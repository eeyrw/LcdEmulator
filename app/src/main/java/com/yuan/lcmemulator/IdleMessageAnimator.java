package com.yuan.lcmemulator;

import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 闲置消息动画器
 * <p>
 * 动画流程：
 * <p>
 * 1️⃣ 混沌增强阶段（不是直接满屏）
 * 2️⃣ 混沌衰减 + 文字逐字符闪烁锁定
 * 3️⃣ 稳定显示
 * 4️⃣ 如果空间足够，绘制动态边框
 */
public class IdleMessageAnimator {

    private static final int FPS = 50;
    private static final float NOISE_GROW_SPEED = 0.05f;   // 混沌增强速度
    private static final float NOISE_DECAY_SPEED = 0.05f;  // 混沌衰减速度
    // 每帧锁定概率
    private static final float LOCK_PROBABILITY = 0.1f;
    /* =========================
       随机字符集合
       ========================= */
    private static final String CHARSET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
                    "abcdefghijklmnopqrstuvwxyz" +
                    "0123456789" +
                    "!@#$%^&*()-_=+[]{}<>?/";
    private final CharLcmView lcm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private boolean running = false;

    /* =========================
       混沌控制参数
       ========================= */
    private State state;
    private int rows;
    private int cols;

    /* =========================
       文本相关
       ========================= */
    private float noiseLevel = 0f;      // 当前噪声密度
    private String message;
    private String[] lines;
    private int startRow;
    // 每个目标字符是否已经锁定
    private boolean[][] locked;

    /* =========================
       边框
       ========================= */
    private int boxTop;
    private int boxBottom;
    private int boxLeft;
    private int boxRight;

    private boolean drawBorder = false;
    private int borderPhase = 0;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {

            if (!running) return;

            update();
            lcm.invalidate();

            handler.postDelayed(this, FPS);
        }
    };

    /* =========================
       构造
       ========================= */
    public IdleMessageAnimator(CharLcmView view) {
        this.lcm = view;
        refreshSize();
    }

    private void refreshSize() {
        Point p = new Point();
        lcm.getColRow(p);
        cols = p.x;
        rows = p.y;
    }

    /* =========================
       对外 API
       ========================= */

    public void start(String msg) {

        if (running) stop();

        this.message = msg;
        refreshSize();

        layoutMessage();
        initLockMatrix();

        noiseLevel = 0f;              // 从无噪声开始
        state = State.CHAOS_GROWING;

        running = true;
        handler.post(tick);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    public boolean isRunning() {
        return running;
    }

    /* =========================
       主循环
       ========================= */

    private void update() {

        lcm.clearScreen();

        switch (state) {

            /* ====================================
               1️⃣ 混沌逐渐增强
               ==================================== */
            case CHAOS_GROWING:

                noiseLevel += NOISE_GROW_SPEED;
                if (noiseLevel >= 1f) {
                    noiseLevel = 1f;
                    state = State.CONVERGING;
                }

                drawChaos(noiseLevel);
                break;

            /* ====================================
               2️⃣ 混沌衰减 + 逐字符锁定
               ==================================== */
            case CONVERGING:

                // 全局噪声逐渐减少
                noiseLevel -= NOISE_DECAY_SPEED;
                if (noiseLevel < 0f) noiseLevel = 0f;

                drawChaos(noiseLevel);

                boolean allLocked = drawFlashingAndLocking();

                if (noiseLevel == 0f && allLocked) {
                    state = State.STABLE;
                }

                break;

            /* ====================================
               3️⃣ 稳定显示
               ==================================== */
            case STABLE:

                drawFinalText();

                if (drawBorder) {
                    drawAnimatedBorder();
                }

                break;
        }
    }

    private void layoutMessage() {

        String[] rawLines = message.split("\n");
        List<String> result = new ArrayList<>();

        for (String raw : rawLines) {

            int index = 0;

            while (index < raw.length()) {
                int end = Math.min(index + cols, raw.length());
                result.add(raw.substring(index, end));
                index += cols;
            }

            if (raw.length() == 0) {
                result.add("");
            }
        }

        lines = result.toArray(new String[0]);
        startRow = Math.max(0, (rows - lines.length) / 2);

        // 计算最大宽度
        int maxWidth = 0;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, line.length());
        }

        boxTop = startRow;
        boxBottom = startRow + lines.length - 1;
        boxLeft = (cols - maxWidth) / 2;
        boxRight = boxLeft + maxWidth - 1;

        // 判断是否有空间绘制边框
        drawBorder =
                boxTop > 0 &&
                        boxBottom < rows - 1 &&
                        boxLeft > 0 &&
                        boxRight < cols - 1;
    }

    /* =========================
       文本布局
       ========================= */

    private void initLockMatrix() {
        locked = new boolean[rows][cols];
    }

    private void drawChaos(float level) {

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {

                if (random.nextFloat() < level) {
                    lcm.putChar(c, r, randomChar());
                }
            }
        }
    }

    /* =========================
       混沌绘制
       ========================= */

    private boolean drawFlashingAndLocking() {

        boolean allLocked = true;

        for (int i = 0; i < lines.length; i++) {

            String line = lines[i];
            int row = startRow + i;

            if (row < 0 || row >= rows) continue;

            int startCol = Math.max(0, (cols - line.length()) / 2);

            for (int c = 0; c < line.length(); c++) {

                int col = startCol + c;

                if (!locked[row][col]) {

                    // 未锁定：持续随机闪烁
                    lcm.putChar(col, row, randomChar());

                    // 随机概率锁定
                    if (random.nextFloat() < LOCK_PROBABILITY) {
                        locked[row][col] = true;
                    } else {
                        allLocked = false;
                    }

                } else {
                    // 已锁定：显示最终字符
                    lcm.putChar(col, row, line.charAt(c));
                }
            }
        }

        return allLocked;
    }

    /* =========================
       逐字符闪烁 + 锁定
       ========================= */

    private void drawFinalText() {

        for (int i = 0; i < lines.length; i++) {

            String line = lines[i];
            int row = startRow + i;

            if (row < 0 || row >= rows) continue;

            int startCol = Math.max(0, (cols - line.length()) / 2);

            for (int c = 0; c < line.length(); c++) {
                lcm.putChar(startCol + c, row, line.charAt(c));
            }
        }
    }

    /* =========================
       最终文字
       ========================= */

    private void drawAnimatedBorder() {

        borderPhase++;

        char h = (borderPhase % 20 < 10) ? '-' : '=';
        char v = (borderPhase % 20 < 10) ? '|' : '!';

        int top = boxTop - 1;
        int bottom = boxBottom + 1;
        int left = boxLeft - 1;
        int right = boxRight + 1;

        // 上下
        for (int c = left + 1; c < right; c++) {
            lcm.putChar(c, top, h);
            lcm.putChar(c, bottom, h);
        }

        // 左右
        for (int r = top + 1; r < bottom; r++) {
            lcm.putChar(left, r, v);
            lcm.putChar(right, r, v);
        }

        // 四角
        lcm.putChar(left, top, '+');
        lcm.putChar(right, top, '+');
        lcm.putChar(left, bottom, '+');
        lcm.putChar(right, bottom, '+');
    }

    /* =========================
       动态边框
       ========================= */

    private char randomChar() {
        return CHARSET.charAt(random.nextInt(CHARSET.length()));
    }

    /* =========================
       状态机
       ========================= */
    private enum State {
        CHAOS_GROWING,     // 混沌逐渐增强
        CONVERGING,        // 混沌衰减 + 字符锁定
        STABLE             // 稳定显示
    }
}