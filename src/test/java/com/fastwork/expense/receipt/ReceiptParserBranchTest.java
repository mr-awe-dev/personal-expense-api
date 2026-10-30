package com.fastwork.expense.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pelengkap matriks cabang {@link ReceiptParser} untuk menutup jalur heuristik yang tidak tersentuh
 * {@code ReceiptParserTest}: baris IGNORE, carry value-only, parsial satu digit, bukti ambigu,
 * tarif pajak, confusable, dan filter merchant.
 */
class ReceiptParserBranchTest {

    private static ReceiptParser.Extracted parse(String text) {
        return ReceiptParser.parse(text);
    }

    @Test
    void whitespaceOnlyTextReturnsZero() {
        ReceiptParser.Extracted e = parse("   \n \t \n");
        assertThat(e.amount()).isNull();
        assertThat(e.confidence()).isZero();
    }

    @Test
    void blankLinesBetweenContentAreSkipped() {
        ReceiptParser.Extracted e = parse("WARUNG A\n\nTOTAL   5.000\n   \nTerima kasih");
        assertThat(e.amount()).isEqualTo(5000L);
    }

    @Test
    void saldoLineIsIgnored() {
        ReceiptParser.Extracted e = parse("SALDO AWAL 50.000\nTOTAL 20.000");
        assertThat(e.amount()).isEqualTo(20000L);
    }

    @Test
    void carryValueOnlyDateLinesAreNotConsumed() {
        assertThat(parse("GRAND TOTAL\nRp 2026-05-12").amount()).isEqualTo(2026L); // fallback
        assertThat(parse("GRAND TOTAL\n14:30").amount()).isNull();
        assertThat(parse("GRAND TOTAL\n12/05/2026").amount()).isNull();
        assertThat(parse("GRAND TOTAL\n05 September 2026").amount()).isNull();
    }

    @Test
    void carryOnTextLineFallsThroughToNoValue() {
        assertThat(parse("TOTAL\nTHANK YOU").amount()).isNull();
        assertThat(parse("GRAND TOTAL\nRp").amount()).isNull();
    }

    @Test
    void partialTotalWithoutEvidenceYieldsMidpointLowConfidence() {
        ReceiptParser.Extracted e = parse("TOTAL 191,4");
        assertThat(e.amount()).isEqualTo(191450L);
        assertThat(e.confidence()).isLessThanOrEqualTo(55);
    }

    @Test
    void partialTotalCompletedByUniquePaymentEvidence() {
        ReceiptParser.Extracted e = parse("Grand Total 191,4\nQRIS 191.475");
        assertThat(e.amount()).isEqualTo(191475L);
        assertThat(e.confidence()).isGreaterThanOrEqualTo(50);
    }

    @Test
    void duplicateEvidenceCountsOnceForPartialPick() {
        ReceiptParser.Extracted e =
                parse("Grand Total 191,4\nQRIS 191.475\nTUNAI 200.000\nKEMBALI 8.525");
        assertThat(e.amount()).isEqualTo(191475L);
    }

    @Test
    void twoDifferentEvidenceInRangeGivesUpPartialPick() {
        ReceiptParser.Extracted e =
                parse("Grand Total 191,4\nQRIS 191.475\nTUNAI 200.000\nKEMBALI 8.600");
        assertThat(e.amount()).isEqualTo(191475L); // jalur pembayaran, bukan partial pick
    }

    @Test
    void strictTotalsPreferExpectedFromSubtotal() {
        ReceiptParser.Extracted e =
                parse("Subtotal : 63.000\nPajak : 6.300\nTOTAL 75.000\nTOTAL 69.300");
        assertThat(e.amount()).isEqualTo(69300L);
    }

    @Test
    void strictTotalsFallBackToLastWhenExpectedMissing() {
        ReceiptParser.Extracted e =
                parse("Subtotal : 10.000\nPajak : 1.000\nTOTAL 75.000\nTOTAL 90.000");
        assertThat(e.amount()).isEqualTo(90000L);
    }

    @Test
    void multipleStrictTotalsWithoutSubtotalTakeLast() {
        ReceiptParser.Extracted e = parse("TOTAL 10.000\nTOTAL 20.000");
        assertThat(e.amount()).isEqualTo(20000L);
    }

