package com.fastwork.expense.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class ReceiptParserTest {

    @Test
    void emptyTextYieldsNothing() {
        ReceiptParser.Extracted e = ReceiptParser.parse("");
        assertThat(e.amount()).isNull();
        assertThat(e.date()).isNull();
        assertThat(e.merchant()).isNull();
        assertThat(e.confidence()).isZero();
        assertThat(ReceiptParser.parse(null).amount()).isNull();
    }

    @Test
    void totalKeywordDrivesAmount() {
        ReceiptParser.Extracted e =
                ReceiptParser.parse(
                        """
                        WARUNG KOPI NUSANTARA
                        Jalan Melati 12
                        24/10/2026  14:32
                        Kopi Susu 18.000
                        Roti Bakar 27.000
                        TOTAL
                        Rp 45.000
                        """);
        assertThat(e.amount()).isEqualTo(45000L);
        assertThat(e.date()).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(e.merchant()).isEqualTo("WARUNG KOPI NUSANTARA");
        assertThat(e.confidence()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void jumlahKeywordIndonesian() {
        ReceiptParser.Extracted e = ReceiptParser.parse("JUMLAH 120.500,-\nTUNAI 120.500,-");
        assertThat(e.amount()).isEqualTo(120500L);
    }

    @Test
    void changeLineIsExcluded() {
        ReceiptParser.Extracted e =
                ReceiptParser.parse(
                        """
                        TOKO ABC
                        TOTAL 45.000
                        TUNAI 100.000
                        KEMBALI 55.000
                        """);
        assertThat(e.amount()).isEqualTo(45000L);
    }

    @Test
    void fallbackUsesGroupedNumberAndCapsConfidence() {
        ReceiptParser.Extracted e =
                ReceiptParser.parse(
                        """
                        Minimarket Sejahtera
                        Belanja bulanan
                        1.200.000
                        """);
        assertThat(e.amount()).isEqualTo(1200000L);
        assertThat(e.confidence()).isLessThanOrEqualTo(55);
    }

    @Test
    void plainDigitsWithoutCurrencyAreIgnoredInFallback() {
        ReceiptParser.Extracted e = ReceiptParser.parse("No. Struk 83720191\nTerima kasih");
        assertThat(e.amount()).isNull();
    }

    @Test
    void amountClampedToMax() {
        ReceiptParser.Extracted e = ReceiptParser.parse("TOTAL 999.999.999.999.999");
        assertThat(e.amount()).isNull();
    }

    @Test
    void englishGroupingSupported() {
        ReceiptParser.Extracted e = ReceiptParser.parse("TOTAL AMOUNT DUE 1,234.56");
        assertThat(e.amount()).isEqualTo(1234L);
    }

    @Test
    void isoDateSupported() {
        assertThat(ReceiptParser.parse("2026-10-24\nTOTAL 5.000").date())
                .isEqualTo(LocalDate.of(2026, 10, 24));
    }

    @Test
    void monthNameDateSupported() {
        assertThat(ReceiptParser.parse("24 Okt 2026\nTOTAL 5.000").date())
                .isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(ReceiptParser.parse("5 Mei 2026\nTOTAL 5.000").date())
                .isEqualTo(LocalDate.of(2026, 5, 5));
    }

    @Test
    void twoDigitYearBecomesTwoThousands() {
        assertThat(ReceiptParser.parse("24/10/26\nTOTAL 5.000").date())
                .isEqualTo(LocalDate.of(2026, 10, 24));
    }

    @Test
    void swappedDateWhenSecondImpossible() {
        assertThat(ReceiptParser.parse("10/25/2026\nTOTAL 5.000").date())
                .isEqualTo(LocalDate.of(2026, 10, 25));
    }

    @Test
    void futureDateRejected() {
        LocalDate far = LocalDate.now().plusYears(2);
        String line =
                String.format(
                        "%02d/%02d/%d", far.getDayOfMonth(), far.getMonthValue(), far.getYear());
        assertThat(ReceiptParser.parse(line + "\nTOTAL 5.000").date()).isNull();
    }

    @Test
    void merchantSkipsNumericAndKeywordLines() {
        ReceiptParser.Extracted e =
                ReceiptParser.parse(
                        """
                        021 555 1234
                        RINCIAN BELANJA
                        Supermarket Berkah Jaya
                        TOTAL 10.000
                        """);
        assertThat(e.merchant()).isEqualTo("Supermarket Berkah Jaya");
    }

    @Test
    void confidenceSumsUpToHundred() {
        ReceiptParser.Extracted e =
                ReceiptParser.parse(
                        """
                        MERCHANT UJI SATU
                        Jl. Test No. 1
                        24/10/2026
                        Item A 1.000
                        Item B 2.000
                        TOTAL 3.000
                        """);
        assertThat(e.confidence()).isEqualTo(100);
    }

    /** Teks OCR NYATA dari foto HP (Tesseract 4.1.1, psm 6): label rusak + total salah digit. */
    @Test
    void realPhotoOcrNoisePsm6ResolvesToTrueTotal() {
        ReceiptParser.Extracted e =
                ReceiptParser.parse(
                        """
                        Calf
                        Kopi Calf Jatiwarna
                        Jl. Raya Hankam No.68,
                        Bekasi, Jawa Barat 17415
                        Penjualan : SCTG18039307594277
                        Tanggal : 27-10-2026 11:39
                        Es Kopi Calf Premium
                        2x 420.000 40.000
                        Dimsum Mentai
                        1x 423,000 23.000
                        3 item
                        Subtotal : 63.000
                        PBI : 6.300
                        Grand Total; ‘69,900
                        Aris : 69.300
                        - Thank You -
                        """);
        assertThat(e.amount()).isEqualTo(69300L);
    }

    /** Varian nyata lain (psm 4 / hasil pra-pemrosesan): total terpotong "69.00" + "Oris". */
    /** Teks OCR NYATA struk BEEPOS (sample/test.jpg, Tesseract 5.3.4 psm 6): subtotal ber-spasi
     *  dan total rusak — bukti subtotal+pajak dan tunai−kembalian harus memulihkan 191.475. */
    @Test
    void realPhotoBeeposBrokenTotalResolvesViaEvidence() {
        ReceiptParser.Extracted e =
                ReceiptParser.parse(
                        """
                        DO berepos
                        Cafe Bee
                        J]. Kemang Selatan I No.20
                        urabaya, 021-33935800
                        Hau Tanggal: 24-05-2093 16145
                        BN Kasir : Hafiz
                        Member ; CASH
                        mm = Channel: Dine In
                        Bakso
                        2 x 12,000 24,000
                        3 x 1,500 22,500
                        . Cap Jay
                        | 3x 17,000 51,000
                        Cumi Goreng Tepung Gurih
                        3 x 25,000 15,000
                        1 Subtotal 172, 500
                        8 Pajak = 18,975
                        Total 191,41,
                        Fe TUNAI 200,000.
                        = Kenbalian 8,525
                        """);
        assertThat(e.amount()).isEqualTo(191475L);
    }

    @Test
    void realPhotoOcrNoiseTruncatedTotalResolvesToTrueTotal() {
        ReceiptParser.Extracted e =
                ReceiptParser.parse(
                        """
                        Kopi Calf Jatiwarna
                        Tanggal : 27-10-2026 11:39
                        Es Kopi Calf Premium
                        2x 420.000 40.000
                        3 item
                        Subtotal : 63.000
                        PBI: 6.300
                        Grand Total; 69.00
                        : Oris : 69.300
                        - Thank You -
                        """);
        assertThat(e.amount()).isEqualTo(69300L);
    }

    @Test
    void misreadGrandTotalCorrectedBySubtotalSumAndPayment() {        ReceiptParser.Extracted e =
                ReceiptParser.parse(
                        """
                        Kopi Calf Jatiwarna
                        Tanggal : 27-10-2026 11:39
                        Es Kopi Calf Premium 2x @20.000 40.000
                        Dimsum Mentai 1x @23.000 23.000
                        Subtotal : 63.000
                        PB1 : 6.300
                        Grand Total : 69.900
                        Qris : 69.300
                        """);
        assertThat(e.amount()).isEqualTo(69300L);
    }

    @Test
    void misreadGrandTotalCorrectedBySumWithoutPaymentLine() {
        ReceiptParser.Extracted e =
                ReceiptParser.parse(
                        """
                        KEDAI KOPI
                        27-10-2026
                        Subtotal : 63.000
                        PB1 : 6.300
                        Grand Total : 69.900
                        """);
        assertThat(e.amount()).isEqualTo(69300L);
    }

    @Test
    void consistentReceiptKeepsGrandTotalAndBoostsConfidence() {
        ReceiptParser.Extracted e =
                ReceiptParser.parse(
                        """
                        KEDAI KOPI SENTOSA
                        Jl. Melati 5
                        27-10-2026 11:39
                        Es Kopi 40.000
                        Dimsum 23.000
                        Subtotal : 63.000
                        PB1 : 6.300
                        Grand Total : 69.300
                        Qris : 69.300
                        """);
        assertThat(e.amount()).isEqualTo(69300L);
        assertThat(e.confidence()).isEqualTo(100);
    }

    @Test
    void discountReceiptSkipsSumCorrectionButPaymentStillCorroborates() {
        ReceiptParser.Extracted e =
                ReceiptParser.parse(
                        """
                        RESTO UJI
                        27-10-2026
                        Subtotal : 63.000
                        Diskon Member : 3.000
                        Grand Total : 69.900
                        Qris : 69.300
                        """);
        assertThat(e.amount()).isEqualTo(69300L);
    }

    @Test
    void largeMismatchIsNotTouched() {
        ReceiptParser.Extracted e =
                ReceiptParser.parse(
                        """
                        TOKO MAJU
                        27-10-2026
                        Subtotal : 63.000
                        PB1 : 6.300
                        Grand Total : 75.000
                        """);
        assertThat(e.amount()).isEqualTo(75000L);
    }

    @Test
    void subtotalOnlyReceiptStillYieldsSubtotal() {
        ReceiptParser.Extracted e =
                ReceiptParser.parse(
                        """
                        WARUNG SEDERHANA
                        27-10-2026
                        Nasi Goreng 25.000
                        Subtotal : 25.000
                        """);
        assertThat(e.amount()).isEqualTo(25000L);
    }
}
