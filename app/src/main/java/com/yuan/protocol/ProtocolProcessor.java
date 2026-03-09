package com.yuan.protocol;

import android.os.Looper;
import android.util.Log;

import com.yuan.lcmemulator.CharLcmView;

import java.util.Arrays;

/**
 * Processes reassembled LCD protocol commands.
 *
 * <p>Receives the command code and payload (already reassembled if fragmented)
 * from {@link UdpReceiver}. CMD_HEARTBEAT is not dispatched here — it is
 * handled directly by {@link LcdUdpServer}.
 */
public class ProtocolProcessor {

    /* Command codes — must match udp.h / protocol spec §7 */
    static final int CMD_LCD_INIT          = 0x01;
    static final int CMD_LCD_SETBACKLIGHT  = 0x02;  // fixed typo from SETBACKLGIHT
    static final int CMD_LCD_SETCONTRAST   = 0x03;
    static final int CMD_LCD_SETBRIGHTNESS = 0x04;
    static final int CMD_LCD_WRITEDATA     = 0x05;
    static final int CMD_LCD_SETCURSOR     = 0x06;
    static final int CMD_LCD_CUSTOMCHAR    = 0x07;
    static final int CMD_LCD_WRITECMD      = 0x08;
    static final int CMD_ECHO              = 0x09;
    static final int CMD_GET_VER_INFO      = 0x0A;
    static final int CMD_LCD_DE_INIT       = 0x0B;
    static final int CMD_HEARTBEAT         = 0x0C;
    static final int CMD_LCD_FULLFRAME     = 0x0D;
    static final int CMD_ENTER_BOOT        = 0x19;
    static final int CMD_ACK               = 0xFF;

    private static final String TAG = "LCDEM";

    private final CharLcmView mLcmEmView;
    private OnDeInitListener mDeInitListener;

    /* Dimensions set by CMD_LCD_INIT — used by FULLFRAME to know
     * screen size without including COL/ROW in every frame. */
    private int mCol = 20;
    private int mRow = 4;

    public interface OnDeInitListener {
        void onDeInit();
    }

    public ProtocolProcessor(CharLcmView view) {
        this.mLcmEmView = view;
    }

    public void setOnDeInitListener(OnDeInitListener listener) {
        this.mDeInitListener = listener;
    }

