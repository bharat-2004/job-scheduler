package com.jobscheduler.common.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * RFC 9562 UUID version 7: 48-bit Unix-millisecond timestamp followed by random bits.
 * Time-ordered IDs make index inserts append at the end instead of landing on random pages.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate(Instant now) {
        long millis = now.toEpochMilli();
        long randA = RANDOM.nextInt(1 << 12);  // 12 bits
        long randB = RANDOM.nextLong();        // top 62 bits used

        long msb = (millis & 0xFFFF_FFFF_FFFFL) << 16  // 48-bit timestamp
                | 0x7000L                                // version 7
                | randA;
        long lsb = (randB & 0x3FFF_FFFF_FFFF_FFFFL)    // 62 random bits
                | 0x8000_0000_0000_0000L;               // IETF variant (10xx)
        return new UUID(msb, lsb);
    }
}
