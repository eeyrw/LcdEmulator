package com.yuan.charlcmui;

import com.yuan.lcmemulator.CharLcmView;

public class IdlePage implements CharLcmUiPage {

    private CharLcmView mCharLcmView;

    @Override
    public void onCreate(CharLcmView charLcmView) {
        mCharLcmView = charLcmView;
    }

    @Override
    public void onPause() {

    }

    @Override
    public void onStop() {

    }

    @Override
    public void onDraw() {

    }
}
