package com.yuan.protocol;

import android.os.Looper;
import android.util.Log;

import com.yuan.lcmemulator.CharLcmView;

import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;

public class ProtocolProcessor {

    private static final int CMD_LCD_INIT = 0x01;
    private static final int CMD_LCD_SETBACKLGIHT = 0x02;
    private static final int CMD_LCD_SETCONTRAST = 0x03;
    private static final int CMD_LCD_SETBRIGHTNESS = 0x04;
    private static final int CMD_LCD_WRITEDATA = 0x05;
    private static final int CMD_LCD_SETCURSOR = 0x06;
    private static final int CMD_LCD_CUSTOMCHAR = 0x07;
    private static final int CMD_LCD_WRITECMD = 0x08;
    private static final int CMD_LCD_DE_INIT = 0x0B;
    private static final int CMD_ENTER_BOOT = 0x19;

    private static final String TAG = "LCDEM";

    private final Socket mSocket;
    private final CharLcmView mLcmEmView;

    public ProtocolProcessor(CharLcmView view, Socket socket) {
        this.mLcmEmView = view;
        this.mSocket = socket;
    }

    /**
     * 保证在 UI 线程执行（兼容 Android 4.x）
     */
    private void runOnUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else if (mLcmEmView != null) {
            mLcmEmView.post(r);
        }
    }

    public void process(byte[] buf) throws IOException {

        if (buf == null || buf.length == 0) {
            return;
        }

        int cmd = buf[0] & 0xFF;

        switch (cmd) {

            case CMD_LCD_INIT: {
                if (buf.length < 3) return;

                final int col = buf[1] & 0xFF;
                final int row = buf[2] & 0xFF;

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

            case CMD_LCD_SETCONTRAST: {
                if (buf.length < 2) return;

                int value = buf[1] & 0xFF;
                Log.i(TAG, "CMD_LCD_SETCONTRAST: " + value);
                break;
            }

            case CMD_LCD_SETBRIGHTNESS: {
                if (buf.length < 2) return;

                int value = buf[1] & 0xFF;
                Log.i(TAG, "CMD_LCD_SETBRIGHTNESS: " + value);
                break;
            }

            case CMD_LCD_WRITEDATA: {
                if (buf.length < 2) return;

                int len = buf[1] & 0xFF;
                if (buf.length < 2 + len) return;

                byte[] strBytes = Arrays.copyOfRange(buf, 2, 2 + len);

                final String text;
                try {
                    text = new String(strBytes, "UTF-8");
                } catch (Exception e) {
                    Log.e(TAG, "UTF-8 decode failed", e);
                    return;
                }

                runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        mLcmEmView.writeStr(text);
                    }
                });
                break;
            }

            case CMD_LCD_SETCURSOR: {
                if (buf.length < 3) return;

                final int x = buf[1] & 0xFF;
                final int y = buf[2] & 0xFF;

                runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        mLcmEmView.setCursor(x, y);
                    }
                });
                break;
            }

            case CMD_LCD_CUSTOMCHAR: {
                if (buf.length < 10) return;

                final int index = buf[1] & 0xFF;

                byte[] font = Arrays.copyOfRange(buf, 2, 10);

                reverseFontBits(font);

                final byte[] finalFont = font;

                runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        mLcmEmView.setCustomFont(index, finalFont);
                    }
                });
                break;
            }

            case CMD_LCD_DE_INIT: {
                if (mSocket != null && !mSocket.isClosed()) {
                    mSocket.close();
                }
                break;
            }

            case CMD_ENTER_BOOT:
            case CMD_LCD_SETBACKLGIHT:
            case CMD_LCD_WRITECMD:
            default:
                Log.w(TAG, "Unhandled command: " + cmd);
                break;
        }
    }

    /**
     * 反转 5bit 字模（高低位翻转）
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