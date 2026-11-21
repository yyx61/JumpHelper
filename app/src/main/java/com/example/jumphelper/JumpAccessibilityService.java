package com.example.jumphelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 核心服务：
 * 1) 维护一个悬浮面板（测距 / 系数 +- / 关闭）
 * 2) 测距模式下铺一层全屏透明视图收取两个点
 * 3) 算出像素距离 -> 毫秒时长 -> dispatchGesture 注入长按
 *
 * 关键顺序：注入前必须先撇掉取点层、并让面板不吃触摸，
 * 否则注入的手势会打在自己的悬浮窗上，游戏收不到。
 */
public class JumpAccessibilityService extends AccessibilityService {

    private static volatile JumpAccessibilityService instance;

    public static JumpAccessibilityService getInstance() {
        return instance;
    }

    private WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;

    private MarkerView capture;
    private WindowManager.LayoutParams captureLp;

    private TextView infoView;
    private TextView coefView;

    private PointF startPoint;
    private boolean injecting;
    private String lastResult = "就绪";

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        if (Prefs.getCoefficient(this) <= 0f) {
            Prefs.setCoefficient(this, defaultCoefficient());
        }
        if (Prefs.isPanelEnabled(this)) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    showPanel();
                }
            });
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不需要监听任何事件，只用手势注入能力
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        teardown();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        teardown();
        instance = null;
        super.onDestroy();
    }

    private void teardown() {
        removeCapture();
        if (panel != null) {
            try {
                wm.removeView(panel);
            } catch (Exception ignored) {
            }
            panel = null;
            panelLp = null;
        }
    }

    // ------------------------------------------------------------ 悬浮面板

    public boolean isPanelShown() {
        return panel != null;
    }

    public void togglePanel() {
        if (panel == null) {
            showPanel();
            Prefs.setPanelEnabled(this, panel != null);
        } else {
            removeCapture();
            try {
                wm.removeView(panel);
            } catch (Exception ignored) {
            }
            panel = null;
            panelLp = null;
            Prefs.setPanelEnabled(this, false);
        }
    }

    private void showPanel() {
        if (panel != null) {
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限");
            return;
        }
        final float d = getResources().getDisplayMetrics().density;

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(24f * d);
        bg.setColor(0xE61C1C1E);
        bg.setStroke(Math.max(1, (int) d), 0x33FFFFFF);
        root.setBackground(bg);
        root.setPadding((int) (8 * d), (int) (4 * d), (int) (6 * d), (int) (4 * d));

        // 左侧文字区：拖动手柄
        infoView = new TextView(this);
        infoView.setText(lastResult);
        infoView.setTextColor(Color.WHITE);
        infoView.setTextSize(13f);
        infoView.setPadding((int) (8 * d), (int) (12 * d), (int) (10 * d), (int) (12 * d));
        root.addView(infoView);

        root.addView(chip("测距", 0xFF2F6BFF, new Runnable() {
            @Override
            public void run() {
                startMeasure();
            }
        }));
        root.addView(chip("-", 0xFF3A3A3E, new Runnable() {
            @Override
            public void run() {
                adjustCoefficient(-0.02f);
            }
        }));

        coefView = new TextView(this);
        coefView.setText(fmt(Prefs.getCoefficient(this)));
        coefView.setTextColor(Color.WHITE);
        coefView.setTextSize(13f);
        coefView.setGravity(Gravity.CENTER);
        coefView.setPadding((int) (6 * d), (int) (12 * d), (int) (6 * d), (int) (12 * d));
        coefView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetCoefficient();
            }
        });
        root.addView(coefView);

        root.addView(chip("+", 0xFF3A3A3E, new Runnable() {
            @Override
            public void run() {
                adjustCoefficient(0.02f);
            }
        }));
        root.addView(chip("x", 0xFFB3261E, new Runnable() {
            @Override
            public void run() {
                togglePanel();
            }
        }));

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int) (8 * d);
        lp.y = (int) (getResources().getDisplayMetrics().heightPixels * 0.30f);

        // 只用文字区当拖动手柄，避免抢掉按钮的点击事件
        infoView.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private int baseX;
            private int baseY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        baseX = lp.x;
                        baseY = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = baseX + (int) (e.getRawX() - downX);
                        lp.y = baseY + (int) (e.getRawY() - downY);
                        try {
                            wm.updateViewLayout(root, lp);
                        } catch (Exception ignored) {
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        try {
            wm.addView(root, lp);
            panel = root;
            panelLp = lp;
        } catch (Exception e) {
            toast("悬浮窗创建失败：" + e.getMessage());
        }
    }

    private TextView chip(String label, int color, final Runnable action) {
        final float d = getResources().getDisplayMetrics().density;
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding((int) (12 * d), (int) (12 * d), (int) (12 * d), (int) (12 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(16f * d);
        bg.setColor(color);
        tv.setBackground(bg);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return tv;
    }

    private void setInfo(String s) {
        lastResult = s;
        if (infoView != null) {
            infoView.setText(s);
        }
    }

    private void adjustCoefficient(float delta) {
        float v = Prefs.getCoefficient(this) + delta;
        if (v < 0.2f) {
            v = 0.2f;
        }
        if (v > 6f) {
            v = 6f;
        }
        Prefs.setCoefficient(this, v);
        if (coefView != null) {
            coefView.setText(fmt(v));
        }
        toast("系数 = " + fmt(v));
    }

    private void resetCoefficient() {
        float v = defaultCoefficient();
        Prefs.setCoefficient(this, v);
        if (coefView != null) {
            coefView.setText(fmt(v));
        }
        toast("已恢复默认系数 " + fmt(v));
    }

    /** 经验起点：1080 宽屏上 1.36 ms/px，按实际宽屏等比缩放 */
    private float defaultCoefficient() {
        return 1.36f * getResources().getDisplayMetrics().widthPixels / 1080f;
    }

    private String fmt(float v) {
        return String.format(Locale.US, "%.2f", v);
    }

    // ------------------------------------------------------------ 测距模式

    private void startMeasure() {
        if (injecting) {
            toast("动作执行中，请稍候");
            return;
        }
        // 再点一次「测距」= 取消
        if (capture != null) {
            removeCapture();
            startPoint = null;
            setInfo("就绪");
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限");
            return;
        }

        MarkerView v = new MarkerView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;

        try {
            wm.addView(v, lp);
            capture = v;
            captureLp = lp;
            startPoint = null;
            setInfo("点棋子中心");
        } catch (Exception e) {
            toast("测距层创建失败：" + e.getMessage());
        }
    }

    private void removeCapture() {
        if (capture != null) {
            try {
                wm.removeView(capture);
            } catch (Exception ignored) {
            }
            capture = null;
            captureLp = null;
        }
    }

    private void handleTap(PointF screenPt, PointF viewPt) {
        final MarkerView v = capture;
        if (v == null || injecting) {
            return;
        }

        // 第一个点：起点
        if (startPoint == null) {
            startPoint = screenPt;
            v.addTap(screenPt, viewPt);
            v.setLabel("起点 (1/2) -> 再点终点");
            setInfo("点终点");
            return;
        }

        // 第二个点：终点，算距离
        final PointF a = startPoint;
        float dist = (float) Math.hypot(screenPt.x - a.x, screenPt.y - a.y);
        float coef = Prefs.getCoefficient(this);
        final long duration = Math.max(60L, Math.min(3000L, Math.round(dist * coef)));

        v.addTap(screenPt, viewPt);
        v.setLabel(((int) dist) + " px -> " + duration + " ms");
        setInfo("跳跃中… " + ((int) dist) + "px/" + duration + "ms");
        startPoint = null;

        // 先让标注停留一下给肉眼确认，再撕掉取点层，延迟注入
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                removeCapture();
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        dispatchPress(a.x, a.y, duration);
                    }
                }, 120L);
            }
        }, 220L);
    }

    // ------------------------------------------------------------ 手势注入

    private void dispatchPress(final float x, final float y, final long durationMs) {
        if (injecting) {
            return;
        }
        injecting = true;
        setPanelTouchable(false);

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, durationMs);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        boolean ok;
        try {
            ok = dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription g) {
                    finishPress();
                }

                @Override
                public void onCancelled(GestureDescription g) {
                    finishPress();
                }
            }, null);
        } catch (Exception e) {
            ok = false;
        }

        if (!ok) {
            injecting = false;
            setPanelTouchable(true);
            setInfo("注入失败");
            toast("手势注入失败：确认无障碍已开启，且未开启 TalkBack");
        }
    }

    private void finishPress() {
        injecting = false;
        setPanelTouchable(true);
        setInfo("就绪");
    }

    private void setPanelTouchable(boolean touchable) {
        if (panel == null || panelLp == null) {
            return;
        }
        if (touchable) {
            panelLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            panelLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        try {
            wm.updateViewLayout(panel, panelLp);
        } catch (Exception ignored) {
        }
    }

    private int overlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ 取点层 + 标注绘制

    private static class Tap {
        final PointF screen;
        final PointF view;

        Tap(PointF s, PointF v) {
            screen = s;
            view = v;
        }
    }

    private class MarkerView extends View {

        private final List<Tap> taps = new ArrayList<Tap>();
        private String label = "";

        private final float d = getResources().getDisplayMetrics().density;
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        MarkerView(Context ctx) {
            super(ctx);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(3f * d);
            strokePaint.setColor(0xFF4DA3FF);

            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(0xFF4DA3FF);

            textPaint.setColor(0xFF4DA3FF);
            textPaint.setTextSize(15f * d);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        }

        void addTap(PointF screen, PointF view) {
            taps.add(new Tap(screen, view));
            invalidate();
        }

        void setLabel(String s) {
            label = s;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if (taps.size() >= 2) {
                PointF p0 = taps.get(0).view;
                PointF p1 = taps.get(1).view;
                canvas.drawLine(p0.x, p0.y, p1.x, p1.y, strokePaint);
            }
            for (Tap t : taps) {
                canvas.drawCircle(t.view.x, t.view.y, 20f * d, strokePaint);
                canvas.drawCircle(t.view.x, t.view.y, 4f * d, fillPaint);
            }
            if (label.length() > 0 && !taps.isEmpty()) {
                PointF p = taps.get(taps.size() - 1).view;
                canvas.drawText(label, p.x + 26f * d, p.y - 26f * d, textPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                // rawX/rawY 是屏幕绝对坐标（用于注入）
                // x/y 是视图坐标（用于画圈标注），两者分开存
                JumpAccessibilityService.this.handleTap(
                        new PointF(e.getRawX(), e.getRawY()),
                        new PointF(e.getX(), e.getY()));
            }
            return true;
        }
    }
}
