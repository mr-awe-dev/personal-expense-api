package com.fastwork.expense.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class UuidV7Test {

    private static final long TIMESTAMP_MASK = 0xFFFFFFFFFFFFL;

    @Test
    void generatesRfc9562Version7() {
        UUID uuid = UuidV7.random();

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
    }

    @Test
    void embedsCurrentTimestampInHighBits() {
        UUID uuid = UuidV7.random();

        long unixMillis = (uuid.getMostSignificantBits() >>> 16) & TIMESTAMP_MASK;

        assertThat(unixMillis).isCloseTo(System.currentTimeMillis(), within(5_000L));
    }

    @Test
    void producesUniqueValues() {
        var generated = IntStream.range(0, 50_000).mapToObj(i -> UuidV7.random()).toList();

        assertThat(generated).doesNotHaveDuplicates();
    }
}
