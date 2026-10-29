package com.fastwork.expense.observability;

import java.time.Duration;
import java.util.Map;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import net.logstash.logback.marker.Markers;

/**
 * Stop-watch untuk method publik service layer (monotonik {@code System.nanoTime()}). Deviasi
 * ambang ({@code app.observability.slow-threshold}, default 500 ms) → WARN; operasi normal → DEBUG
 * (senyap di level default). Eksepsi TIDAK di-log di sini agar stacktrace tidak dobel — boundary
 * penanganan ({@code GlobalExceptionHandler}) sudah mencatatnya pada level ERROR. Catatan: durasi
 * service != durasi SQL; timing per-kueri JDBC perlu instrumentasi terpisah. Tidak ada
 * argumen/return value yang di-log.
 */
@Aspect
@Component
@ConditionalOnProperty(
        prefix = "app.observability",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class LoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(LoggingAspect.class);

    private final ObservabilityProperties properties;

    @Around("execution(public * com.fastwork.expense..*Service.*(..))")
    public Object measure(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedAt = System.nanoTime();
        String operation =
                joinPoint.getSignature().getDeclaringType().getSimpleName()
                        + "."
                        + joinPoint.getSignature().getName();
        try {
            Object result = joinPoint.proceed();
            long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            if (durationMs >= properties.getSlowThreshold().toMillis()) {
                log.warn(
                        Markers.appendEntries(
                                Map.of(
                                        "operation",
                                        operation,
                                        "durationMs",
                                        durationMs,
                                        "slow",
                                        true,
                                        "outcome",
                                        "success")),
                        "Lambat: {} selesai dalam {} ms",
                        operation,
                        durationMs);
            } else {
                log.debug(
                        Markers.appendEntries(
                                Map.of(
                                        "operation", operation,
                                        "durationMs", durationMs,
                                        "outcome", "success")),
                        "Selesai: {} ({} ms)",
                        operation,
                        durationMs);
            }
            return result;
        } catch (Throwable ex) {
            long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            log.debug(
                    Markers.appendEntries(
                            Map.of(
                                    "operation",
                                    operation,
                                    "durationMs",
                                    durationMs,
                                    "outcome",
                                    "error",
                                    "exception",
                                    ex.getClass().getSimpleName())),
                    "Gagal: {} setelah {} ms ({})",
                    operation,
                    durationMs,
                    ex.getClass().getSimpleName());
            throw ex;
        }
    }
}
