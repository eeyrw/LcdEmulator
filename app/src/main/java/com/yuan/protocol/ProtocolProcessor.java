package com.yuan.protocol;

import android.util.Log;

import com.yuan.lcmemulator.CharLcmView;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
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

    private CharLcmView mLcmEmView;

    public ProtocolProcessor(CharLcmView mLcmEmView, Socket socket) {
        this.mLcmEmView = mLcmEmView;
        this.mSocket = socket;
    }

    private void runOnUi(Runnable r) {
        mLcmEmView.post(r);
    }

    public void Process(byte[] Buf) throws IOException {

        switch (Buf[0]) {
            case CMD_LCD_INIT:
                Log.i(TAG, "CMD_LCD_INIT: " + Buf[1] + "," + Buf[2]);
                int col = Buf[1];
                int row = Buf[2];
                runOnUi(() -> {
                    mLcmEmView.setColRow(col, row);
                    mLcmEmView.clearScreen();
                });
                // lcd_init(Buf[1],Buf[2]);


                break;

            case CMD_LCD_SETBACKLGIHT:

                break;

            case CMD_LCD_SETCONTRAST:

                Log.i(TAG, "CMD_LCD_SETCONTRAST:" + Buf[1]);

                break;

            case CMD_LCD_SETBRIGHTNESS:

                Log.i(TAG, "CMD_LCD_SETBRIGHTNESS:" + Buf[1]);

                break;

            case CMD_LCD_WRITEDATA:
                byte[] str = new byte[Buf[1]];

                System.arraycopy(Buf, 2, str, 0, str.length);

                try {
                    String srt2 = new String(str, "UTF-8");
                    String text = srt2;
                    runOnUi(() -> {
                        mLcmEmView.writeStr(text);
                    });
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }

                break;

            case CMD_LCD_SETCURSOR:
                int xx = Buf[1];
                int y = Buf[2];
                runOnUi(() -> mLcmEmView.setCursor(xx, y));
                break;

            case CMD_LCD_CUSTOMCHAR:

                byte[] font = Arrays.copyOfRange(Buf, 2, 2 + 8);
                // Reverse bit
                for (int i = 0; i < font.length; i++) {
                    byte x = font[i];
                    byte b = 0;

                    for (int bit = 4; bit >= 0; bit--) {
                        if ((x & 1 << bit) != 0)
                            b |= 1 << (4 - bit);

                    }
                    font[i] = b;
                }
                byte[] fontCopy = font.clone();
                int index = Buf[1];
                runOnUi(() -> {
                    mLcmEmView.setCustomFont(index, fontCopy);
                });
                break;

            case CMD_LCD_WRITECMD:

                break;

            case CMD_ENTER_BOOT:
                break;
            case CMD_LCD_DE_INIT:
                mSocket.close();
                break;

            default:
                break;

        }
    }

}
