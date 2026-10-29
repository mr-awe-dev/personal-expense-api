package com.fastwork.expense.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pra-pemrosesan OCR diuji dengan gambar sintetis tanpa Tesseract. */
class ReceiptImagePreprocessorTest {

    @TempDir Path temp;

    private Path writePng(int width, int height, long seed) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        Random random = new Random(seed);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        for (int i = 0; i < 40; i++) {
            g.fillRect(random.nextInt(width), random.nextInt(height), 6, 12);
        }
        g.dispose();
        Path target = Files.createTempFile(temp, "struk-", ".png");
        ImageIO.write(image, "png", target.toFile());
        return target;
    }

    @Test
    void preprocessProducesGrayscalePng() throws Exception {
        Path source = writePng(1000, 600, 1);
        Path target = temp.resolve("out.png");

        assertThat(ReceiptImagePreprocessor.preprocess(source, target)).isTrue();

        BufferedImage result = ImageIO.read(target.toFile());
        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(BufferedImage.TYPE_BYTE_GRAY);
    }

    @Test
    void preprocessReturnsFalseForUndecodableImage() throws Exception {
        Path source = temp.resolve("rusak.png");
        Files.write(source, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        assertThat(ReceiptImagePreprocessor.preprocess(source, temp.resolve("out.png")))
                .isFalse();
    }

    @Test
    void preprocessReturnsFalseWhenTargetUnwritable() throws Exception {
        Path source = writePng(1000, 600, 2);
        Path target = temp.resolve("tidak-ada/ subdir/out.png");
        assertThat(ReceiptImagePreprocessor.preprocess(source, target)).isFalse();
    }

    @Test
    void preprocessReturnsFalseForMissingSource() throws Exception {
        assertThat(
                        ReceiptImagePreprocessor.preprocess(
                                temp.resolve("tidak-ada.png"), temp.resolve("out.png")))
                .isFalse();
    }

    @Test
    void scaleKeepsAlreadyFittingWidthUnchanged() throws Exception {
        BufferedImage image = new BufferedImage(1000, 500, BufferedImage.TYPE_INT_RGB);
        assertThat(ReceiptImagePreprocessor.scale(image)).isSameAs(image);
    }

    @Test
    void scaleUpsizesSmallImagesUpToTwoTimes() throws Exception {
        BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        BufferedImage scaled = ReceiptImagePreprocessor.scale(image);
        assertThat(scaled.getWidth()).isEqualTo(1600);
        assertThat(scaled.getHeight()).isEqualTo(1200);
    }

    @Test
    void scaleDownsizesWideImagesToMaxWidth() throws Exception {
        BufferedImage image = new BufferedImage(2000, 400, BufferedImage.TYPE_INT_RGB);
        BufferedImage scaled = ReceiptImagePreprocessor.scale(image);
        assertThat(scaled.getWidth()).isEqualTo(1600);
    }

    @Test
    void scaleDownsizesWhenOverPixelBudget() throws Exception {
        BufferedImage image = new BufferedImage(9000, 9000, BufferedImage.TYPE_INT_RGB);
        BufferedImage scaled = ReceiptImagePreprocessor.scale(image);
        assertThat(scaled.getWidth()).isEqualTo(1600);
        assertThat((long) scaled.getWidth() * scaled.getHeight())
                .isLessThanOrEqualTo(ReceiptImagePreprocessor.MAX_WIDTH * 1600L);
    }

    @Test
    void scaleKeepsWideButShortImagesWithEnoughPixels() throws Exception {
        BufferedImage image = new BufferedImage(2500, 2500, BufferedImage.TYPE_INT_RGB);
        BufferedImage scaled = ReceiptImagePreprocessor.scale(image);
        assertThat(scaled.getWidth()).isEqualTo(1600);
    }

    @Test
    void toGrayUsesLumaWeights() {
        BufferedImage image = new BufferedImage(3, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xFF0000);
        image.setRGB(1, 0, 0x00FF00);
        image.setRGB(2, 0, 0x0000FF);

        int[] gray = ReceiptImagePreprocessor.toGray(image);
        assertThat(gray[0]).isEqualTo(76);
        assertThat(gray[1]).isEqualTo(150);
        assertThat(gray[2]).isEqualTo(29);
    }

    @Test
    void stretchContrastKeepsNeutralRange() {
        int[] flat = new int[] {120, 120, 120, 120};
        assertThat(ReceiptImagePreprocessor.stretchContrast(flat)).isSameAs(flat);
    }

    @Test
    void stretchContrastSpreadsWideHistogram() {
        int[] gray = new int[50];
        for (int i = 0; i < 50; i++) {
            gray[i] = 100 + i;
        }
        int[] stretched = ReceiptImagePreprocessor.stretchContrast(gray);
        assertThat(stretched[0]).isZero();
        assertThat(stretched[stretched.length - 1]).isEqualTo(255);
    }

    @Test
    void constantsAreDocumented() {
        assertThat(ReceiptImagePreprocessor.MAX_WIDTH).isEqualTo(1600);
        assertThat(ReceiptImagePreprocessor.MIN_WIDTH).isEqualTo(900);
        assertThat(ReceiptImagePreprocessor.MAX_PIXELS).isEqualTo(24_000_000L);
    }

    @Test
    void grayscaleRoundTripThroughBytes() throws Exception {
        BufferedImage image = new BufferedImage(50, 50, BufferedImage.TYPE_BYTE_GRAY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(out.toByteArray())))
                .isNotNull();
    }
}
