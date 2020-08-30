package com.yuan.lcmemulator;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

import android.os.Bundle;
import android.app.Activity;
import android.util.Log;
import android.view.Menu;

public class MainActivity extends Activity {

    private boolean switcher = false;
    private CharLcmView mCharLcdView;
    private SocketServer ss;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        Log.d("LCDEM", "onCreate...");


        mCharLcdView = (CharLcmView) findViewById(R.id.CHAR_LCD_VIEW);
        mCharLcdView.setColRow(20, 4);
        //mCharLcdView.setLcdPanelColor(getResources().getColor(R.color.LcdPanelColor));
        //mCharLcdView.setNegetivePixelColor(getResources().getColor(R.color.NegetivePixelColor));
        //mCharLcdView.setPostivePixelColor(getResources().getColor(R.color.PostivePixelColor));
        //mCharLcdView.clearScreen();
        mCharLcdView.writeStr(getIpAddressString());
        //mCharLcdView.updateFullScreen();

        ss = new SocketServer(2400, mCharLcdView);
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
        ss = new SocketServer(2400, mCharLcdView);
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
