package com.example.jumphelper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 引导页：三步授权（无障碍 / 悬浮窗 / 面板）。
 * 全部用代码布局，不依赖任何 XML 布局和 androidx。
 */
public class MainActivity extends Activity {

    private TextView statusAcc;
    private TextView statusOverlay;
    private Button btnPanel;
    private float d = 1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(20), px(24), px(20), px(24));

        root.addView(text("跳一跳助手", 22f, true));

        // ---- ① 无障碍 ----
        root.addView(text("① 无障碍服务（必需，用于注入手势）", 15f, true));
        statusAcc = text("状态：未开启", 14f, false);
        root.addView(statusAcc);
        root.addView(button("打开无障碍设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception e) {
                    toast("请手动进入 设置 -> 无障碍 开启本服务");
                }
            }
        }));

        // ---- ② 悬浮窗 ----
        root.addView(text("② 悬浮窗权限（必需，用于显示面板）", 15f, true));
        statusOverlay = text("状态：未授权", 14f, false);
        root.addView(statusOverlay);
        root.addView(button("授予悬浮窗权限", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    toast("请手动进入 设置 -> 应用 -> 显示在其他应用上层");
                }
            }
        }));

        // ---- ③ 面板 ----
        root.addView(text("③ 悬浮面板", 15f, true));
        btnPanel = button("显示悬浮面板", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JumpAccessibilityService svc = JumpAccessibilityService.getInstance();
                if (svc == null) {
                    toast("请先开启无障碍服务");
                } else {
                    svc.togglePanel();
                    refresh();
                }
            }
        });
        root.addView(btnPanel);

        // ---- 说明 ----
        root.addView(text("使用方法", 15f, true));
        root.addView(text(
                "1. 打开微信 -> 跳一跳，进入游戏画面。\n"
                        + "2. 点悬浮面板的「测距」。\n"
                        + "3. 点一下棋子中心（起点），再点一下目标方块中心（终点）。\n"
                        + "4. 自动算距离并注入长按，完成一次跳跃。\n\n"
                        + "校准：跳短了就点「+」增大系数，跳远了就点「-」减小；\n"
                        + "长按面板上的系数数字可恢复默认值。", 13f, false));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean accOn = JumpAccessibilityService.getInstance() != null;
        statusAcc.setText(accOn ? "状态：已开启" : "状态：未开启");
        statusAcc.setTextColor(accOn ? 0xFF11A34A : 0xFFD32F2F);

        boolean ov = Settings.canDrawOverlays(this);
        statusOverlay.setText(ov ? "状态：已授权" : "状态：未授权");
        statusOverlay.setTextColor(ov ? 0xFF11A34A : 0xFFD32F2F);

        JumpAccessibilityService svc = JumpAccessibilityService.getInstance();
        btnPanel.setText(svc != null && svc.isPanelShown() ? "隐藏悬浮面板" : "显示悬浮面板");
    }

    private TextView text(String s, float size, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(size);
        tv.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tv.setPadding(0, px(8), 0, px(4));
        return tv;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        b.setOnClickListener(l);
        return b;
    }

    private int px(int v) {
        return (int) (v * d + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
