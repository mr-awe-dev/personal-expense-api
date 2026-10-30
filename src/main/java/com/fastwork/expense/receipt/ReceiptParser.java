package com.fastwork.expense.receipt;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse heuristik teks OCR struk menjadi nominal, tanggal, dan nama merchant. Murni (tanpa I/O)
 * agar mudah di-unit-test. Format angka mengikuti struk Indonesia (titik = ribuan, koma = desimal)
 * dengan toleransi format Inggris.
 *
 * <p>Pemilihan nominal: tiap baris diklasifikasi (grand total, total, subtotal, diskon, pajak,
 * pembayaran, tunai, kembalian). Nominal dipilih dari baris total, lalu divalidasi silang dengan
 * bukti lain (subtotal + pajak - diskon, nominal QRIS/debit, tunai - kembalian).
 */
final class ReceiptParser {

    record Extracted(Long amount, LocalDate date, String merchant, int confidence) {}

    static final long MAX_AMOUNT = 999_999_999_999L;

    private enum Kind {
        NONE(true),
        IGNORE(true),
        CHANGE(true),
        SUBTOTAL(false),
        GRAND(false),
        DISCOUNT(true),
        FEE(true),
        PAYMENT(true),
        TENDERED(true),
        STRICT(false),
        WEAK(false);

        /** true = hanya angka berformat ribuan / berawalan Rp yang dianggap nominal. */
        final boolean strongOnly;

        Kind(boolean strongOnly) {
            this.strongOnly = strongOnly;
        }
    }

    private static final class Acc {
        Long grand;
        Long weak;
        Long subtotal;
        Long payment;
        Long tendered;
        Long change;
        Long fallback;

        /** Rentang nominal total yang digitnya terpotong OCR ("191,47" -> 191470..191479). */
        Long partialLo;

        Long partialHi;
        long fee;
        long disc;
        final List<Long> strict = new ArrayList<>();

        void add(Kind kind, long v) {
            switch (kind) {
                case GRAND -> grand = v;
                case STRICT -> strict.add(v);
                case WEAK -> weak = v;
                case SUBTOTAL -> subtotal = v;
                case FEE -> fee += v;
                case DISCOUNT -> disc += v;
                case PAYMENT -> payment = v;
                case TENDERED -> tendered = tendered == null ? v : Math.max(tendered, v);
                case CHANGE -> change = v;
                default -> {}
            }
        }
    }

    private static final int CI = Pattern.CASE_INSENSITIVE;

    /** "total" toleran salah baca OCR: T0TAL, TOTAI, TOTA1, TOTA|. */
    private static final String TOTAL = "t[o0]t[a4][l1i|!]";

    private static final Pattern TOTAL_WORD =
            Pattern.compile("(?<![a-z])" + TOTAL + "(?![a-z])", CI);
    private static final Pattern SUBTOTAL =
            Pattern.compile("(?<![a-z])sub\\s*[-.]?\\s*" + TOTAL, CI);
    private static final Pattern GRAND =
            Pattern.compile(
                    "grand\\s*"
                            + TOTAL
                            + "|(?<![a-z])"
                            + TOTAL
                            + "\\s*(bayar|pembayaran|tagihan|akhir|harus|amount|due)(?![a-z])"
                            + "|(harus|jumlah)\\s*(di)?bayar|amount\\s*due",
                    CI);
    private static final Pattern WEAK_WORD =
            Pattern.compile("(?<![a-z])(jumlah|net{1,2}o?|amount)(?![a-z])", CI);

    /** Kata yang membuat baris "total" tetap sah walau memuat ppn/diskon: "Total (Incl. PPN)". */
    private static final Pattern QUALIFIED =
            Pattern.compile(
                    "(?<![a-z])(incl\\w*|inc|termasuk|setelah|sesudah|after|dgn|dengan)(?![a-z])",
                    CI);

