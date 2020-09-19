package com.yuan.lcmemulator;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import java.util.Arrays;


/**
 * TODO: document your custom view class.
 */
public class CharLcmView extends View {

    private String TAG = "LCDEM";

    public int getNegativePixelColor() {
        return mNegativePixelColor;
    }

    public void setNegativePixelColor(int mNegativePixelColor) {
        this.mNegativePixelColor = mNegativePixelColor;
    }

    public int getPositivePixelColor() {
        return mPositivePixelColor;
    }

    public void setPositivePixelColor(int mPositivePixelColor) {
        this.mPositivePixelColor = mPositivePixelColor;
    }

    public int getLcdPanelColor() {
        return mLcdPanelColor;
    }

    public void setLcdPanelColor(int mLcdPanelColor) {
        this.mLcdPanelColor = mLcdPanelColor;
    }

    // Color
    private int mNegativePixelColor;
    private int mPositivePixelColor;
    private int mLcdPanelColor;

    // SurfaceView
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

    private boolean mIsRoundRectPixel;

    public boolean isRoundRectPixel() {
        return mIsRoundRectPixel;
    }

    public void setRoundRectPixel(boolean mIsRoundRectPixel) {
        this.mIsRoundRectPixel = mIsRoundRectPixel;
    }

    public String getText() {
        return mText;
    }

    public void setText(String mText) {
        setCursor(0, 0);
        this.mText = mText;
        char[] chars = mText.toCharArray();
        System.arraycopy(chars, 0, mLcmChars, 0,
                chars.length <= mLcmChars.length ? chars.length : mLcmChars.length);
        forceReDraw();
    }

    private String mText;


    public CharLcmView(Context context) {
        super(context);
        init(null, 0);
    }

