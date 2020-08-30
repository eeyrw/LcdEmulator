package com.yuan.lcmemulator;

import java.io.Serializable;
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
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Toast;

public class MainActivity extends Activity {

    private boolean switcher = false;
    private LcmEmulatorView mLcmEmulatorView;
    private SocketServer ss;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        Log.d("LCDEM", "onCreate...");


        mLcmEmulatorView = (LcmEmulatorView) findViewById(R.id.LcmEmuMain);
        mLcmEmulatorView.setColRow(20, 4);
        mLcmEmulatorView.setLcdPanelColor(getResources().getColor(R.color.LcdPanelColor));
        mLcmEmulatorView.setNegetivePixelColor(getResources().getColor(R.color.NegetivePixelColor));
        mLcmEmulatorView.setPostivePixelColor(getResources().getColor(R.color.PostivePixelColor));
        mLcmEmulatorView.clearScreen();
        mLcmEmulatorView.writeStr(getIpAddressString());
        mLcmEmulatorView.updateFullScreen();

        ss = new SocketServer(2400, mLcmEmulatorView);
        //savedInstanceState.putParcelable("mLcmEmulatorView", (Parcelable) mLcmEmulatorView);
        //savedInstanceState.putParcelable("ss", (Parcelable) ss);

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
        Log.d("LCDEM", "onPause...");
        ss.Close();

    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d("LCDEM", "onResume...");
        ss = new SocketServer(2400, mLcmEmulatorView);
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


}
