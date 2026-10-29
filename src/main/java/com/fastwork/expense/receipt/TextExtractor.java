package com.fastwork.expense.receipt;

import java.nio.file.Path;

/** Ekstraksi teks dari gambar struk (implementasi nyata: Tesseract CLI). */
public interface TextExtractor {

    String extract(Path imageFile);
}
