package com.yuan.charlcmui;

import com.yuan.lcmemulator.CharLcmView;

public interface CharLcmUiPage {
    public void onCreate(CharLcmView charLcmView);

    public void onPause();

    public void onStop();

    public void onDraw();
}
