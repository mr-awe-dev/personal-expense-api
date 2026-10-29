package com.fastwork.expense.today;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TodayStatusTest {

    @Test
    void thresholdsFollowTheSpec() {
        assertEquals(TodayStatus.AMAN, TodayStatus.of(100000L, 0L));
        assertEquals(TodayStatus.AMAN, TodayStatus.of(100000L, 79999L));
        assertEquals(TodayStatus.WASPADA, TodayStatus.of(100000L, 80000L));
        assertEquals(TodayStatus.WASPADA, TodayStatus.of(100000L, 100000L));
        assertEquals(TodayStatus.MELEBIHI, TodayStatus.of(100000L, 100001L));
        assertEquals(TodayStatus.MELEBIHI, TodayStatus.of(100000L, 200000L));
    }

    @Test
    void zeroLimitAndNoSpendingIsAman() {
        assertEquals(TodayStatus.AMAN, TodayStatus.of(0L, 0L));
    }

    @Test
    void zeroLimitWithAnySpendingIsMelebihi() {
        assertEquals(TodayStatus.MELEBIHI, TodayStatus.of(0L, 1L));
        assertEquals(TodayStatus.MELEBIHI, TodayStatus.of(0L, 500000L));
    }
}