    @Test
    void negativeExpectedAndOverflowAreIgnored() {
        assertThat(parse("Subtotal : 1.000\nDiskon : 5.000").amount()).isNull();
        assertThat(parse("Subtotal : 999.999.999.999\nPPN : 999.999.999.999").amount()).isNull();
    }

    @Test
    void tenderedEqualChangeLeavesLowConfidenceTendered() {
        ReceiptParser.Extracted e = parse("TUNAI 100.000\nKEMBALI 100.000");
        assertThat(e.amount()).isEqualTo(100000L);
        assertThat(e.confidence()).isLessThanOrEqualTo(55);
    }

    @Test
    void percentOnlyAndPlainSmallNumbersDoNotCarry() {
        ReceiptParser.Extracted e = parse("KEMBALI 500\nPB1 10%\nDISKON 20\nTUNAI 30");
        assertThat(e.amount()).isNull();
    }

    @Test
    void bareDiscountAndFeeLabelsCarryToNextLine() {
        // DISKON lalu PB1 menyerap nilai "Rp 5.000" sebagai fee; tidak ada total → tanpa nominal.
        assertThat(parse("DISKON\nPB1\nRp 5.000").amount()).isNull();
    }

    @Test
    void subtotalAndPaymentCarryValuesFromNextLine() {
        ReceiptParser.Extracted e = parse("SUBTOTAL\nRp 60.000\nQRIS\nRp 60.000");
        assertThat(e.amount()).isEqualTo(60000L);
    }

    @Test
    void changeAndTenderedCarryValuesAndKeepMaxTendered() {
        assertThat(parse("KEMBALI\nRp 5.000\nTUNAI\nRp 50.000").amount()).isEqualTo(45000L);
        ReceiptParser.Extracted descending = parse("TUNAI 90.000\nTUNAI 50.000\nKEMBALI 5.000");
        assertThat(descending.amount()).isEqualTo(85000L); // tendered max = 90.000
    }

    @Test
    void qualifiedTotalKeepsValueDespitePpnInside() {
        ReceiptParser.Extracted e = parse("TOTAL (INCL. PPN 10%)\nRp 69.300");
        assertThat(e.amount()).isEqualTo(69300L);
    }

    @Test
    void qualifiedSaldoLineStaysTotalLine() {
        ReceiptParser.Extracted e = parse("TOTAL SALDO SETELAH\nRp 5.000");
        assertThat(e.amount()).isEqualTo(5000L);
    }

    @Test
    void smallCurrencyAmountNeedsCurrencyFlag() {
        ReceiptParser.Extracted e = parse("TOTAL Rp 50\nQris 12.000");
        assertThat(e.amount()).isEqualTo(12000L);
    }

    @Test
    void weakPlainNumberAboveThousandCounts() {
        assertThat(parse("JUMLAH 1500").amount()).isEqualTo(1500L);
    }

    @Test
    void absurdlyLongNumberOverflowsDoubleGracefully() {
        String huge = "9".repeat(310);
        assertThat(parse("TOTAL " + huge).amount()).isNull();
    }

    @Test
    void groupedNumberOverflowingLongIsIgnored() {
        String grouped = "999.999.999.999.999.999.999.999";
        assertThat(parse("TOTAL " + grouped).amount()).isNull();
    }

    @Test
    void zeroAmountRejected() {
        assertThat(parse("TOTAL 0").amount()).isNull();
    }

    @Test
    void monthNameUnknownAndInvalidDayRejected() {
        assertThat(parse("5 Fubar 2026\nTOTAL 5.000").date()).isNull();
        assertThat(parse("32 Okt 2026\nTOTAL 5.000").date()).isNull();
    }

    @Test
    void impossibleNumericDateCombinationsRejected() {
        assertThat(parse("25/25/2026\nTOTAL 5.000").date()).isNull();
        assertThat(parse("05/00/2026\nTOTAL 5.000").date()).isNull();
        assertThat(parse("00/05/2026\nTOTAL 5.000").date()).isNull();
        assertThat(parse("31/02/2025\nTOTAL 5.000").date()).isNull();
    }

