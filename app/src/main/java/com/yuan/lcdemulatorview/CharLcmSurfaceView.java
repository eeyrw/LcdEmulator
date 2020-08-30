package com.yuan.lcdemulatorview;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class CharLcmSurfaceView extends SurfaceView implements
        SurfaceHolder.Callback {

    private String TAG = "LCDEM";

    public int getNegetivePixelColor() {
        return mNegetivePixelColor;
    }

    public void setNegetivePixelColor(int mNegetivePixelColor) {
        this.mNegetivePixelColor = mNegetivePixelColor;
    }

    public int getPostivePixelColor() {
        return mPostivePixelColor;
    }

    public void setPostivePixelColor(int mPostivePixelColor) {
        this.mPostivePixelColor = mPostivePixelColor;
    }

    public int getLcdPanelColor() {
        return mLcdPanelColor;
    }

    public void setLcdPanelColor(int mLcdPanelColor) {
        this.mLcdPanelColor = mLcdPanelColor;
    }

    // Color
    private int mNegetivePixelColor;
    private int mPostivePixelColor;
    private int mLcdPanelColor;

    // SurfaceView
    private SurfaceHolder mSurfaceHolder;
    private DrawThread mDrawThread;
    private boolean mDrawThreadRunFlag;
    private int mSurfaceHeight;
    private int mSurfaceWidth;

    // Virtual LCM property
    private int mColNum;
    private int mRowNum;

    private int mCursorX;
    private int mCursorY;

    private char[] mLcmChars;
    private byte[] mCustomCharsRaw;

    // Font generation class instance
    private FontCalc mFontCalc;

    // Blocking queue for cmd transfer
    private BlockingQueue<DrawParam> mDrawParamQueue;

    public void updateFullScreen() {
        DrawParam dp = new DrawParam(DrawParam.CmdUpdateFullScreen, null);
        try {
            mDrawParamQueue.put(dp);
        } catch (InterruptedException e) {
            Log.e(TAG, e.getMessage());
        }
    }

    private void cursorAutoInc() {
        if (mCursorX < mColNum - 1) {
            ++mCursorX;
        } else {
            ++mCursorY;
            mCursorX = 0;
        }
    }

    public void writeChar(char ch) {

        // Log.i(TAG, "User cmd:writeChar.");

        Point postion = new Point(mCursorX, mCursorY);
        DrawCharParam dcp = new DrawCharParam(postion, ch);
        DrawParam dp = new DrawParam(DrawParam.CmdDrawChar, dcp);

        mLcmChars[mCursorY * mColNum + mCursorX] = ch;

        dcp.mChar = ch;

        try {
            mDrawParamQueue.put(dp);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        cursorAutoInc();
    }

    public void writeStr(String str) {

        Point postion = new Point(mCursorX, mCursorY);
        char[] chars = str.toCharArray();
        DrawStrParam dsp = new DrawStrParam(postion, chars);
        DrawParam dp = new DrawParam(DrawParam.CmdDrawStr, dsp);

        // mLcmChars[mCursorY * mColNum + mCursorX] = ch;
        try {
            System.arraycopy(chars, 0, mLcmChars, mCursorX + mCursorY * mColNum,
                    chars.length);
            mCursorX += (mCursorX + mCursorY * mColNum + chars.length) % mColNum;
            // mDrawParamQueue.put(dp);
            DrawParam dp2 = new DrawParam(DrawParam.CmdUpdateFullScreen, null);
            mDrawParamQueue.put(dp2);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }


    public void setCustomFont(int index, byte[] rawdata) {

        System.arraycopy(rawdata, 0, mCustomCharsRaw, index * 8, rawdata.length);
        reGenResoures();
    }

    public void setColRow(int col, int row) {
        mColNum = col;
        mRowNum = row;
        // reGenResoures();

    }

    public void getColRow(int col, int row) {
        col = mColNum;
        row = mRowNum;
    }

    private void setDefaultParam() {

        // SurfaceView
        mSurfaceHolder = getHolder();
        mSurfaceHolder.addCallback(this);
        // Thread
        mDrawThreadRunFlag = false;
        // Color
        mNegetivePixelColor = Color.rgb(100, 25, 0);
        mPostivePixelColor = Color.rgb(255, 255, 0);
        mLcdPanelColor = Color.rgb(80, 0, 0);
        // Size
        mSurfaceHeight = 100;
        mSurfaceWidth = 50;
        mCursorX = 0;
        mCursorY = 0;
        mColNum = 40;
        mRowNum = 4;

        //
        mCustomCharsRaw = new byte[8 * 8];

        for (int i = 0; i < 8 * 8; i++) {
            mCustomCharsRaw[i] = (char) 0x23; // 特殊图样
        }

        mLcmChars = new char[mRowNum * mColNum];
        for (int i = 0; i < mRowNum * mColNum; i++) {
            mLcmChars[i] = ' '; // 空格字符
        }

        // que
        mDrawParamQueue = new ArrayBlockingQueue<DrawParam>(50);

    }

    public void clearScreen() {

        Log.i(TAG, "User cmd:clear screen");

        DrawParam dp = new DrawParam(DrawParam.CmdClearScreen, null);

        // for (int i = 0; i < mRowNum * mColNum; i++) {
        // mLcmChars[i] = ' '; // 空格字符
        // }
        mCursorX = 0;
        mCursorY = 0;
        try {
            mDrawParamQueue.put(dp);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    public CharLcmSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);

        setDefaultParam();

    }

    // 在这里我们将测试canvas提供的绘制图形方法
    private Rect dirtyRect = new Rect();
    private PointF acutalCursor = new PointF();
    private Bitmap charBitmap = null;

    public void Draw(SurfaceHolder mSurfaceHolder, DrawParam dp) {

        Canvas canvas = null;

        // Log.i(TAG, "Try to Draw.");
        switch (dp.mCmdId) {
            case DrawParam.CmdDrawChar:

                if (mFontCalc != null) {
                    mFontCalc
                            .getActualCursor(
                                    ((DrawCharParam) dp.mArgObj).mCharPostion,
                                    acutalCursor);

                    charBitmap = mFontCalc
                            .getCharBitmap(((DrawCharParam) dp.mArgObj).mChar);
                    // dirtyRect = new Rect();
                    dirtyRect.left = (int) acutalCursor.x - 3;
                    dirtyRect.top = (int) acutalCursor.y - 3;
                    dirtyRect.right = dirtyRect.left + charBitmap.getWidth() + 3;
                    dirtyRect.bottom = dirtyRect.top + charBitmap.getHeight() + 3;
                }

                canvas = mSurfaceHolder.lockCanvas(dirtyRect);
                if (canvas != null) {
                    // canvas.drawColor(mLcdPanelColor);
                    // Log.i(TAG, "Draw dirty rect.);
                    canvas.drawBitmap(charBitmap, acutalCursor.x, acutalCursor.y,
                            null);
                }
                if (canvas != null)
                    mSurfaceHolder.unlockCanvasAndPost(canvas);

                break;
            case DrawParam.CmdUpdateFullScreen:

                for (int i = 0; i < 1; i++) {
                    canvas = mSurfaceHolder.lockCanvas();
                    if (canvas != null) {

                        char[] MirrorLcmChars = new char[mLcmChars.length];
                        MirrorLcmChars = Arrays.copyOf(mLcmChars, mLcmChars.length);
                        // Log.i(TAG, "Draw full screen.");
                        canvas.drawColor(mLcdPanelColor);
                        int dy = 0;
                        PointF postion = new PointF();
                        if (mFontCalc != null) {
                            for (int y = 0; y < mRowNum; y++) {
                                for (int x = 0; x < mColNum; x++) {
                                    mFontCalc.getActualCursor(x, y, postion);// y*mColNum+x+32
                                    canvas.drawBitmap(mFontCalc
                                                    .getCharBitmap(MirrorLcmChars[dy + x]),
                                            postion.x, postion.y, null);
                                }
                                dy += mColNum;
                            }
                        }
                    }
                    if (canvas != null)
                        mSurfaceHolder.unlockCanvasAndPost(canvas);
                }
                break;

            case DrawParam.CmdClearScreen:

                for (int i = 0; i < 2; i++) {
                    canvas = mSurfaceHolder.lockCanvas();
                    if (canvas != null) {

                        // Log.i(TAG, "Draw full screen.");
                        canvas.drawColor(mLcdPanelColor);

                        PointF postion = new PointF();
                        if (mFontCalc != null) {
                            for (int y = 0, dy = 0; y < mRowNum; y++) {
                                for (int x = 0; x < mColNum; x++) {
                                    mFontCalc.getActualCursor(x, y, postion);// y*mColNum+x+32
                                    canvas.drawBitmap(mFontCalc.getCharBitmap(' '),
                                            postion.x, postion.y, null);
                                }
                                dy += mColNum;
                            }
                        }
                    }
                    if (canvas != null)
                        mSurfaceHolder.unlockCanvasAndPost(canvas);

                    Arrays.fill(mLcmChars, ' ');
                }

                break;
            case DrawParam.CmdDrawStr:
                float delt_x = (float) mFontCalc.mCharWidthOffest;
                float start_x = acutalCursor.x;
                char[] str = ((DrawStrParam) dp.mArgObj).mStr;
                if (mFontCalc != null) {
                    mFontCalc.getActualCursor(
                            ((DrawStrParam) dp.mArgObj).mStrPostion, acutalCursor);

                    // Log.i(TAG,"Draw Str:Str_lem="+str.length+" postion="+((DrawStrParam)dp.mArgObj).mStrPostion.x+","+((DrawStrParam)dp.mArgObj).mStrPostion.y);
                    charBitmap = mFontCalc.getCharBitmap(str[0]);
                    // dirtyRect = new Rect();
                    dirtyRect.left = (int) acutalCursor.x - 3;
                    dirtyRect.top = (int) acutalCursor.y - 3;
                    dirtyRect.right = dirtyRect.left
                            + (int) (delt_x * (str.length - 1)) + 3;
                    dirtyRect.bottom = dirtyRect.top + charBitmap.getHeight() + 3;
                }

                canvas = mSurfaceHolder.lockCanvas(dirtyRect);
                if (canvas != null) {
                    // canvas.drawColor(mLcdPanelColor);
                    // Log.i(TAG, "Draw dirty rect.);

                    for (int i = 0; i < (str.length - 1); i++) {
                        charBitmap = mFontCalc.getCharBitmap(str[i]);
                        canvas.drawBitmap(charBitmap, start_x, acutalCursor.y, null);
                        start_x += delt_x;
                    }
                }
                if (canvas != null)
                    mSurfaceHolder.unlockCanvasAndPost(canvas);

                break;

        }

    }

    // public synchronized void issueRedraw() {
    // synchronized (token) {
    // token.notify();
    // }
    // }

    @Override
    public void surfaceChanged(SurfaceHolder mSurfaceHolder, int format,
                               int width, int height) {
        Log.i(TAG, "onSurfaceChanged");

        mDrawParamQueue = new ArrayBlockingQueue<DrawParam>(50);
        mSurfaceHeight = height;
        mSurfaceWidth = width;
        reGenResoures();

        mDrawThread = new DrawThread();
        mDrawThreadRunFlag = true;
        mDrawThread.start();

    }

    public void reGenResoures() {
        mFontCalc = new FontCalc(new Point(mColNum, mRowNum), new Point(
                mSurfaceWidth, mSurfaceHeight), mCustomCharsRaw);

        updateFullScreen();
    }

    @Override
    public void surfaceCreated(SurfaceHolder mSurfaceHolder) {
        Log.i(TAG, "onSurfaceCreated");

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder mSurfaceHolder) {
        Log.i(TAG, "onSurfaceDestroyed");
        mDrawThreadRunFlag = false;

    }

    class DrawThread extends Thread {
        @Override
        public void run() {
            SurfaceHolder runholder = mSurfaceHolder;
            Log.i(TAG, "Run into thread.");
            while (mDrawThreadRunFlag) {
                DrawParam dp = null;
                try {
                    dp = mDrawParamQueue.take();

                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                Draw(runholder, dp);

            }
            Log.i(TAG, "Exit from thread.");
        }
    }

    public class DrawParam {

        public final static int CmdDrawChar = 1;
        public final static int CmdUpdateFullScreen = 2;
        public final static int CmdClearScreen = 3;
        public final static int CmdDrawStr = 4;

        public int mCmdId;
        public Object mArgObj;

        public DrawParam(int cmdId, Object arg) {
            mCmdId = cmdId;
            mArgObj = arg;

        }

        // public int getCmdId() {
        // return mCmdId;
        // }
        //
        // public void setCmdId(int cmdId) {
        // mCmdId = cmdId;
        // }
        //
        // public DrawCharParam getArgObj() {
        // return mArgObj;
        // }
        //
        // public void setArgObj(DrawCharParam arg) {
        // mArgObj = arg;
        // }

    }


    public class DrawCharParam {

        public Point mCharPostion;
        public char mChar;

        public DrawCharParam(Point charPostion, char ch) {
            mCharPostion = charPostion;
            mChar = ch;

        }

        // public Point getcharPostion() {
        // return mCharPostion;
        // }
        //
        // public void setcharPostion(Point charPostion) {
        // mCharPostion = charPostion;
        // }
        //
        // public char getChar() {
        // return mChar;
        // }
        //
        // public void setChar(char ch) {
        // mChar = ch;
        // }

    }

    public class DrawStrParam {

        public Point mStrPostion;
        public char[] mStr;

        public DrawStrParam(Point charPostion, char[] str) {
            mStrPostion = charPostion;
            mStr = str;

        }

        // public Point getcharPostion() {
        // return mCharPostion;
        // }
        //
        // public void setcharPostion(Point charPostion) {
        // mCharPostion = charPostion;
        // }
        //
        // public char getChar() {
        // return mChar;
        // }
        //
        // public void setChar(char ch) {
        // mChar = ch;
        // }

    }

    public void setCursor(int x, int y) {
        mCursorX = x;
        mCursorY = y;
    }

    public void setCursor(Point cursor) {
        mCursorX = cursor.x;
        mCursorY = cursor.y;
    }

    public void getCursor(int x, int y) {
        x = mCursorX;
        y = mCursorY;
    }

    public void getCursor(Point cursor) {
        cursor.x = mCursorX;
        cursor.y = mCursorY;
    }

    public class FontCalc {

        private byte[] mRawFontsData = { // 纵向排列的。
                0x00, 0x00, 0x00, 0x00, 0x00,// ' '
                0x00, 0x00, 0x5F, 0x00, 0x00,// !
                0x00, 0x07, 0x00, 0x07, 0x00,// "
                0x14, 0x7F, 0x14, 0x7F, 0x14,// #
                0x24, 0x2A, 0x7F, 0x2A, 0x12,// $
                0x23, 0x13, 0x08, 0x64, 0x62,// %
                0x37, 0x49, 0x55, 0x22, 0x50,// &
                0x00, 0x05, 0x03, 0x00, 0x00,// '
                0x00, 0x1C, 0x22, 0x41, 0x00,// (
                0x00, 0x41, 0x22, 0x1C, 0x00,// )
                0x08, 0x2A, 0x1C, 0x2A, 0x08,// *
                0x08, 0x08, 0x3E, 0x08, 0x08,// +
                0x00, 0x50, 0x30, 0x00, 0x00,// ,
                0x08, 0x08, 0x08, 0x08, 0x08,// -
                0x00, 0x60, 0x60, 0x00, 0x00,// .
                0x20, 0x10, 0x08, 0x04, 0x02,// /
                0x3E, 0x51, 0x49, 0x45, 0x3E,// 0
                0x00, 0x42, 0x7F, 0x40, 0x00,// 1
                0x42, 0x61, 0x51, 0x49, 0x46,// 2
                0x21, 0x41, 0x45, 0x4B, 0x31,// 3
                0x18, 0x14, 0x12, 0x7F, 0x10,// 4
                0x27, 0x45, 0x45, 0x45, 0x39,// 5
                0x3C, 0x4A, 0x49, 0x49, 0x30,// 6
                0x01, 0x71, 0x09, 0x05, 0x03,// 7
                0x36, 0x49, 0x49, 0x49, 0x36,// 8
                0x06, 0x49, 0x49, 0x29, 0x1E,// 9
                0x00, 0x36, 0x36, 0x00, 0x00,// :
                0x00, 0x56, 0x36, 0x00, 0x00,// ;
                0x00, 0x08, 0x14, 0x22, 0x41,// <
                0x14, 0x14, 0x14, 0x14, 0x14,// =
                0x41, 0x22, 0x14, 0x08, 0x00,// >
                0x02, 0x01, 0x51, 0x09, 0x06,// ?
                0x32, 0x49, 0x79, 0x41, 0x3E,// @
                0x7E, 0x11, 0x11, 0x11, 0x7E,// A
                0x7F, 0x49, 0x49, 0x49, 0x36,// B
                0x3E, 0x41, 0x41, 0x41, 0x22,// C
                0x7F, 0x41, 0x41, 0x22, 0x1C,// D
                0x7F, 0x49, 0x49, 0x49, 0x41,// E
                0x7F, 0x09, 0x09, 0x01, 0x01,// F
                0x3E, 0x41, 0x41, 0x51, 0x32,// G
                0x7F, 0x08, 0x08, 0x08, 0x7F,// H
                0x00, 0x41, 0x7F, 0x41, 0x00,// I
                0x20, 0x40, 0x41, 0x3F, 0x01,// J
                0x7F, 0x08, 0x14, 0x22, 0x41,// K
                0x7F, 0x40, 0x40, 0x40, 0x40,// L
                0x7F, 0x02, 0x04, 0x02, 0x7F,// M
                0x7F, 0x04, 0x08, 0x10, 0x7F,// N
                0x3E, 0x41, 0x41, 0x41, 0x3E,// O
                0x7F, 0x09, 0x09, 0x09, 0x06,// P
                0x3E, 0x41, 0x51, 0x21, 0x5E,// Q
                0x7F, 0x09, 0x19, 0x29, 0x46,// R
                0x46, 0x49, 0x49, 0x49, 0x31,// S
                0x01, 0x01, 0x7F, 0x01, 0x01,// T
                0x3F, 0x40, 0x40, 0x40, 0x3F,// U
                0x1F, 0x20, 0x40, 0x20, 0x1F,// V
                0x7F, 0x20, 0x18, 0x20, 0x7F,// W
                0x63, 0x14, 0x08, 0x14, 0x63,// X
                0x03, 0x04, 0x78, 0x04, 0x03,// Y
                0x61, 0x51, 0x49, 0x45, 0x43,// Z
                0x00, 0x00, 0x7F, 0x41, 0x41,// [
                0x02, 0x04, 0x08, 0x10, 0x20,// "\"
                0x41, 0x41, 0x7F, 0x00, 0x00,// ]
                0x04, 0x02, 0x01, 0x02, 0x04,// ^
                0x40, 0x40, 0x40, 0x40, 0x40,// _
                0x00, 0x01, 0x02, 0x04, 0x00,// `
                0x20, 0x54, 0x54, 0x54, 0x78,// a
                0x7F, 0x48, 0x44, 0x44, 0x38,// b
                0x38, 0x44, 0x44, 0x44, 0x20,// c
                0x38, 0x44, 0x44, 0x48, 0x7F,// d
                0x38, 0x54, 0x54, 0x54, 0x18,// e
                0x08, 0x7E, 0x09, 0x01, 0x02,// f
                0x08, 0x14, 0x54, 0x54, 0x3C,// g
                0x7F, 0x08, 0x04, 0x04, 0x78,// h
                0x00, 0x44, 0x7D, 0x40, 0x00,// i
                0x20, 0x40, 0x44, 0x3D, 0x00,// j
                0x00, 0x7F, 0x10, 0x28, 0x44,// k
                0x00, 0x41, 0x7F, 0x40, 0x00,// l
                0x7C, 0x04, 0x18, 0x04, 0x78,// m
                0x7C, 0x08, 0x04, 0x04, 0x78,// n
                0x38, 0x44, 0x44, 0x44, 0x38,// o
                0x7C, 0x14, 0x14, 0x14, 0x08,// p
                0x08, 0x14, 0x14, 0x18, 0x7C,// q
                0x7C, 0x08, 0x04, 0x04, 0x08,// r
                0x48, 0x54, 0x54, 0x54, 0x20,// s
                0x04, 0x3F, 0x44, 0x40, 0x20,// t
                0x3C, 0x40, 0x40, 0x20, 0x7C,// u
                0x1C, 0x20, 0x40, 0x20, 0x1C,// v
                0x3C, 0x40, 0x30, 0x40, 0x3C,// w
                0x44, 0x28, 0x10, 0x28, 0x44,// x
                0x0C, 0x50, 0x50, 0x50, 0x3C,// y
                0x44, 0x64, 0x54, 0x4C, 0x44,// z
                0x00, 0x08, 0x36, 0x41, 0x00,// {
                0x00, 0x00, 0x7F, 0x00, 0x00,// |
                0x00, 0x41, 0x36, 0x08, 0x00,// }
                0x02, 0x01, 0x02, 0x04, 0x02,// ~
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff // black
                // block
        };
        private double mUnitWidth;
        private double mUnitHeight;

        private double mCharWidthOffest;
        private double mCharHeightOffest;

        private Bitmap[] mFontBitmapMain;
        private Bitmap[] mFontBitmapCustom;

        private byte[] mCustomFontRawData;

        private static final double mPixelSpaceWeight = 1;
        private static final double mPixelWeight = 5;
        private static final double mCharSpaceWeight = 5;
        private static final double mMarginWeight = 12;

        private static final double mPixelsPerRow = 5;
        private static final double mPixelsPerCol = 8;

        private static final int mBytesPerFont = 5;

        private Point mColRowSize;

        public FontCalc(Point colRowSize, Point areaSize,
                        byte[] customFontRawData) {
            mColRowSize = colRowSize;
            double colNum = mColRowSize.x;
            double rowNum = mColRowSize.y;

            double surfaceWidth = areaSize.x;
            double surfaceHeight = areaSize.y;
            // mMarginWeight*2+colNum*(mPixelWeight*mPixelsPerRow+mPixelSpaceWeight*(mPixelsPerRow-1))+(colNum-1)*mCharSpaceWeight
            mUnitWidth = surfaceWidth
                    / (mMarginWeight
                    * 2
                    + colNum
                    * (mPixelWeight * mPixelsPerRow + mPixelSpaceWeight
                    * (mPixelsPerRow - 1)) + (colNum - 1)
                    * mCharSpaceWeight);
            // mMarginWeight*2+rowNum*(mPixelWeight*mPixelsPerCol+mPixelSpaceWeight*(mPixelsPerCol-1))+(rowNum-1)*2*mCharSpaceWeight
            mUnitHeight = surfaceHeight
                    / (mMarginWeight
                    * 2
                    + rowNum
                    * (mPixelWeight * mPixelsPerCol + mPixelSpaceWeight
                    * (mPixelsPerCol - 1)) + (rowNum - 1) * 2
                    * mCharSpaceWeight);

            mCustomFontRawData = customFontRawData;

            genMainFontBitmap(mUnitWidth, mUnitHeight);
            genCustomFontBitmap(mCustomFontRawData, mUnitWidth, mUnitHeight);

            mCharWidthOffest = mUnitWidth
                    * (mPixelWeight * mPixelsPerRow + mPixelSpaceWeight
                    * (mPixelsPerRow - 1) + mCharSpaceWeight);
            mCharHeightOffest = mUnitHeight
                    * (mPixelWeight * mPixelsPerCol + mPixelSpaceWeight
                    * (mPixelsPerCol - 1) + mCharSpaceWeight * 2);
        }

        public Bitmap genSingleCustomFontBitmap(byte[] raw, double unitWidth,
                                                double unitHeight) {

            int bitmapWidth = (int) (unitWidth * (mPixelWeight * mPixelsPerRow + mPixelSpaceWeight
                    * (mPixelsPerRow - 1)));
            int bitmapHeight = (int) (unitHeight * (mPixelWeight
                    * mPixelsPerCol + mPixelSpaceWeight * (mPixelsPerCol - 1)));

            Bitmap fontBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight,
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(fontBitmap);
            double charPixelWidth = mUnitWidth * mPixelWeight;
            double charPixelHeight = mUnitHeight * mPixelWeight;
            canvas.drawColor(mLcdPanelColor);
            Paint pixelPaint = new Paint();

            pixelPaint.setAntiAlias(true); // 反锯齿
            pixelPaint.setStyle(Style.FILL);

            for (int y = 0; y < mPixelsPerCol; ++y) {
                for (int x = 0; x < mPixelsPerRow; ++x) {

                    float pixelRectLeft = (float) (x * (charPixelWidth + mPixelSpaceWeight
                            * mUnitWidth));
                    float pixelRectTop = (float) (y * (charPixelHeight + mPixelSpaceWeight
                            * mUnitHeight));
                    float pixelRectRight = (float) (pixelRectLeft + charPixelWidth);
                    float pixelRectBottom = (float) (pixelRectTop + charPixelHeight);

                    RectF pixelRect = new RectF(pixelRectLeft, pixelRectTop,
                            pixelRectRight, pixelRectBottom);
                    if ((raw[y] & (1 << x)) != 0)
                        pixelPaint.setColor(mPostivePixelColor);
                    else
                        pixelPaint.setColor(mNegetivePixelColor);
                    canvas.drawRect(pixelRect, pixelPaint);

                }
            }
            return fontBitmap;

        }

        public Bitmap genSingleFontBitmap(int fontIndex, double unitWidth,
                                          double unitHeight) {

            int bitmapWidth = (int) (unitWidth * (mPixelWeight * mPixelsPerRow + mPixelSpaceWeight
                    * (mPixelsPerRow - 1)));
            int bitmapHeight = (int) (unitHeight * (mPixelWeight
                    * mPixelsPerCol + mPixelSpaceWeight * (mPixelsPerCol - 1)));

            Bitmap fontBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight,
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(fontBitmap);
            double charPixelWidth = mUnitWidth * mPixelWeight;
            double charPixelHeight = mUnitHeight * mPixelWeight;
            canvas.drawColor(mLcdPanelColor);
            Paint pixelPaint = new Paint();

            pixelPaint.setAntiAlias(true); // 反锯齿
            pixelPaint.setStyle(Style.FILL);

            for (int x = 0; x < mPixelsPerRow; ++x) {
                for (int y = 0; y < mPixelsPerCol; ++y) {

                    float pixelRectLeft = (float) (x * (charPixelWidth + mPixelSpaceWeight
                            * mUnitWidth));
                    float pixelRectTop = (float) (y * (charPixelHeight + mPixelSpaceWeight
                            * mUnitHeight));
                    float pixelRectRight = (float) (pixelRectLeft + charPixelWidth);
                    float pixelRectBottom = (float) (pixelRectTop + charPixelHeight);

                    RectF pixelRect = new RectF(pixelRectLeft, pixelRectTop,
                            pixelRectRight, pixelRectBottom);
                    if ((mRawFontsData[(int) (fontIndex * mPixelsPerRow + x)] & (1 << y)) != 0)
                        pixelPaint.setColor(mPostivePixelColor);
                    else
                        pixelPaint.setColor(mNegetivePixelColor);
                    canvas.drawRect(pixelRect, pixelPaint);

                }
            }
            return fontBitmap;

        }

        public void genMainFontBitmap(double unitWidth, double unitHeight) {

            int fontNum = mRawFontsData.length / mBytesPerFont;

            mFontBitmapMain = new Bitmap[fontNum];

            for (int fontIndex = 0; fontIndex < fontNum; ++fontIndex) {
                mFontBitmapMain[fontIndex] = genSingleFontBitmap(fontIndex,
                        unitWidth, unitHeight);
            }
            Log.i(TAG, "Custom font generated.");
        }

        public void genCustomFontBitmap(byte[] allRawData, double unitWidth,
                                        double unitHeight) {

            int fontNum = 8;

            mFontBitmapCustom = new Bitmap[fontNum];

            byte[] temp = new byte[8];

            for (int fontIndex = 0; fontIndex < fontNum; ++fontIndex) {
                System.arraycopy(allRawData, fontIndex * 8, temp, 0,
                        temp.length);
                mFontBitmapCustom[fontIndex] = genSingleCustomFontBitmap(temp,
                        unitWidth, unitHeight);
            }
            Log.i(TAG, "Main font generated.");
        }

        public void setColRowSize(Point size) {
            mColRowSize = size;
        }

        public Point getColRowSize() {
            return mColRowSize;
        }

        public void getActualCursor(Point cursor, PointF actualCursor) {

            actualCursor.x = (float) (mMarginWeight * mUnitWidth + mCharWidthOffest
                    * cursor.x);
            actualCursor.y = (float) (mMarginWeight * mUnitHeight + mCharHeightOffest
                    * cursor.y);
        }

        public void getActualCursor(int x, int y, PointF actualCursor) {
            getActualCursor(new Point(x, y), actualCursor);
        }

        public Bitmap getCharBitmap(char ch) {

            // Type "char" in java is based on unicode.
            int charNum = ch & 0xff;

            // ' ' in ASCII is 32
            if (charNum >= 32 && charNum <= 127) {
                charNum -= 32;
            } else if (charNum >= 0 && charNum <= 7) {
                return mFontBitmapCustom[charNum];
            } else {
                charNum = 32;
            }

            return mFontBitmapMain[charNum];

        }

    }

}