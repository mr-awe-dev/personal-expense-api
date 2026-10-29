package com.fastwork.expense.today;

/** Status kartu Sisa Hari Ini: Aman < 80%, Waspada 80-100%, Melebihi > 100%. */
public enum TodayStatus {
    AMAN,
    WASPADA,
    MELEBIHI;

    private static final double APPROACHING = 0.8d;
    private static final double DOUBLE = 2.0d;

    public static TodayStatus of(long dailyLimit, long spent) {
        double usage = usageRatio(dailyLimit, spent);
        if (usage < APPROACHING) {
            return AMAN;
        }
        if (usage <= 1.0d) {
            return WASPADA;
        }
        return MELEBIHI;
    }

    static double usageRatio(long dailyLimit, long spent) {
        if (spent <= 0) {
            return 0.0d;
        }
        if (dailyLimit <= 0) {
            return DOUBLE;
        }
        return (double) spent / (double) dailyLimit;
    }
}