    @Test
    void pre2000DateRejected() {
        assertThat(parse("01/01/1999\nTOTAL 5.000").date()).isNull();
    }

    @Test
    void merchantLongLineTruncatedToSixty() {
        String longName =
                "WARUNG MAKMUR JAYA SENTOSA ABADI YANG SANGAT PANJANG NAMA TEMPAT INI SEKALI";
        ReceiptParser.Extracted e = parse(longName + "\nTOTAL 5.000");
        assertThat(e.merchant()).hasSize(60);
    }

    @Test
    void merchantSkipsCurrencyLineAndDateishLine() {
        ReceiptParser.Extracted e =
                parse("Rata Rp 20.000\n05 September 2026\nMINUMAN SEGAR\nTOTAL 20.000");
        assertThat(e.merchant()).isEqualTo("MINUMAN SEGAR");
    }

    @Test
    void feeBelowHundredCannotValidateRateAndExpectedCorrectionsApply() {
        // fee 50 di bawah ambang → rateValidated null; lalu alternatif expected (50.000) yang
        // hanya beda satu digit OCR-lazim (6↔5) mengalahkan grand total 60.000.
        ReceiptParser.Extracted e = parse("Subtotal : 50.000\nPPN : 50\nGrand Total : 60.000");
        assertThat(e.amount()).isEqualTo(50000L);
    }

    @Test
    void mismatchedRateCorrectedByExpectedWhenDigitSimilar() {
        ReceiptParser.Extracted e =
                parse("Subtotal : 63.000\nService : 6.800\nGrand Total : 69.900");
        assertThat(e.amount()).isEqualTo(69800L);
    }

    @Test
    void taxRateInferredWhenNoFeeLine() {
        ReceiptParser.Extracted e = parse("Subtotal : 63.000\nGrand Total : 69.900\nQRIS 80.000");
        assertThat(e.amount()).isEqualTo(69300L);
    }

    @Test
    void rateInferredReturnsNullWhenTotalAlreadyMatchesRate() {
        ReceiptParser.Extracted e = parse("Subtotal : 63.000\nGrand Total : 69.300\nQRIS 80.000");
        assertThat(e.amount()).isEqualTo(69300L);
    }

    @Test
    void ambiguousInferredRatesAreRefused() {
        ReceiptParser.Extracted e = parse("Subtotal : 30.000\nGrand Total : 33.800\nQRIS 80.000");
        assertThat(e.amount()).isEqualTo(33800L);
    }

    @Test
    void taxDerivedCompletesPartialTotalWithoutSubtotal() {
        ReceiptParser.Extracted e = parse("Pajak : 6.300\nGrand Total : 69,3");
        assertThat(e.amount()).isEqualTo(69300L);
    }

    @Test
    void confusableDigitPairsOnlyForKnownMisreads() {
        ReceiptParser.Extracted e = parse("Subtotal : 63.000\nPajak : 6.300\nGrand Total : 69.600");
        assertThat(e.amount()).isEqualTo(69300L); // 6↔3 bukan pasangan OCR lazim
    }

    @Test
    void lengthMismatchNeverCorrects() {
        ReceiptParser.Extracted e = parse("Grand Total : 5.000\nSubtotal : 45.000\nPajak : 600");
        assertThat(e.amount()).isEqualTo(5000L);
    }

    @Test
    void paymentAgreeingWithExpectedBoostsConfidence() {
        ReceiptParser.Extracted e = parse("QRIS 45.000\nSubtotal : 45.000");
        assertThat(e.amount()).isEqualTo(45000L);
        assertThat(e.confidence()).isGreaterThanOrEqualTo(45);
    }

    @Test
    void tenderedNetAgreeingWithExpectedBoostsConfidence() {
        ReceiptParser.Extracted e = parse("TUNAI 100.000\nKEMBALI 55.000\nSubtotal : 45.000");
        assertThat(e.amount()).isEqualTo(45000L);
        assertThat(e.confidence()).isGreaterThanOrEqualTo(45);
    }

