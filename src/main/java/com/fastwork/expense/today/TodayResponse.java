package com.fastwork.expense.today;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Today")
public record TodayResponse(
        LocalDate date,
        Long dailyLimit,
        long spentToday,
        Long remaining,
        TodayStatus status,
        List<TodayCategory> categories) {}