    public CharLcmView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs, 0);
    }

    public CharLcmView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(attrs, defStyle);
    }

    private void init(AttributeSet attrs, int defStyle) {
        // Load attributes
        final TypedArray a = getContext().obtainStyledAttributes(
                attrs, R.styleable.CharLcmView, defStyle, 0);

        // Color
        mNegativePixelColor = a.getColor(
                R.styleable.CharLcmView_negativePixelColor,
                mNegativePixelColor);
        mPositivePixelColor = a.getColor(
                R.styleable.CharLcmView_positivePixelColor,
                mPositivePixelColor);
        mLcdPanelColor = a.getColor(
                R.styleable.CharLcmView_lcdPanelColor,
                mLcdPanelColor);

        mText = a.getString(R.styleable.CharLcmView_text);

        if (mText == null)
            mText = "Char LCM";

        a.recycle();
        // Size
        mSurfaceHeight = 100;
        mSurfaceWidth = 50;
        mCursorX = 0;
        mCursorY = 0;
        mColNum = 40;
        mRowNum = 4;
        mIsRoundRectPixel = true;

        //
        mCustomCharsRaw = new byte[8 * 8];

        for (int i = 0; i < 8 * 8; i++) {
            mCustomCharsRaw[i] = (char) 0x23; // 特殊图样
        }

        mLcmChars = new char[mRowNum * mColNum];
        for (int i = 0; i < mRowNum * mColNum; i++) {
            mLcmChars[i] = ' '; // 空格字符
        }
        char[] chars = mText.toCharArray();
        System.arraycopy(chars, 0, mLcmChars, 0,
                chars.length <= mLcmChars.length ? chars.length : mLcmChars.length);

        reGenResoures();
        forceReDraw();
        Log.d(TAG, "Char Lcm Init");
    }

    private void forceReDraw() {
        this.postInvalidate();
    }

    public void writeStr(String str) {

        char[] chars = str.toCharArray();
        System.arraycopy(chars, 0, mLcmChars, mCursorX + mCursorY * mColNum,
                chars.length);
        mCursorX += (mCursorX + mCursorY * mColNum + chars.length) % mColNum;
        forceReDraw();
    }

    public void setCustomFont(int index, byte[] rawdata) {

        System.arraycopy(rawdata, 0, mCustomCharsRaw, index * 8, rawdata.length);
        mFontCalc.genCustomFontBitmapByIndex(index, rawdata);
        forceReDraw();
    }

    public void clearScreen() {

        for (int i = 0; i < mRowNum * mColNum; i++) {
            mLcmChars[i] = ' '; // 空格字符
        }
        mCursorX = 0;
        mCursorY = 0;
        forceReDraw();
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

    public void reGenResoures() {
        mFontCalc = new FontCalc(new Point(mColNum, mRowNum), new Point(
                mSurfaceWidth, mSurfaceHeight), mCustomCharsRaw);
    }

    public void setColRow(int col, int row) {
        mColNum = col;
        mRowNum = row;
        reGenResoures();
        forceReDraw();
    }

    public void getColRow(int col, int row) {
        col = mColNum;
        row = mRowNum;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int paddingLeft = getPaddingLeft();
        int paddingTop = getPaddingTop();
        int paddingRight = getPaddingRight();
        int paddingBottom = getPaddingBottom();

        int contentWidth = getWidth() - paddingLeft - paddingRight;
        int contentHeight = getHeight() - paddingTop - paddingBottom;
        mSurfaceHeight = contentHeight;
        mSurfaceWidth = contentWidth;
        Log.d(TAG, String.format("LCM SIZE CHANGE: h:%d,w:%d", contentHeight, contentWidth));
        reGenResoures();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // TODO: consider storing these as member variables to reduce
        // allocations per draw cycle.
        int paddingLeft = getPaddingLeft();
        int paddingTop = getPaddingTop();
        int paddingRight = getPaddingRight();
        int paddingBottom = getPaddingBottom();

        int contentWidth = getWidth() - paddingLeft - paddingRight;
        int contentHeight = getHeight() - paddingTop - paddingBottom;
        mSurfaceHeight = contentHeight;
        mSurfaceWidth = contentWidth;


        char[] MirrorLcmChars = new char[mLcmChars.length];
        MirrorLcmChars = Arrays.copyOf(mLcmChars, mLcmChars.length);
        // Log.i(TAG, "Draw full screen.");
        canvas.drawColor(mLcdPanelColor);
        canvas.translate(paddingLeft,paddingTop);
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

        private double mCharWidthOffset;
        private double mCharHeightOffset;

        private Bitmap[] mFontBitmapMain;
        private Bitmap[] mFontBitmapCustom;

        private byte[] mCustomFontRawData;

        private static final double mPixelSpaceWeight = 1.3;
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

            mCharWidthOffset = mUnitWidth
                    * (mPixelWeight * mPixelsPerRow + mPixelSpaceWeight
                    * (mPixelsPerRow - 1) + mCharSpaceWeight);
            mCharHeightOffset = mUnitHeight
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
            pixelPaint.setStyle(Paint.Style.FILL);

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
                        pixelPaint.setColor(mPositivePixelColor);
                    else
                        pixelPaint.setColor(mNegativePixelColor);

                    if (mIsRoundRectPixel)
                        canvas.drawRoundRect(pixelRect, (float) charPixelWidth * 0.3f, (float) charPixelWidth * 0.3f, pixelPaint);
                    else
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
            pixelPaint.setStyle(Paint.Style.FILL);

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
                        pixelPaint.setColor(mPositivePixelColor);
                    else
                        pixelPaint.setColor(mNegativePixelColor);
                    if (mIsRoundRectPixel)
                        canvas.drawRoundRect(pixelRect, (float) charPixelWidth * 0.3f, (float) charPixelWidth * 0.3f, pixelPaint);
                    else
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
            Log.i(TAG, "Main font generated.");
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
            Log.i(TAG, "Custom font generated.");
        }

        public void genCustomFontBitmapByIndex(int fontIndex, byte[] singleFontData) {
            if (mFontBitmapCustom == null)
                genCustomFontBitmap(mCustomFontRawData, mUnitWidth, mUnitHeight);
            mFontBitmapCustom[fontIndex] = genSingleCustomFontBitmap(singleFontData,
                    mUnitWidth, mUnitHeight);
            Log.i(TAG, "Single custom font generated.");
        }

        public void setColRowSize(Point size) {
            mColRowSize = size;
        }

        public Point getColRowSize() {
            return mColRowSize;
        }

        public void getActualCursor(Point cursor, PointF actualCursor) {

            actualCursor.x = (float) (mMarginWeight * mUnitWidth + mCharWidthOffset
                    * cursor.x);
            actualCursor.y = (float) (mMarginWeight * mUnitHeight + mCharHeightOffset
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