    private void runOnUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else if (mLcmEmView != null) {
            mLcmEmView.post(r);
        }
    }

    /**
     * Process a single command with its reassembled payload.
     *
     * <p>CMD_HEARTBEAT and CMD_ACK are NOT dispatched here; they are
     * handled at the transport layer ({@link UdpReceiver} / {@link LcdUdpServer}).
     *
     * @param cmd     command code
     * @param payload reassembled payload bytes (may be empty)
     */
    public void process(int cmd, byte[] payload) {

        if (payload == null) {
            payload = new byte[0];
        }

        switch (cmd) {

            case CMD_LCD_INIT: {
                if (payload.length < 2) return;
                final int col = payload[0] & 0xFF;
                final int row = payload[1] & 0xFF;
                mCol = col;
                mRow = row;
                Log.i(TAG, "CMD_LCD_INIT: " + col + "," + row);
                runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        mLcmEmView.setColRow(col, row);
                        mLcmEmView.clearScreen();
                    }
                });
                break;
            }

            case CMD_LCD_SETBACKLIGHT: {
                if (payload.length < 1) return;
                int value = payload[0] & 0xFF;
                Log.i(TAG, "CMD_LCD_SETBACKLIGHT: " + value);
                break;
            }

            case CMD_LCD_SETCONTRAST: {
                if (payload.length < 1) return;
                int value = payload[0] & 0xFF;
                Log.i(TAG, "CMD_LCD_SETCONTRAST: " + value);
                break;
            }

            case CMD_LCD_SETBRIGHTNESS: {
                if (payload.length < 1) return;
                int value = payload[0] & 0xFF;
                Log.i(TAG, "CMD_LCD_SETBRIGHTNESS: " + value);
                break;
            }

            case CMD_LCD_WRITEDATA: {
                if (payload.length < 2) return;
                int len = (payload[0] & 0xFF) | ((payload[1] & 0xFF) << 8);
                if (payload.length < 2 + len) return;
                byte[] strBytes = Arrays.copyOfRange(payload, 2, 2 + len);
                final String text;
                try {
                    text = new String(strBytes, "UTF-8");
                } catch (Exception e) {
                    Log.e(TAG, "UTF-8 decode failed", e);
                    return;
                }
                Log.i(TAG, "CMD_LCD_WRITEDATA: len=" + len + " text=\"" + text + "\"");
                runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        mLcmEmView.writeStr(text);
                    }
                });
                break;
            }

            case CMD_LCD_SETCURSOR: {
                if (payload.length < 2) return;
                final int x = payload[0] & 0xFF;
                final int y = payload[1] & 0xFF;
                Log.i(TAG, "CMD_LCD_SETCURSOR: x=" + x + " y=" + y);
                runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        mLcmEmView.setCursor(x, y);
                    }
                });
                break;
            }

            case CMD_LCD_CUSTOMCHAR: {
                if (payload.length < 9) return;
                final int index = payload[0] & 0xFF;
                byte[] font = Arrays.copyOfRange(payload, 1, 9);
                reverseFontBits(font);
                final byte[] finalFont = font;
                Log.i(TAG, "CMD_LCD_CUSTOMCHAR: index=" + index);
                runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        mLcmEmView.setCustomFont(index, finalFont);
                    }
                });
                break;
            }

            case CMD_LCD_WRITECMD: {
                if (payload.length < 1) return;
                int rawCmd = payload[0] & 0xFF;
                Log.i(TAG, "CMD_LCD_WRITECMD: 0x" + Integer.toHexString(rawCmd));
                break;
            }

            case CMD_ECHO: {
                /* CMD_ECHO: no-op on device side.
                 * The ACK is already sent by UdpReceiver if FLAG_ACK_REQ was set. */
                Log.i(TAG, "CMD_ECHO received");
                break;
            }

            case CMD_LCD_DE_INIT: {
                Log.i(TAG, "CMD_LCD_DE_INIT");
                if (mDeInitListener != null) {
                    mDeInitListener.onDeInit();
                }
                break;
            }

            case CMD_LCD_FULLFRAME: {
                /* Full-frame payload v2 (§13 lean):
                 * CONTRAST(1) + BACKLIGHT(1) + BRIGHTNESS(1) + CUSTOMCHAR_MASK(1)
                 * + [index(1)+font(8)]×N   (N = popcount(CUSTOMCHAR_MASK))
                 * + screen_data(COL×ROW)
                 *
                 * COL/ROW are NOT in the payload — they come from CMD_LCD_INIT.
                 * CUSTOMCHAR_MASK=0 means no custom char data follows, so the
                 * device skips font rebuild entirely on most frames.
                 */
                if (payload.length < 4) return;  /* minimum header */

                final int contrast   = payload[0] & 0xFF;
                final int backlight  = payload[1] & 0xFF;
                final int brightness = payload[2] & 0xFF;
                final int ccMask     = payload[3] & 0xFF;

                int pos = 4;

                /* Use dimensions from CMD_LCD_INIT */
                final int col = mCol;
                final int row = mRow;

                /* Parse custom characters (only present when ccMask != 0) */
                final int[][] customChars = new int[8][];
                final int[] customIndices = new int[8];
                int ccCount = 0;
                for (int i = 0; i < 8; i++) {
                    if ((ccMask & (1 << i)) != 0) {
                        if (pos + 9 > payload.length) return;  /* truncated */
                        customIndices[ccCount] = payload[pos] & 0xFF;
                        pos++;
                        byte[] font = new byte[8];
                        System.arraycopy(payload, pos, font, 0, 8);
                        reverseFontBits(font);
                        customChars[ccCount] = new int[8];
                        for (int j = 0; j < 8; j++)
                            customChars[ccCount][j] = font[j] & 0xFF;
                        pos += 8;
                        ccCount++;
                    }
                }

                /* Parse screen data */
                final int screenBytes = col * row;
                if (pos + screenBytes > payload.length) return;  /* truncated */

                /* Build per-row strings from raw bytes */
                final String[] lines = new String[row];
                for (int r = 0; r < row; r++) {
                    byte[] lineBytes = new byte[col];
                    System.arraycopy(payload, pos + r * col, lineBytes, 0, col);
                    try {
                        lines[r] = new String(lineBytes, "UTF-8");
                    } catch (Exception e) {
                        lines[r] = "";
                    }
                }

                final int finalCcCount = ccCount;
                final int[][] finalCustomChars = customChars;
                final int[] finalCustomIndices = customIndices;

                Log.i(TAG, "CMD_LCD_FULLFRAME: " + col + "x" + row
                        + " ccMask=0x" + Integer.toHexString(ccMask));

                runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        /* 1. Apply custom characters (only if mask != 0) */
                        for (int i = 0; i < finalCcCount; i++) {
                            byte[] fontBytes = new byte[8];
                            for (int j = 0; j < 8; j++)
                                fontBytes[j] = (byte) finalCustomChars[i][j];
                            mLcmEmView.setCustomFont(finalCustomIndices[i], fontBytes);
                        }

                        /* 2. Write screen content — overwrite each row in place */
                        for (int r = 0; r < row; r++) {
                            mLcmEmView.setCursor(0, r);
                            mLcmEmView.writeStr(lines[r]);
                        }

                        /* 3. Refresh display */
                        mLcmEmView.invalidate();
                    }
                });
                break;
            }

            case CMD_ENTER_BOOT: {
                Log.i(TAG, "CMD_ENTER_BOOT");
                break;
            }

            default:
                Log.w(TAG, "Unhandled command: 0x" + Integer.toHexString(cmd));
                break;
        }
    }

    /**
     * Reverse 5-bit font data (mirror low 5 bits).
     */
    private void reverseFontBits(byte[] font) {
        for (int i = 0; i < font.length; i++) {
            byte x = font[i];
            byte result = 0;
            for (int bit = 0; bit < 5; bit++) {
                if ((x & (1 << bit)) != 0) {
                    result |= 1 << (4 - bit);
                }
            }
            font[i] = result;
        }
    }
}
