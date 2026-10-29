package com.fastwork.expense.observability;

/**
 * Klasifikasi {@code monolith_module} untuk log terstruktur: segemen paket pertama di bawah {@code
 * com.fastwork.expense} (auth, transaction, summary, ...). Nilai tidak pernah berasal dari input
 * pengguna — hanya dari nama logger.
 */
public final class MonolithModules {

    private static final String ROOT_PACKAGE = "com.fastwork.expense.";

    private MonolithModules() {}

    public static String fromLogger(String loggerName) {
        if (loggerName == null || !loggerName.startsWith(ROOT_PACKAGE)) {
            return "platform";
        }
        String remainder = loggerName.substring(ROOT_PACKAGE.length());
        if (remainder.isBlank()) {
            return "platform";
        }
        int dot = remainder.indexOf('.');
        return dot < 0 ? "app" : remainder.substring(0, dot);
    }
}
