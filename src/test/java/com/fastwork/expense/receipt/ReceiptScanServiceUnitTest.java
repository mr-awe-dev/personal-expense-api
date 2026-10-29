package com.fastwork.expense.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import com.fastwork.expense.common.ApiException;

/** Layanan scan struk diuji unit penuh: validasi berkas, jalur best-of, dan tangkapan error. */
class ReceiptScanServiceUnitTest {

    private static final String LOW_CONFIDENCE = "TOTAL 5.000";
    private static final String HIGH_CONFIDENCE =
            """
            WARUNG ABC SENTOSA
            Jl. Mawar 1
            24/10/2026 10:00
            Kopi 5.000
            TOTAL
            Rp 9.000
            """;

    private final TextExtractor extractor = mock(TextExtractor.class);
    private final ReceiptProperties props = new ReceiptProperties();

    @TempDir Path temp;

    private byte[] realPng() throws IOException {
        BufferedImage image = new BufferedImage(1000, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1000, 600);
        g.setColor(Color.BLACK);
        g.drawString("TOTAL 5.000", 100, 300);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private ReceiptScanService service() {
        return new ReceiptScanService(extractor, props);
    }

    @Test
    void nullAndEmptyFilesRejected() {
        assertThatThrownBy(() -> service().scan(null))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo("INVALID_IMAGE"));
        assertThatThrownBy(
                        () ->
                                service()
                                        .scan(
                                                new MockMultipartFile(
                                                        "file", "x.png", "image/png", new byte[0])))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo("INVALID_IMAGE"));
    }

    @Test
    void oversizedFileRejectedWithReadableLimit() {
        props.setMaxImageBytes(10L);
        MockMultipartFile big =
                new MockMultipartFile("file", "x.png", "image/png", new byte[20]);
        assertThatThrownBy(() -> service().scan(big))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex ->
                                assertThat(ex.getMessage())
                                        .contains("Foto terlalu besar (maks 0 MB)"));
    }

    @Test
    void missingContentTypeRejectedButUpperCaseImageAccepted() {
        assertThatThrownBy(
                        () ->
                                service()
                                        .scan(
                                                new MockMultipartFile(
                                                        "file", "x.bin", null, new byte[] {1, 2, 3})))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo("INVALID_IMAGE"));

        given(extractor.extract(any())).willReturn(LOW_CONFIDENCE);
        ReceiptScanResponse response =
                service()
                        .scan(
                                new MockMultipartFile(
                                        "file",
                                        "x.txt",
                                        "IMAGE/JPEG",
                                        "halo".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(response.amount()).isEqualTo(5000L);
    }

    @Test
    void undecodableImageFallsBackToSinglePass() {
        given(extractor.extract(any())).willReturn(LOW_CONFIDENCE);
        MockMultipartFile fake =
                new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8});

        ReceiptScanResponse response = service().scan(fake);

        assertThat(response.amount()).isEqualTo(5000L);
        verify(extractor, times(1)).extract(any());
        assertThat(response.ocrText()).isNull();
    }

    @Test
    void bestOfPrefersHigherConfidenceProcessedPass() throws IOException {
        given(extractor.extract(any())).willReturn(LOW_CONFIDENCE, HIGH_CONFIDENCE);
        MockMultipartFile png = new MockMultipartFile("file", "x.png", "image/png", realPng());

        ReceiptScanResponse response = service().scan(png);

        assertThat(response.amount()).isEqualTo(9000L);
        assertThat(response.date()).isEqualTo("2026-10-24");
        assertThat(response.description()).isEqualTo("WARUNG ABC SENTOSA");
        verify(extractor, times(2)).extract(any());
    }

    @Test
    void bestOfKeepsOriginalWhenProcessedWorse() throws IOException {
        given(extractor.extract(any())).willReturn(HIGH_CONFIDENCE, LOW_CONFIDENCE);
        MockMultipartFile png = new MockMultipartFile("file", "x.png", "image/png", realPng());

        assertThat(service().scan(png).amount()).isEqualTo(9000L);
    }

    @Test
    void blankProcessedTextKeepsOriginal() throws IOException {
        given(extractor.extract(any())).willReturn(LOW_CONFIDENCE, "   ");
        MockMultipartFile png = new MockMultipartFile("file", "x.png", "image/png", realPng());

        assertThat(service().scan(png).amount()).isEqualTo(5000L);
    }

    @Test
    void processedExtractFailureKeepsOriginal() throws IOException {
        given(extractor.extract(any(Path.class)))
                .willReturn(LOW_CONFIDENCE)
                .willThrow(new ReceiptOcrException("pass kedua gagal"));
        MockMultipartFile png = new MockMultipartFile("file", "x.png", "image/png", realPng());

        assertThat(service().scan(png).amount()).isEqualTo(5000L);
    }

    @Test
    void webpExtensionAccepted() {
        given(extractor.extract(any())).willReturn(LOW_CONFIDENCE);
        MockMultipartFile webp =
                new MockMultipartFile("file", "x.webp", "image/webp", new byte[] {1, 2, 3});
        assertThat(service().scan(webp).amount()).isEqualTo(5000L);
    }

    @Test
    void debugFlagReturnsBestText() throws IOException {
        props.setDebugReturnOcrText(true);
        given(extractor.extract(any())).willReturn(LOW_CONFIDENCE, HIGH_CONFIDENCE);
        MockMultipartFile png = new MockMultipartFile("file", "x.png", "image/png", realPng());

        ReceiptScanResponse response = service().scan(png);
        assertThat(response.ocrText()).contains("WARUNG ABC SENTOSA");
    }

    @Test
    void nullOcrTextMapsToUnprocessable() {
        given(extractor.extract(any())).willReturn(null);
        MockMultipartFile fake =
                new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[] {1, 2, 3});
        assertThatThrownBy(() -> service().scan(fake))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> {
                            assertThat(ex.code()).isEqualTo("RECEIPT_UNREADABLE");
                            assertThat(ex.status().value()).isEqualTo(422);
                        });
    }

    @Test
    void extractorFailureMapsToUnprocessable() {
        willThrow(new ReceiptOcrException("biner hilang")).given(extractor).extract(any());
        MockMultipartFile fake =
                new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[] {1, 2, 3});
        assertThatThrownBy(() -> service().scan(fake))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo("RECEIPT_UNREADABLE"));
    }

    @Test
    void ioFailureMapsToServiceUnavailable() throws Exception {
        MultipartFile broken = mock(MultipartFile.class);
        given(broken.isEmpty()).willReturn(false);
        given(broken.getSize()).willReturn(1234L);
        given(broken.getContentType()).willReturn("image/jpeg");
        org.mockito.BDDMockito.willThrow(new IOException("disk penuh"))
                .given(broken)
                .transferTo(any(Path.class));

        assertThatThrownBy(() -> service().scan(broken))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> {
                            assertThat(ex.code()).isEqualTo("OCR_UNAVAILABLE");
                            assertThat(ex.status().value()).isEqualTo(503);
                        });
    }

    @Test
    void deleteQuietlySwallowsIoErrors() throws Exception {
        Path dir = Files.createTempDirectory(temp, "pakai-");
        Files.writeString(dir.resolve("isi.txt"), "x");
        // Direktori terisi tidak bisa dihapus → cabang IOException harus diam (tidak melempar).
        ReflectionTestUtils.invokeMethod(
                ReceiptScanService.class, "deleteQuietly", (Object) dir);
    }
}
