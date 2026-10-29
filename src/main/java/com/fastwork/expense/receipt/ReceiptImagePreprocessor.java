package com.fastwork.expense.receipt;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pra-pemrosesan foto struk sebelum masuk Tesseract (tanpa dependensi baru):
 * resize (maks 1600 px / naikkan bila terlalu kecil) → grayscale → contrast-stretch
 * persentil 2–98. Binerisasi Sauvola DAN mask-latar-gelap sengaja TIDAK dipakai:
 * Sauvola merusak digit tebal ("69.300" → "== 9.900"), sementara mask latar menghancurkan
 * angka di bawah bayangan gelap di atas kertas (kasus BEEPOS: baris Total jadi "-¢").
 * Layanan pemindai memakai strategi best-of (teks asli vs teks pra-pemrosesan) sehingga
 * pra-pemrosesan tidak pernah bisa merugikan.
 * Gagal apa pun (format tak terbaca, I/O) → {@code false}; pemanggil tetap memakai gambar asli.
 */
final class ReceiptImagePreprocessor {

    private static final Logger log = LoggerFactory.getLogger(ReceiptImagePreprocessor.class);

    static final int MAX_WIDTH = 1600;
    static final int MIN_WIDTH = 900;
    static final long MAX_PIXELS = 24_000_000L;

    private ReceiptImagePreprocessor() {}

    static boolean preprocess(Path source, Path targetPng) {
        try {
            BufferedImage image = ImageIO.read(source.toFile());
            if (image == null) {
                return false;
            }
            image = scale(image);
            int width = image.getWidth();
            int height = image.getHeight();
            int[] gray = stretchContrast(toGray(image));
            BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            out.setRGB(0, 0, width, height, gray, 0, width);
            return ImageIO.write(out, "png", targetPng.toFile());
        } catch (IOException | RuntimeException ex) {
            log.debug("pra-pemrosesan struk gagal — tetap pakai gambar asli", ex);
            return false;
        }
    }

    static BufferedImage scale(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        double factor = 1.0;
        if ((long) width * height > MAX_PIXELS) {
            factor = Math.sqrt((double) MAX_PIXELS / ((long) width * height));
        }
        if (width * factor > MAX_WIDTH) {
            factor = (double) MAX_WIDTH / width;
        } else if (width * factor < MIN_WIDTH && width < MIN_WIDTH) {
            factor = Math.min((double) MIN_WIDTH / width * 2, (double) MAX_WIDTH / width);
        }
        if (Math.abs(factor - 1.0) < 0.01) {
            return image;
        }
        int targetWidth = Math.max(1, (int) Math.round(width * factor));
        int targetHeight = Math.max(1, (int) Math.round(height * factor));
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return scaled;
    }

    static int[] toGray(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] rgb = image.getRGB(0, 0, width, height, null, 0, width);
        int[] gray = new int[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            gray[i] = (int) Math.min(255, Math.round(0.299 * r + 0.587 * g + 0.114 * b));
        }
        return gray;
    }

    /** Linear stretch antara persentil 2 dan 98; netral bila rentang sudah lebar. */
    static int[] stretchContrast(int[] gray) {
        long[] histogram = new long[256];
        for (int v : gray) {
            histogram[v]++;
        }
        long total = gray.length;
        int low = 0;
        int high = 255;
        long cumulative = 0;
        for (int v = 0; v < 256; v++) {
            cumulative += histogram[v];
            if (cumulative >= total * 0.02) {
                low = v;
                break;
            }
        }
        cumulative = 0;
        for (int v = 255; v >= 0; v--) {
            cumulative += histogram[v];
            if (cumulative >= total * 0.02) {
                high = v;
                break;
            }
        }
        if (high - low < 40) {
            return gray;
        }
        int[] out = new int[gray.length];
        double scale = 255.0 / (high - low);
        for (int i = 0; i < gray.length; i++) {
            out[i] = (int) Math.max(0, Math.min(255, Math.round((gray[i] - low) * scale)));
        }
        return out;
    }

}