    private static final Pattern CHANGE =
            Pattern.compile("(?<![a-z])(k[eo][mn]?bali\\w*|change)(?![a-z])", CI);
    private static final Pattern IGNORE =
            Pattern.compile("(?<![a-z])(saldo|balance|serah|voucher|poin)|hemat", CI);
    private static final Pattern DISCOUNT =
            Pattern.compile("(?<![a-z])(diskon|discount|disc(?![a-z])|promo|potongan)", CI);
    private static final Pattern FEE =
            Pattern.compile(
                    "(?<![a-z])(pb1|pbl?i|pb2|ppn|pajak|service|servis|charge(?![a-z])|tax(?![a-z])|vat(?![a-z])|administrasi)",
                    CI);
    private static final Pattern PAYMENT =
            Pattern.compile(
                    "(?<![a-z])(qris|[aou]ris|debit|credit|ovo|gopay|go-?pay|shopee?pay|dana|linkaja|e-?money|transfer)(?![a-z])",
                    CI);
    private static final Pattern TENDERED =
            Pattern.compile(
                    "(?<![a-z])(tunai|tuna[l1!|]|tuna(?=[\\s:;=.,]*(?:rp)?[\\s\\d.,]+$)"
                            + "|cash|uang|bayar|dibayar|paid|payment|pembayaran)(?![a-z])",
                    CI);

    /** Nominal total yang digit terakhirnya hilang/terbaca simbol: "191,47", "191,4/5". */
    private static final Pattern PARTIAL =
            Pattern.compile("(?<![\\dA-Za-z])(\\d{1,3})[.,](\\d{1,2})(?![\\dA-Za-z%])");

    private static final Pattern PAREN_TAX =
            Pattern.compile("\\([^)]*(ppn|pajak|tax|vat|pb1|pb2|service)[^)]*\\)", CI);
    private static final Pattern SECTION_KEYWORD =
            Pattern.compile(
                    "(rincian|detail|keterangan|item|qty|telp|tlp|^hp|^no\\.?\\s*struk|fax|wc:|tunai\\b.*\\()",
                    CI);
    private static final Pattern MERCHANT_SKIP =
            Pattern.compile(
                    "(?<![a-z])(total|jumlah|net{1,2}o?|amount|kembali|change|diskon|discount|promo|ppn|vat|tax|saldo|balance|serah)",
                    CI);
    private static final Pattern CURRENCY_MARK =
            Pattern.compile("(?<![a-z])(rp|idr|rub)(?![a-z])", CI);
    private static final Pattern TOKEN =
            Pattern.compile(
                    "(?<![\\dA-Za-z])(\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,2})?|\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?)(?![\\dA-Za-z%])");
    private static final Pattern THOUSANDS =
            Pattern.compile("\\d{1,3}(?:[.,]\\d{3})+(?:[.,]\\d{1,2})?");

    /** Rangkaian angka yang huruf O-nya salah baca dari 0 ("5O.OOO"). */
    private static final Pattern OCR_NUM =
            Pattern.compile("(?<![A-Za-z])[\\dOo][\\dOo.,]*[\\dOo](?![A-Za-z])");

    private static final Pattern TIME = Pattern.compile("\\d{1,2}:\\d{2}");
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern NUM_DATE =
            Pattern.compile("(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{2,4})");
    private static final Pattern NAME_DATE =
            Pattern.compile("(\\d{1,2})\\s+([A-Za-z]{3,9})\\.?\\s+(\\d{4})");

    private static final Map<String, Integer> MONTHS = months();

    private ReceiptParser() {}

    private static Map<String, Integer> months() {
        Map<String, Integer> map = new HashMap<>();
        String[][] names = {
            {"jan", "january"}, {"feb", "february"}, {"mar", "march"}, {"apr", "april"},
            {"mei", "may"}, {"jun", "june"}, {"jul", "july"}, {"agu", "august"},
            {"sep", "september"}, {"okt", "october"}, {"nov", "november"}, {"des", "december"},
        };
        for (int i = 0; i < names.length; i++) {
            for (String alias : names[i]) {
                map.put(alias, i + 1);
            }
        }
        map.put("aug", 8);
        map.put("agt", 8);
        map.put("ags", 8);
        map.put("sept", 9);
        map.put("oct", 10);
        map.put("dec", 12);
        return Map.copyOf(map);
    }

