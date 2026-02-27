package com.yuan.lcmemulator;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.widget.Toast;

import androidx.annotation.ColorInt;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.jaredrummler.android.colorpicker.ColorPickerDialogListener;


public class MainActivity extends AppCompatActivity
        implements GestureDetector.OnGestureListener,
        ColorPickerDialogListener,
        ThemeManager.Listener {

    private static final String TAG = "LCDEM";
    private static final int DEFAULT_PORT = 2400;
    private static final int FLING_DISTANCE = 50;

    private CharLcmView lcdView;
    private IdleMessageAnimator idleMessageAnimator;
    private TcpServer tcpServer;
    private GestureDetector gestureDetector;
    private ThemeManager themeManager;
    private OsdController osdController;

    private int socketPort = DEFAULT_PORT;

    // ===========================
    // Lifecycle
    // ===========================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "onCreate");

        initViews();
        updateLcdSettings();
        initTheme();
        initOsd();
        initGesture();
        initNetwork();
        displayIdleMessage();
        FullScreenHelper.enterFullScreen(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        tcpServer.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
    }

    @Override
    protected void onStop() {
        super.onStop();
        tcpServer.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        themeManager.unregister(this);
    }

    // ===========================
    // Initialization
    // ===========================

    private void initViews() {
        lcdView = findViewById(R.id.CHAR_LCD_VIEW);
        idleMessageAnimator = new IdleMessageAnimator(lcdView);
    }

    private void displayIdleMessage() {
        String ip = NetworkUtils.getIpv4Address();
        String ipText = ip.isEmpty()
                ? "Fail to get IP address."
                : "WAITING FOR CLIENT\nIP:" + ip;

        idleMessageAnimator.start(ipText);
    }

    private void initTheme() {
        themeManager = new ThemeManager(this);
        themeManager.register(this);
    }

    private void initOsd() {
        osdController = new OsdController(
                this,
                findViewById(R.id.osdOverlay),
                themeManager.getRepository(),
                preset -> themeManager.selectPreset(preset.id)
        );
    }

    private void initGesture() {
        gestureDetector = new GestureDetector(this, this);
    }

    private void initNetwork() {
        socketPort = loadPortFromPrefs();
        tcpServer = new TcpServer(socketPort, lcdView);
        tcpServer.setConnectionListener(new TcpServer.ConnectionListener() {
            @Override
            public void onClientConnected() {
                runOnUiThread(() -> {
                    idleMessageAnimator.stop();   // 停止动画
                });
            }

            @Override
            public void onClientDisconnected() {
                runOnUiThread(() -> {
                    displayIdleMessage();
                });
            }
        });
    }

    // ===========================
    // Settings
    // ===========================

    private void updateLcdSettings() {
        if (lcdView == null) return;

        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(this);

        lcdView.setRoundRectPixel(
                prefs.getBoolean("prefIsRoundBorderPixel", false)
        );
        lcdView.setUsePoint2PointRender(
                prefs.getBoolean("prefUsePoint2PointRender", false)
        );
    }

    private int loadPortFromPrefs() {
        try {
            return Integer.parseInt(
                    PreferenceManager.getDefaultSharedPreferences(this)
                            .getString("prefPortNumber", String.valueOf(DEFAULT_PORT))
            );
        } catch (NumberFormatException e) {
            Toast.makeText(
                    this,
                    R.string.wrong_format_portnum,
                    Toast.LENGTH_LONG
            ).show();

            PreferenceManager.getDefaultSharedPreferences(this)
                    .edit()
                    .putString("prefPortNumber", String.valueOf(DEFAULT_PORT))
                    .apply();

            return DEFAULT_PORT;
        }
    }

    // ===========================
    // Theme Callback
    // ===========================

    @Override
    public void onThemeChanged(ColorPreset preset) {
        if (preset == null || lcdView == null) return;

        lcdView.setLcdColorPresent(
                preset.panelColor,
                preset.positiveColor,
                preset.negativeColor
        );
    }

    // ===========================
    // Color Picker Callback
    // ===========================

    @Override
    public void onColorSelected(int dialogId, @ColorInt int color) {
        osdController.proxyColorPickDialogReturn(dialogId, color);
    }

    @Override
    public void onDialogDismissed(int dialogId) {
        // no-op
    }

    // ===========================
    // Gesture Handling
    // ===========================

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2,
                           float velocityX, float velocityY) {

//        if (e1.getX() - e2.getX() > FLING_DISTANCE) { //right->left
//            //FullScreenHelper.enterFullScreen(this);
//            osdController.hide();
//        } else if (e2.getX() - e1.getX() > FLING_DISTANCE) {//left->right
//            //FullScreenHelper.exitFullScreen(this);
//            osdController.show();
//
//        }

        return false;
    }

    @Override
    public boolean onDown(MotionEvent e) {
        return false;
    }

    @Override
    public void onShowPress(MotionEvent e) {
    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        osdController.onScreenTapped();
        return true;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
        return false;
    }

    @Override
    public void onLongPress(MotionEvent e) {
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    // ===========================
    // Menu
    // ===========================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        int id = item.getItemId();

        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }

        if (id == R.id.action_about) {
            startActivity(new Intent(this, AboutPageActivity.class));
            return true;
        }

        return super.onOptionsItemSelected(item);
    }



}
