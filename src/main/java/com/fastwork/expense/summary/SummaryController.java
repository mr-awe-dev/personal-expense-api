package com.fastwork.expense.summary;

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
@RequestMapping("/api/v1/summary")
@Tag(
        name = "Summary",
        description = "Ringkasan periode: KPI, per kategori/sumber, budget, tren, tabel")
public class SummaryController {

    private final CurrentUserService currentUser;
    private final SummaryService summaryService;

    @GetMapping
    @Operation(
            summary =
                    "KPI (income/expense/net/savings rate), per sumber & kategori dengan persentase"
                            + " (largest remainder), budget-vs-aktual, dan tren harian")
    public SummaryResponse summary(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        User user = currentUser.get();
        return summaryService.summary(user.getId(), user.getTimezone(), from, to);
    }

    @GetMapping("/table")
    @Operation(
            summary =
                    "Tabel summary dikelompokkan per kategori/sumber (subtotal ber-budget &"
                            + " selisih; grand tersendiri)")
    public SummaryTableResponse table(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Integer limit) {
        User user = currentUser.get();
        return summaryService.table(user.getId(), user.getTimezone(), from, to, limit);
    }
}