    static Extracted parse(String ocrText) {
        if (ocrText == null || ocrText.isBlank()) {
            return new Extracted(null, null, null, 0);
        }
        List<String> lines = new ArrayList<>();
        for (String raw : ocrText.split("\\R")) {
            String line = raw.trim();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            return new Extracted(null, null, null, 0);
        }

        Acc acc = new Acc();
        Kind pendingKind = Kind.NONE;
        int pendingLeft = 0;

        for (String raw : lines) {
            String t = normalize(raw);
            boolean carry = pendingLeft > 0;
            if (carry) {
                pendingLeft--;
            }
            Kind kind = classify(t);

            if (kind == Kind.NONE) {
                boolean consumed = false;
                // label di baris sebelumnya, angka di baris ini ("GRAND TOTAL" / "Rp" /
                // "55.000")
                if (carry && valueOnly(t) && !dateish(t)) {
                    Long v = lineValue(t, pendingKind.strongOnly);
                    if (v != null) {
                        acc.add(pendingKind, v);
                        pendingLeft = 0;
                        consumed = true;
                    }
                }
                if (!consumed) {
                    Long f = lineValue(t, true);
                    if (f != null) {
                        acc.fallback = max(acc.fallback, f);
                    }
                }
                continue;
            }

            pendingLeft = 0;
            boolean totalLike = kind == Kind.GRAND || kind == Kind.STRICT || kind == Kind.WEAK;
            String valueText = totalLike ? PAREN_TAX.matcher(t).replaceAll(" ") : t;
            Long v = lineValue(valueText, kind.strongOnly);
            if (v != null) {
                acc.add(kind, v);
                continue;
            }
            if (kind == Kind.GRAND || kind == Kind.STRICT) {
                Matcher pm = PARTIAL.matcher(valueText);
                while (pm.find()) {
                    long scale = pm.group(2).length() == 1 ? 100L : 10L;
                    acc.partialLo = Long.parseLong(pm.group(1) + pm.group(2)) * scale;
                    acc.partialHi = acc.partialLo + scale - 1;
                }
            }
            boolean hasNumber = TOKEN.matcher(t).find();
            boolean carryable =
                    kind == Kind.GRAND
                            || kind == Kind.STRICT
                            || kind == Kind.SUBTOTAL
                            || kind == Kind.PAYMENT
                            || (!hasNumber
                                    && (kind == Kind.CHANGE
                                            || kind == Kind.FEE
                                            || kind == Kind.DISCOUNT
                                            || kind == Kind.TENDERED));
            if (carryable) {
                pendingKind = kind;
                pendingLeft = 3;
            }
        }

        Long expected = null;
        if (acc.subtotal != null) {
            long e = acc.subtotal + acc.fee - acc.disc;
            if (e >= 1 && e <= MAX_AMOUNT) {
                expected = e;
            }
        }
        Long tenderedNet = null;
        if (acc.tendered != null && acc.change != null && acc.tendered > acc.change) {
            tenderedNet = acc.tendered - acc.change;
        }

        Long amount = null;
        int amountConfidence = 0;
        boolean lowEvidence = false;

        Long primary = acc.grand;
        if (primary == null && !acc.strict.isEmpty()) {
            primary =
                    expected != null && acc.strict.contains(expected)
                            ? expected
                            : acc.strict.get(acc.strict.size() - 1);
        }
        int base = 40;
        if (primary == null && acc.weak != null) {
            primary = acc.weak;
            base = 30;
        }

        // Total terbaca tapi digit terakhirnya hilang ("191,47"): lengkapi dari bukti
        // yang jatuh di
        // rentang. Tanpa bukti yang cocok, rentang hanya dipakai bila tidak ada bukti
        // angka lain
        // (QRIS, tunai-kembalian, subtotal+pajak) yang bisa dipercaya; hasilnya
        // berkeyakinan rendah.
        boolean partialUsed = false;
        Long partialPick = null;
        if (primary == null && acc.partialLo != null) {
            int hits = 0;
            Long[] evidence = {
                expected, tenderedNet, acc.payment, taxDerived(acc, acc.partialLo, acc.partialHi)
            };
            for (Long c : evidence) {
                if (c != null
                        && c >= acc.partialLo
                        && c <= acc.partialHi
                        && !c.equals(partialPick)) {
                    partialPick = c;
                    hits++;
                }
            }
            if (hits != 1) {
                partialPick = null;
            }
            boolean otherEvidence = expected != null || tenderedNet != null || acc.payment != null;
            partialUsed = partialPick != null || !otherEvidence;
        }

        if (primary != null) {
            amount = primary;
            amountConfidence = base;
            // subtotal + pajak bertarif baku (mis. PB1 10%) mengalahkan total yang salah
            // baca satu
            // digit, bahkan bila baris QRIS/tunai ikut salah baca sama.
            Long byRate = rateValidated(acc);
            if (byRate != null && !byRate.equals(primary) && confusable(primary, byRate)) {
                amount = byRate;
                amountConfidence += 10;
            } else if (count(primary, acc.payment, tenderedNet, expected) >= 1) {
                amountConfidence += 10;
            } else {
                Long alt = alternative(primary, acc.payment, tenderedNet, expected);
                if (alt == null && acc.fee == 0 && acc.disc == 0) {
                    alt = rateInferred(primary, acc.subtotal);
                }
                if (alt != null) {
                    amount = alt;
                    amountConfidence += 10;
                }
            }
        } else if (partialUsed) {
            if (partialPick != null) {
                amount = partialPick;
                amountConfidence = 50;
            } else {
                amount = acc.partialLo + (acc.partialHi - acc.partialLo + 1) / 2;
                amountConfidence = 25;
                lowEvidence = true;
            }
        } else if (acc.payment != null) {
            amount = acc.payment;
            amountConfidence = 35;
            if (count(amount, tenderedNet, expected) >= 1) {
                amountConfidence += 10;
            }
        } else if (tenderedNet != null) {
            amount = tenderedNet;
            amountConfidence = 35;
            if (count(amount, expected) >= 1) {
                amountConfidence += 10;
            }
        } else if (expected != null) {
            amount = expected;
            amountConfidence = 30;
        } else if (acc.tendered != null) {
            amount = acc.tendered;
            amountConfidence = 20;
            lowEvidence = true;
        } else if (acc.fallback != null) {
            amount = acc.fallback;
            amountConfidence = 15;
            lowEvidence = true;
        }

        LocalDate date = findDate(lines);
        String merchant = findMerchant(lines);

        int confidence = 0;
        if (amount != null) {
            confidence += amountConfidence;
        }
        if (date != null) {
            confidence += 20;
        }
        if (merchant != null) {
            confidence += 20;
        }
        if (lines.size() >= 5) {
            confidence += 20;
        }
        if (amount != null && lowEvidence) {
            confidence = Math.min(confidence, 55);
        }

        return new Extracted(amount, date, merchant, Math.min(confidence, 100));
    }

