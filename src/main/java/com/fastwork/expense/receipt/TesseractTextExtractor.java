package com.fastwork.expense.receipt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Ekstraktor teks via CLI `tesseract` (tanpa dependensi Java/native JNI). */
@Component
@RequiredArgsConstructor
public class TesseractTextExtractor implements TextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TesseractTextExtractor.class);
    private static final int ERR_TAIL = 400;

    private final ReceiptProperties props;

    @Override
    public String extract(Path imageFile) {
        List<String> command =
                List.of(
                        props.getBinary(),
                        imageFile.toString(),
                        "stdout",
                        "-l",
                        props.getLanguages(),
                        "--psm",
                        props.getPsm());
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            final Process proc = process;
            CompletableFuture<byte[]> errFuture =
                    CompletableFuture.supplyAsync(() -> readAll(proc.getErrorStream()));
            byte[] out = readAll(process.getInputStream());
            byte[] err = errFuture.join();
            boolean finished = process.waitFor(props.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ReceiptOcrException(
                        "tesseract timeout setelah " + props.getTimeoutSeconds() + "s");
            }
            if (process.exitValue() != 0) {
                throw new ReceiptOcrException(
                        "tesseract exit " + process.exitValue() + ": " + tail(err));
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ReceiptOcrException("tesseract tidak tersedia: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ReceiptOcrException("tesseract terinterupsi", ex);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static byte[] readAll(InputStream in) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (in) {
            in.transferTo(buffer);
        } catch (IOException ex) {
            log.debug("gagal membaca stream tesseract", ex);
        }
        return buffer.toByteArray();
    }

    private static String tail(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        return text.length() <= ERR_TAIL ? text : text.substring(text.length() - ERR_TAIL);
    }
}
