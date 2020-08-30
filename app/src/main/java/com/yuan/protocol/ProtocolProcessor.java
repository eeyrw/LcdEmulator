package com.yuan.protocol;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;

import com.yuan.lcmemulator.CharLcmView;

import android.util.Log;

public class ProtocolProcessor {

    private static final int CMD_LCD_INIT = 0x01;
    private static final int CMD_LCD_SETBACKLGIHT = 0x02;
    private static final int CMD_LCD_SETCONTRAST = 0x03;
    private static final int CMD_LCD_SETBRIGHTNESS = 0x04;
    private static final int CMD_LCD_WRITEDATA = 0x05;
    private static final int CMD_LCD_SETCURSOR = 0x06;
    private static final int CMD_LCD_CUSTOMCHAR = 0x07;
    private static final int CMD_LCD_WRITECMD = 0x08;
    private static final int CMD_ENTER_BOOT = 0x19;

    private static final String TAG = "LCDEM";

    private CharLcmView mLcmEmView;

    public ProtocolProcessor(CharLcmView mLcmEmView) {
        this.mLcmEmView = mLcmEmView;
    }

    public void Process(byte[] Buf) {

        switch (Buf[0]) {
            case CMD_LCD_INIT:
				Log.i(TAG, "CMD_LCD_INIT: " + Buf[1]+","+Buf[2]);

                // lcd_init(Buf[1],Buf[2]);
				mLcmEmView.setColRow(Buf[1],Buf[2]);
				mLcmEmView.clearScreen();
				mLcmEmView.reGenResoures();

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
                    mLcmEmView.writeStr(srt2);
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }

                break;

            case CMD_LCD_SETCURSOR:
                mLcmEmView.setCursor(Buf[1], Buf[2]);
                break;

            case CMD_LCD_CUSTOMCHAR:

                byte[] font = Arrays.copyOfRange(Buf, 2, 2 + 8);
                // Reverse bit
                for (int i = 0; i < font.length; i++) {
                    byte x = font[i];
                    byte b = 0;

                    while (x != 0) {
                        b <<= 1;
                        b |= (x & 1);
                        x >>= 1;
                    }
                    font[i] = b;
                }

                mLcmEmView.setCustomFont(Buf[1], font);
                break;

            case CMD_LCD_WRITECMD:

                break;

            case CMD_ENTER_BOOT:

            default:
                break;

        }
    }

}