    /**
     * Rapikan artefak OCR: "Rp55.000,-", "5O.OOO", "55. 000" menjadi "Rp 55.000", "50.000",
     * "55.000".
     */
    private static String normalize(String raw) {
        String s = raw.replaceAll("(?<=\\d)[.,]\\s*-+(?!\\d)", "");
        s = s.replaceAll("(?i)(?<![a-z])(rp|idr)\\.?\\s*(?=\\d)", "$1 ");
        Matcher m = OCR_NUM.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String g = m.group();
            boolean hasDigit = g.chars().anyMatch(Character::isDigit);
            m.appendReplacement(
                    sb,
                    Matcher.quoteReplacement(hasDigit ? g.replace('O', '0').replace('o', '0') : g));
        }
        m.appendTail(sb);
        s = sb.toString();
        s = s.replaceAll("(?<=\\d[.,])\\s+(?=\\d{3}(?!\\d))", "");
        s = s.replaceAll("(?<=\\d)\\s+(?=[.,]\\d{3}(?!\\d))", "");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static Kind classify(String t) {
        boolean total = TOTAL_WORD.matcher(t).find();
        boolean qualified = total && QUALIFIED.matcher(t).find();
        if (CHANGE.matcher(t).find()) {
            return Kind.CHANGE;
        }
        if (!qualified && IGNORE.matcher(t).find()) {
            return Kind.IGNORE;
        }
        if (SUBTOTAL.matcher(t).find()) {
            return Kind.SUBTOTAL;
        }
        if (GRAND.matcher(t).find()) {
            return Kind.GRAND;
        }
        if (!qualified && DISCOUNT.matcher(t).find()) {
            return Kind.DISCOUNT;
        }
        if (!qualified && FEE.matcher(t).find()) {
            return Kind.FEE;
        }
        if (PAYMENT.matcher(t).find()) {
            return Kind.PAYMENT;
        }
        if (TENDERED.matcher(t).find()) {
            return Kind.TENDERED;
        }
        if (total) {
            return Kind.STRICT;
        }
        if (WEAK_WORD.matcher(t).find()) {
            return Kind.WEAK;
        }
        return Kind.NONE;
    }

