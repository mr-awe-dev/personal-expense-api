package com.fastwork.expense.today;

import java.time.LocalDate;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fastwork.expense.user.CurrentUserService;
import com.fastwork.expense.user.User;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/today")
@Tag(
        name = "Today",
        description = "Ringkasan hari ini: sisa batas harian & kategori ber-budget harian")
public class TodayController {

    private final CurrentUserService currentUser;
    private final TodayService todayService;

    @GetMapping
    @Operation(
            summary = "Total pengeluaran, sisa batas harian, status, dan rincian kategori harian")
    public TodayResponse today(@RequestParam(required = false) LocalDate date) {
        User user = currentUser.get();
        return todayService.today(user.getId(), user.getDailyLimit(), user.getTimezone(), date);
    }
}
