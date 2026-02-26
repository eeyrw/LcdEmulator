package com.yuan.lcmemulator;

import android.os.Build;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

public class FullScreenHelper {

    public static void enterFullScreen(AppCompatActivity activity) {

        if (Build.VERSION.SDK_INT < 16) {
            activity.getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            return;
        }

        View decor = activity.getWindow().getDecorView();

        if (Build.VERSION.SDK_INT >= 19) {
            // 4.4+ 真正沉浸式
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        } else {
            // 4.1 ~ 4.3
            // ⚠️ 不要使用 IMMERSIVE
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
            );
        }
    }

    public static void exitFullScreen(AppCompatActivity activity) {

        if (Build.VERSION.SDK_INT < 16) {
            activity.getWindow().clearFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            return;
        }

        View decor = activity.getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }
}