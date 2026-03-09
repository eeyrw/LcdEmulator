package com.yuan.protocol;

/**
 * CRC-16-CCITT (POLY=0x1021, INIT=0xFFFF)
 */
public final class Crc16Ccitt {

    private static final int POLY = 0x1021;
    private static final int INIT = 0xFFFF;

    private Crc16Ccitt() {}

    /**
     * Calculate CRC-16-CCITT over the given byte range.
     *
     * @param data   source buffer
     * @param offset start offset (inclusive)
     * @param length number of bytes to process
     * @return 16-bit CRC value
     */
    public static int calculate(byte[] data, int offset, int length) {
        int crc = INIT;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ POLY;
                } else {
                    crc = crc << 1;
                }
            }
            crc &= 0xFFFF;
        }
        return crc;
    }
}