    /**
     * Nominal paling kanan di baris (nominal struk rata kanan). Angka kecil polos seperti jumlah
     * item ("TOTAL 3 ITEM 55.000") dan persen ("PPN 10%") diabaikan.
     */
    private static Long lineValue(String t, boolean strongOnly) {
        boolean currency = CURRENCY_MARK.matcher(t).find();
        Long result = null;
        Matcher m = TOKEN.matcher(t);
        while (m.find()) {
            String token = m.group(1);
            Long v = toRupiah(token);
            if (v == null) {
                continue;
            }
            boolean strong = THOUSANDS.matcher(token).matches() || (currency && v >= 100);
            if (strong || (!strongOnly && v >= 1000)) {
                result = v;
            }
        }
        return result;
    }

    /** Baris tanpa huruf selain penanda mata uang: "Rp", ":", "55.000", "Rp 55.000". */
    private static boolean valueOnly(String t) {
        return CURRENCY_MARK.matcher(t).replaceAll("").replaceAll("[^A-Za-z]", "").isEmpty();
    }

    private static boolean dateish(String t) {
        return ISO_DATE.matcher(t).find()
                || NUM_DATE.matcher(t).find()
                || NAME_DATE.matcher(t).find()
                || TIME.matcher(t).find();
    }

    private static int count(Long a, Long... evidence) {
        int n = 0;
        for (Long e : evidence) {
            if (e != null && e.equals(a)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Koreksi nominal total yang bertentangan dengan bukti lain: (1) dua bukti independen sepakat,
     * atau (2) nominal pembayaran / tunai-kembalian hanya beda satu digit (salah baca OCR), atau
     * (3) tanpa bukti pembayaran, subtotal+pajak-diskon beda satu digit.
     */
    private static Long alternative(long primary, Long payment, Long tenderedNet, Long expected) {
        for (Long a : new Long[] {payment, tenderedNet, expected}) {
            if (a != null
                    && a.longValue() != primary
                    && count(a, payment, tenderedNet, expected) >= 2) {
                return a;
            }
        }
        for (Long a : new Long[] {payment, tenderedNet}) {
            if (a != null && digitSimilar(primary, a)) {
                return a;
            }
        }
        if (payment == null
                && tenderedNet == null
                && expected != null
                && digitSimilar(primary, expected)) {
            return expected;
        }
        return null;
    }

    private static final double[] TAX_RATES = {0.05, 0.10, 0.11, 0.12, 0.15, 0.155, 0.1655};
    private static final double[] SINGLE_RATES = {0.05, 0.10, 0.11, 0.12, 0.15};

    /** Pasangan digit yang lazim tertukar OCR. */
    private static final String[] CONFUSABLE = {
        "39", "38", "08", "06", "09", "17", "56", "58", "68", "27", "49", "35", "69"
    };

    /**
     * true bila beda tepat satu digit DAN pasangan digitnya lazim salah baca OCR (3↔9, 0↔8, ...).
     */
    private static boolean confusable(long a, long b) {
        if (!digitSimilar(a, b)) {
            return false;
        }
        String x = Long.toString(a);
        String y = Long.toString(b);
        for (int i = 0; i < x.length(); i++) {
            char p = x.charAt(i);
            char q = y.charAt(i);
            if (p != q) {
                for (String c : CONFUSABLE) {
                    if ((c.charAt(0) == p && c.charAt(1) == q)
                            || (c.charAt(0) == q && c.charAt(1) == p)) {
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }

    /** subtotal + pajak, bila nominal pajak pas sebesar tarif baku x subtotal (tanpa diskon). */
    private static Long rateValidated(Acc acc) {
        if (acc.subtotal == null || acc.fee < 100 || acc.disc != 0) {
            return null;
        }
        for (double r : TAX_RATES) {
            if (Math.abs(acc.fee - Math.round(acc.subtotal * r)) <= 50) {
                return acc.subtotal + acc.fee;
            }
        }
        return null;
    }

    /**
     * Baris pajak tidak terbaca: bila tepat satu subtotal x (1 + tarif baku) hanya beda satu digit
     * "lazim tertukar" dari nominal terbaca, pakai nominal itu. Ambigu atau sudah cocok -> null.
     */
    private static Long rateInferred(long primary, Long subtotal) {
        if (subtotal == null) {
            return null;
        }
        Long found = null;
        for (double r : SINGLE_RATES) {
            long cand = Math.round(subtotal * (1 + r));
            if (cand == primary) {
                return null;
            }
            if (confusable(primary, cand)) {
                if (found != null && found != cand) {
                    return null;
                }
                found = cand;
            }
        }
        return found;
    }

    /**
     * Subtotal tidak terbaca tapi nominal pajak ada: subtotal = pajak / tarif baku, total =
     * subtotal + pajak. Hanya dipakai bila tepat satu tarif menghasilkan total di rentang [lo, hi].
     */
    private static Long taxDerived(Acc acc, long lo, long hi) {
        if (acc.fee < 100 || acc.disc != 0) {
            return null;
        }
        Long found = null;
        for (double r : SINGLE_RATES) {
            long total = Math.round(acc.fee / r) + acc.fee;
            if (total >= lo && total <= hi) {
                if (found != null && found != total) {
                    return null;
                }
                found = total;
            }
        }
        return found;
    }

    private static Long max(Long current, long candidate) {
        return current == null || candidate > current ? Long.valueOf(candidate) : current;
    }

    /**
     * true bila kedua angka sama panjang dan berbeda tepat satu digit (misread OCR khas: 3↔9, 0↔8).
     */
    private static boolean digitSimilar(long a, long b) {
        String x = Long.toString(a);
        String y = Long.toString(b);
        if (x.length() != y.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < x.length(); i++) {
            if (x.charAt(i) != y.charAt(i) && ++diff > 1) {
                return false;
            }
        }
        return diff == 1;
    }

    /** "1.234.567" → 1234567; "45.000,50" → 45000; "1,234.56" (en) → 1235; "45000" → 45000. */
    private static Long toRupiah(String token) {
        long value;
        try {
            if (Pattern.matches("\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,2})?", token)) {
                value = Long.parseLong(token.split(",")[0].replace(".", ""));
            } else if (Pattern.matches("\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?", token)) {
                value = Long.parseLong(token.split("\\.")[0].replace(",", ""));
            } else {
                double d = Double.parseDouble(token.replace(',', '.'));
                if (!Double.isFinite(d)) {
                    return null;
                }
                value = Math.round(d);
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        if (value < 1 || value > MAX_AMOUNT) {
            return null;
        }
        return Long.valueOf(value);
    }

    private static LocalDate findDate(List<String> lines) {
        LocalDate today = LocalDate.now();
        for (String line : lines) {
            Matcher iso = ISO_DATE.matcher(line);
            if (iso.find()) {
                LocalDate parsed =
                        safeDate(
                                parseInt(iso.group(1)),
                                parseInt(iso.group(2)),
                                parseInt(iso.group(3)));
                if (valid(parsed, today)) {
                    return parsed;
                }
            }
            Matcher num = NUM_DATE.matcher(line);
            if (num.find()) {
                int a = parseInt(num.group(1));
                int b = parseInt(num.group(2));
                int y = parseInt(num.group(3));
                if (y < 100) {
                    y += 2000;
                }
                int day = a;
                int month = b;
                if (month > 12 && day <= 12) {
                    month = day;
                    day = b;
                }
                LocalDate parsed = safeDate(y, month, day);
                if (valid(parsed, today)) {
                    return parsed;
                }
            }
            Matcher name = NAME_DATE.matcher(line);
            if (name.find()) {
                Integer month = MONTHS.get(name.group(2).toLowerCase());
                if (month != null) {
                    LocalDate parsed =
                            safeDate(parseInt(name.group(3)), month, parseInt(name.group(1)));
                    if (valid(parsed, today)) {
                        return parsed;
                    }
                }
            }
        }
        return null;
    }

    private static boolean valid(LocalDate date, LocalDate today) {
        return date != null
                && !date.isBefore(LocalDate.of(2000, 1, 1))
                && !date.isAfter(today.plusDays(1));
    }

    private static LocalDate safeDate(int year, int month, int day) {
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return null;
        }
        try {
            return LocalDate.of(year, month, day);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static int parseInt(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static String findMerchant(List<String> lines) {
        for (String line : lines) {
            String letters = line.replaceAll("[^A-Za-z]", "");
            if (letters.length() < 3) {
                continue;
            }
            if (line.replaceAll("[^0-9]", "").length() > letters.length() || dateish(line)) {
                continue;
            }
            if (MERCHANT_SKIP.matcher(line).find() || SECTION_KEYWORD.matcher(line).find()) {
                continue;
            }
            if (CURRENCY_MARK.matcher(line).find() && TOKEN.matcher(line).find()) {
                continue;
            }
            String cleaned = line.replaceAll("\\s+", " ").trim();
            if (cleaned.length() > 60) {
                cleaned = cleaned.substring(0, 60).trim();
            }
            return cleaned;
        }
        return null;
    }
}
