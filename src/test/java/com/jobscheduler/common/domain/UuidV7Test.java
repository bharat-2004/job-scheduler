package com.jobscheduler.common.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

    @Test
    void hasVersion7AndIetfVariant() {
        UUID id = UuidV7.generate(Instant.now());
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTheTimestampSoIdsSortByTime() {
        Instant t = Instant.parse("2026-11-21T10:00:00.123Z");
        UUID earlier = UuidV7.generate(t);
        UUID later = UuidV7.generate(t.plusMillis(1));
        assertThat(earlier.getMostSignificantBits() >>> 16).isEqualTo(t.toEpochMilli());
        assertThat(Long.compareUnsigned(earlier.getMostSignificantBits(), later.getMostSignificantBits())).isNegative();
    }
}
