package com.yuan.protocol;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;

import com.yuan.lcdemulatorview.LcmEmulatorView;

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

	private LcmEmulatorView mLcmEmView;

	public ProtocolProcessor(LcmEmulatorView mLcmEmView) {
		this.mLcmEmView = mLcmEmView;
	}

	public void Process(byte[] Buf) {
		int i;

		switch (Buf[0]) {
		case CMD_LCD_INIT:

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
			// for(i=0; i<Buf[1]; i++)
			// {
			// lcd_putchar(Buf[2+i]);
			//
			// }
			byte[] str = new byte[Buf[1]];

			System.arraycopy(Buf, 2, str, 0, str.length);

			try {
				String srt2 = new String(str, "UTF-8");
				mLcmEmView.writeStr(srt2);
			} catch (UnsupportedEncodingException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			break;

		case CMD_LCD_SETCURSOR:

			// set_cursor(Buf[1],Buf[2]);
			mLcmEmView.setCursor(Buf[1], Buf[2]);

			break;

		case CMD_LCD_CUSTOMCHAR:

			// if(Is_Daul)
			// {
			// CurrentPanel=1;
			// lcd_write_cmd(0x40 | 8 * Buf[1]);
			//
			// for(i=0; i<8; i++)
			// lcd_putchar(Buf[2+i]);
			// }
			// CurrentPanel=0;
			// lcd_write_cmd(0x40 | 8 * Buf[1]);
			//
			// for(i=0; i<8; i++)
			// lcd_putchar(Buf[2+i]);
			mLcmEmView.setCustomFont(Buf[1], Arrays.copyOfRange(Buf, 2, 2+8));

			break;

		case CMD_LCD_WRITECMD:

			break;

		case CMD_ENTER_BOOT:

		default:
			break;

		}
	}

}
