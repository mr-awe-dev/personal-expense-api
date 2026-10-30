package com.fastwork.expense.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ekstraktor Tesseract diuji lewat skrip pembungkus buatan (echo/sleep/exit) sehingga tidak butuh
 * biner tesseract asli dan berjalan di Windows maupun Linux.
 */
class TesseractTextExtractorTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    @TempDir Path temp;

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    private Path script(String name, String unixBody, String windowsBody) throws IOException {
        Path file;
        if (WINDOWS) {
            file = temp.resolve(name + ".bat");
            Files.writeString(file, "@echo off\r\n" + windowsBody + "\r\n", StandardCharsets.UTF_8);
        } else {
            file = temp.resolve(name + ".sh");
            Files.writeString(file, "#!/bin/sh\n" + unixBody + "\n", StandardCharsets.UTF_8);
            file.toFile().setExecutable(true);
        }
        return file;
    }

    private ReceiptProperties props(String binary) {
        ReceiptProperties props = new ReceiptProperties();
        props.setBinary(binary);
        return props;
    }

    @Test
    void returnsStdoutOnSuccess() throws Exception {
        Path script = script("ok", "echo TOTAL 5.000", "echo TOTAL 5.000");
        TesseractTextExtractor extractor = new TesseractTextExtractor(props(script.toString()));
        String text = extractor.extract(temp.resolve("apapun.jpg"));
        assertThat(text).contains("TOTAL 5.000");
    }

    @Test
    void nonZeroExitIsOcrExceptionWithStderrTail() throws Exception {
        Path script =
                script(
                        "fail",
                        "echo GAGAL-OFFLINE 1>&2; exit 3",
                        "echo GAGAL-OFFLINE 1>&2\r\nexit /b 3");
        TesseractTextExtractor extractor = new TesseractTextExtractor(props(script.toString()));
        assertThatThrownBy(() -> extractor.extract(temp.resolve("x.jpg")))
                .isInstanceOfSatisfying(
                        ReceiptOcrException.class,
                        ex -> {
                            assertThat(ex.getMessage()).startsWith("tesseract exit 3");
                            assertThat(ex.getMessage()).contains("GAGAL-OFFLINE");
                        });
    }

    @Test
    void longStderrIsTruncatedToTail() throws Exception {
        String marker = "AWAL" + "e".repeat(600);
        Path script;
        if (WINDOWS) {
            script = script("longerr", "", "echo " + marker + " 1>&2\r\nexit /b 9");
        } else {
            script = script("longerr", "echo " + marker + " 1>&2; exit 9", "");
        }
        TesseractTextExtractor extractor = new TesseractTextExtractor(props(script.toString()));
        assertThatThrownBy(() -> extractor.extract(temp.resolve("x.jpg")))
                .isInstanceOfSatisfying(
                        ReceiptOcrException.class,
                        ex -> assertThat(ex.getMessage()).doesNotContain("AWAL"));
    }

    @Test
    void timeoutKillsProcessAndThrows() throws Exception {
        ReceiptProperties props = props(javaBinary());
        props.setTimeoutSeconds(2);
        TesseractTextExtractor extractor = new TesseractTextExtractor(props);
        // "Hold.java" menutup stdout/stderr lalu tertidur: stream EOF tapi proses hidup
        assertThatThrownBy(() -> extractor.extract(holdSource()))
                .isInstanceOfSatisfying(
                        ReceiptOcrException.class,
                        ex -> assertThat(ex.getMessage()).contains("timeout"));
    }

    @Test
    void interruptedThreadRestoresFlagAndThrows() throws Exception {
        ReceiptProperties props = props(javaBinary());
        props.setTimeoutSeconds(15);
        TesseractTextExtractor extractor = new TesseractTextExtractor(props);
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> extractor.extract(holdSource()))
                .isInstanceOfSatisfying(
                        ReceiptOcrException.class,
                        ex ->
                                assertThat(ex.getMessage())
                                        .isIn(
                                                "tesseract terinterupsi",
                                                "tesseract timeout setelah 15s"));
        assertThat(Thread.interrupted()).isTrue();
    }

    private static String javaBinary() {
        String exe =
                System.getProperty("os.name", "").toLowerCase().contains("win")
                        ? "java.exe"
                        : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    /** Sumber Java satu-file: penutup stream lalu tidur, dipakai sebagai "tesseract" palsu. */
    private Path holdSource() throws IOException {
        Path file = temp.resolve("Hold.java");
        Files.writeString(
                file,
                """
                public class Hold {
                    public static void main(String[] args) throws Exception {
                        System.out.close();
                        System.err.close();
                        Thread.sleep(30_000);
                    }
                }
                """);
        return file;
    }

    @Test
    void missingBinaryReportsUnavailable() {
        TesseractTextExtractor extractor =
                new TesseractTextExtractor(props(temp.resolve("tidak-ada-x").toString()));
        assertThatThrownBy(() -> extractor.extract(temp.resolve("x.jpg")))
                .isInstanceOfSatisfying(
                        ReceiptOcrException.class,
                        ex -> assertThat(ex.getMessage()).startsWith("tesseract tidak tersedia"));
    }

    @Test
    void readAllSwallowsIoErrorFromStream() {
        java.io.InputStream failing =
                new java.io.InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("stream putus");
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        throw new IOException("stream putus");
                    }
                };
        byte[] result =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                        TesseractTextExtractor.class, "readAll", failing);
        assertThat(result).isEmpty();
    }

    @Test
    void propertiesDefaultsAndExceptionCause() {
        ReceiptProperties props = new ReceiptProperties();
        assertThat(props.getBinary()).isEqualTo("tesseract");
        assertThat(props.getLanguages()).isEqualTo("ind+eng");
        assertThat(props.getPsm()).isEqualTo("6");
        assertThat(props.getTimeoutSeconds()).isEqualTo(25);
        assertThat(props.getMaxImageBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(props.isDebugReturnOcrText()).isFalse();

        props.setBinary("/usr/bin/tesseract");
        props.setLanguages("eng");
        props.setPsm("4");
        props.setTimeoutSeconds(3);
        props.setMaxImageBytes(100L);
        props.setDebugReturnOcrText(true);
        assertThat(props.getBinary()).isEqualTo("/usr/bin/tesseract");
        assertThat(props.getLanguages()).isEqualTo("eng");
        assertThat(props.getPsm()).isEqualTo("4");
        assertThat(props.getTimeoutSeconds()).isEqualTo(3);
        assertThat(props.getMaxImageBytes()).isEqualTo(100L);
        assertThat(props.isDebugReturnOcrText()).isTrue();

        IOException cause = new IOException("io");
        ReceiptOcrException withCause = new ReceiptOcrException("gagal", cause);
        assertThat(withCause.getCause()).isSameAs(cause);
        assertThat(new ReceiptOcrException("polos").getMessage()).isEqualTo("polos");
    }
}