    @Test
    void grandTotalLabelAloneCarriesToNextLine() {
        ReceiptParser.Extracted e = parse("GRAND TOTAL:\nRp 7.000");
        assertThat(e.amount()).isEqualTo(7000L);
    }

    @Test
    void grandTotalWithoutAnySubtotalWorksAlone() {
        ReceiptParser.Extracted e = parse("Grand Total : 69.000");
        assertThat(e.amount()).isEqualTo(69000L);
    }

    @Test
    void discountWithoutFeeSkipsRateInference() {
        // fee 0 tapi diskon > 0 → cabang `fee == 0 && disc == 0` false → tanpa rateInferred
        ReceiptParser.Extracted e =
                parse("Subtotal : 63.000\nDiskon : 5.000\nGrand Total : 69.900");
        assertThat(e.amount()).isEqualTo(69900L);
    }

    @Test
    void taxDerivedWithDiscountIsUnavailable() {
        // disc != 0 → taxDerived null; tidak ada bukti lain → midpoint parsial (conf 25, cap 55)
        ReceiptParser.Extracted e = parse("Diskon : 3.000\nPajak : 6.300\nGrand Total : 69,3");
        assertThat(e.amount()).isEqualTo(69350L);
        assertThat(e.confidence()).isLessThanOrEqualTo(55);
    }

    @Test
    void agreementOfTwoPaymentsOverridesGrandTotal() {
        // dua bukti independen (QRIS == tunai−kembalian) ≠ grand total → alternative()
        // mengembalikan 90.000
        ReceiptParser.Extracted e =
                parse("Grand Total : 99.900\nQRIS 90.000\nTUNAI 100.000\nKEMBALI 10.000");
        assertThat(e.amount()).isEqualTo(90000L);
    }

    @Test
    void letterOPlaceholderSurvivesNormalization() {
        // "O.o" tanpa digit → normalize tidak mengubahnya; total tetap terbaca
        ReceiptParser.Extracted e = parse("Toko O.o\nTOTAL 5.000");
        assertThat(e.amount()).isEqualTo(5000L);
        assertThat(e.merchant()).isEqualTo("Toko O.o");
    }

    @Test
    void merchantCurrencyLineWithoutDigitsIsKept() {
        ReceiptParser.Extracted e = parse("Harga Rp\nWARUNG X\nTOTAL 5.000");
        assertThat(e.merchant()).isEqualTo("Harga Rp");
    }

    @Test
    void farFutureIsoDateRejected() {
        assertThat(parse("2999-12-31\nTOTAL 5.000").date()).isNull();
    }

    @Test
    void parseIntHelperSurvivesGarbageViaReflection() {
        assertThat(
                        (Integer)
                                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                                        ReceiptParser.class, "parseInt", "abc"))
                .isEqualTo(-1);
        assertThat(
                        (Integer)
                                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                                        ReceiptParser.class, "parseInt", "42"))
                .isEqualTo(42);
    }

    @Test
    void feeAtLeastHundredWithDiscountDisablesRateValidation() {
        // subtotal != null, fee >= 100, disc != 0 → operand ketiga rateValidated
        ReceiptParser.Extracted e =
                parse("Subtotal : 63.000\nPajak : 6.300\nDiskon : 5.000\nGrand Total : 70.000");
        assertThat(e.amount()).isEqualTo(70000L);
    }

    @Test
    void tenderedNetOnlyCannotTakeExpectedCorrection() {
        // payment null, tenderedNet beda 2 digit → loop 507 nihil → 511 short-circuit di
        // operand tenderedNet; tanpa rateInferred (subtotal null) total tetap 90.000
        ReceiptParser.Extracted e = parse("Grand Total : 90.000\nTUNAI 100.000\nKEMBALI 25.000");
        assertThat(e.amount()).isEqualTo(90000L);
    }

    @Test
    void confusableIdenticalNumbersAreFalseViaReflection() {
        assertThat(
                        (Boolean)
                                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                                        ReceiptParser.class, "confusable", 5000L, 5000L))
                .isFalse();
        assertThat(
                        (Boolean)
                                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                                        ReceiptParser.class, "digitSimilar", 5000L, 5000L))
                .isFalse();
    }
}
