package com.fastwork.expense.common;

import java.security.SecureRandom;
import java.util.UUID;

public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    public static UUID random() {
        byte[] bytes = new byte[16];
        byte[] rnd = new byte[10];
        RANDOM.nextBytes(rnd);

        long timestamp = System.currentTimeMillis();
        for (int i = 5; i >= 0; i--) {
            bytes[i] = (byte) (timestamp & 0xFF);
            timestamp >>= 8;
        }
        bytes[6] = (byte) (0x70 | (rnd[0] & 0x0F));
        bytes[7] = rnd[1];
        bytes[8] = (byte) (0x80 | (rnd[2] & 0x3F));
        System.arraycopy(rnd, 3, bytes, 9, 7);

        long msb = 0L;
        long lsb = 0L;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (bytes[i] & 0xFFL);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (bytes[i] & 0xFFL);
        }
        return new UUID(msb, lsb);
    }
}
