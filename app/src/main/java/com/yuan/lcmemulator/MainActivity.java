package com.yuan.lcmemulator;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Timer;
import java.util.TimerTask;

import com.yuan.lcdemulatorview.LcmEmulatorView;

import android.net.ConnectivityManager;
import android.net.NetworkInfo.State;
import android.os.Bundle;
import android.os.Debug;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Paint.Style;
import android.util.AttributeSet;
import android.view.Menu;
import android.view.View;
import android.widget.Toast;

public class MainActivity extends Activity {

	private boolean switcher = false;
	private LcmEmulatorView mLcmEmulatorView;
	private BroadCastNetStatus receiver;
	private  SocketServer ss;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Debug.startMethodTracing("test");
		setContentView(R.layout.activity_main);

		mLcmEmulatorView = (LcmEmulatorView) findViewById(R.id.LcmEmuMain);
		mLcmEmulatorView.setColRow(20, 4);
		mLcmEmulatorView.clearScreen();
		mLcmEmulatorView.writeStr(getIpAddressString());
		mLcmEmulatorView.updateFullScreen();

		
		ss = new SocketServer(2400, mLcmEmulatorView);
		// receiver = new BroadCastNetStatus();

		// IntentFilter filter = new IntentFilter();
		// filter.addAction("android.net.conn.CONNECTIVITY_CHANGE");

		// registerReceiver(receiver, filter);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	// onPause 方法中结束
	@Override
	protected void onPause() {
		super.onPause();
			//unregisterReceiver(receiver);
		// Debug.stopMethodTracing();

	}
	public static String getIpAddressString() {
		try {
			for (Enumeration<NetworkInterface> enNetI = NetworkInterface
					.getNetworkInterfaces(); enNetI.hasMoreElements(); ) {
				NetworkInterface netI = enNetI.nextElement();
				for (Enumeration<InetAddress> enumIpAddr = netI
						.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
					InetAddress inetAddress = enumIpAddr.nextElement();
					if (inetAddress instanceof Inet4Address && !inetAddress.isLoopbackAddress()) {
						return inetAddress.getHostAddress();
					}
				}
			}
		} catch (SocketException e) {
			e.printStackTrace();
		}
		return "";
	}

	public class BroadCastNetStatus extends BroadcastReceiver {
		State wifiState = null;
		State mobileState = null;

		@Override
		public void onReceive(Context context, Intent intent) {
			// TODO Auto-generated method stub
			// 获取手机的连接服务管理器，这里是连接管理器类
			ConnectivityManager cm = (ConnectivityManager) context
					.getSystemService(Context.CONNECTIVITY_SERVICE);
			wifiState = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI)
					.getState();
			//mobileState = cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE)
			//		.getState();

			if (wifiState != null && mobileState != null
					&& State.CONNECTED != wifiState
					&& State.CONNECTED == mobileState) {
				Toast.makeText(context, "手机网络连接成功！", Toast.LENGTH_SHORT).show();
			} else if (wifiState != null && mobileState != null
					&& State.CONNECTED == wifiState
					&& State.CONNECTED != mobileState) {
				Toast.makeText(context, "无线网络连接成功！", Toast.LENGTH_SHORT).show();
			} else if (wifiState != null && mobileState != null
					&& State.CONNECTED != wifiState
					&& State.CONNECTED != mobileState) {
				Toast.makeText(context, "手机没有任何网络...", Toast.LENGTH_SHORT)
						.show();
			}
		}

	}

}
