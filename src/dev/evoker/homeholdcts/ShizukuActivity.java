// SPDX-License-Identifier: GPL-3.0-only
package dev.evoker.homeholdcts;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import rikka.shizuku.Shizuku;

public final class ShizukuActivity extends Activity {
    private TextView status;
    private TextView connection;
    private Switch returnToOrigin;
    private Switch powerEnabled;
    private Switch hotwordEnabled;
    private int background, surface, ink, muted, accent, onAccent, tint;

    private final Shizuku.OnRequestPermissionResultListener permission = (code, result) -> {
        if (code == 1) runOnUiThread(() -> {
            if (result == PackageManager.PERMISSION_GRANTED) start();
            else show("未获得 Shizuku 授权。");
        });
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Shizuku.addRequestPermissionResultListener(permission);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        background = Color.parseColor(dark ? "#111813" : "#F2F6EF");
        surface = Color.parseColor(dark ? "#1B2720" : "#FFFFFF");
        ink = Color.parseColor(dark ? "#EDF5EC" : "#15291D");
        muted = Color.parseColor(dark ? "#B3C6B8" : "#4D6255");
        accent = Color.parseColor(dark ? "#A8E4B7" : "#245B3E");
        onAccent = Color.parseColor(dark ? "#122C1C" : "#FFFFFF");
        tint = Color.parseColor(dark ? "#25392D" : "#E2F0E4");

        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController bars = getWindow().getDecorView().getWindowInsetsController();
        if (bars != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            bars.setSystemBarsAppearance(dark ? 0 : mask, mask);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(background);
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        LinearLayout page = column();
        page.setPadding(dp(20), dp(22), dp(20), dp(24));
        int width = getResources().getConfiguration().screenWidthDp;
        scroll.addView(page, new android.widget.FrameLayout.LayoutParams(
                width > 600 ? dp(600) : -1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this);
        mark.setImageResource(getResources().getIdentifier("ic_colorgpt", "drawable", getPackageName()));
        heading.addView(mark, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout name = column();
        name.setPadding(dp(12), 0, 0, 0);
        name.addView(label("ColorGPT", 30, ink, true));
        name.addView(label("ColorOS 语音转接", 14, muted, false));
        heading.addView(name);
        add(page, heading, 0);

        LinearLayout monitor = card();
        monitor.addView(label("监听状态", 19, ink, true));
        connection = label("", 13, accent, true);
        add(monitor, connection, 6);
        status = label("", 14, muted, false);
        status.setTextIsSelectable(true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(monitor, status, 8);
        add(monitor, action("开始监听 / 应用设置", true, this::start), 14);
        LinearLayout controls = new LinearLayout(this);
        controls.addView(action("刷新状态", false, this::refresh), new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams stop = new LinearLayout.LayoutParams(0, dp(48), 1);
        stop.leftMargin = dp(8);
        controls.addView(action("停止监听", false, () -> {
            if (WatchService.stopListener()) stopService(new Intent(this, WatchService.class));
            show(WatchService.latest);
        }), stop);
        add(monitor, controls, 8);
        add(page, monitor, 22);

        LinearLayout options = card();
        options.addView(label("唤醒与对话", 19, ink, true));
        hotwordEnabled = setting(options, "小布小布唤醒", "开启后将系统热词转接到 ChatGPT。", WatchService.HOTWORD_ENABLED, true);
        powerEnabled = setting(options, "长按电源键", "系统长按电源键需设为唤起小布。", WatchService.POWER_ENABLED, WatchService.DEFAULT_POWER_ENABLED);
        returnToOrigin = setting(options, "自动返回原应用", "语音就绪后返回。请先在 ChatGPT 开启「后台对话」。", WatchService.RETURN_TO_ORIGIN, false);
        add(page, options, 12);

        TextView note = label("通过 Shizuku 监听系统事件。转接会结束小布当前会话，小布界面可能短暂出现。划掉应用后，Shizuku 可能继续监听。要恢复原生小布，请回到这里点「停止监听」。", 13, muted, false);
        note.setPadding(dp(4), 0, dp(4), 0);
        add(page, note, 16);
        setContentView(scroll);
        scroll.requestApplyInsets();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        status.postDelayed(this::refresh, 500);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout card() {
        LinearLayout layout = column();
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        layout.setBackground(rounded(surface, 22));
        return layout;
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }
    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    private void add(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(view, params);
    }
    private Button action(String title, boolean primary, Runnable onClick) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextColor(primary ? onAccent : accent);
        button.setMinHeight(dp(48));
        button.setAllCaps(false);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x44FFFFFF : 0x2274A585),
                rounded(primary ? accent : tint, 14), null));
        button.setOnClickListener(v -> onClick.run());
        return button;
    }
    private Switch setting(LinearLayout card, String title, String description, String key, boolean defaultEnabled) {
        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextColor(ink);
        toggle.setTextSize(16);
        toggle.setMinHeight(dp(48));
        toggle.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{accent, muted}));
        toggle.setTrackTintList(ColorStateList.valueOf(tint));
        toggle.setChecked(getPreferences(MODE_PRIVATE).getBoolean(key, defaultEnabled));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean(key, checked).apply();
            show("设置已保存。点「开始监听 / 应用设置」使其生效。");
        });
        add(card, toggle, 8);
        add(card, label(description, 13, muted, false), 0);
        return toggle;
    }
    private void start() {
        try {
            if (!Shizuku.pingBinder()) { show("Shizuku 尚未连接，请先启动 Shizuku。"); return; }
            if (!ShellWatcher.supportsUid(Shizuku.getUid())) { show("拒绝运行：当前 Shizuku UID="+Shizuku.getUid()+"。支持 Root（UID 0）或 ADB（UID 2000）模式。"); return; }
            if (Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) { Shizuku.requestPermission(1); return; }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},2); return;
            }
            startForegroundService(listenIntent()); show("正在启动监听…"); status.postDelayed(this::refresh, 900);
        } catch (Exception error) { show("启动失败："+error.getClass().getSimpleName()); }
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==2) {
            // A notification denial does not authorize broad settings changes or prevent a legal FGS.
            startForegroundService(listenIntent());
            show("正在启动监听…"); status.postDelayed(this::refresh, 900);
        }
    }
    private Intent listenIntent() {
        return new Intent(this,WatchService.class).setAction("START")
                .putExtra(WatchService.RETURN_TO_ORIGIN,returnToOrigin.isChecked())
                .putExtra(WatchService.POWER_ENABLED,powerEnabled.isChecked())
                .putExtra(WatchService.HOTWORD_ENABLED,hotwordEnabled.isChecked());
    }
    private void refresh() {
        try {
            int uid = Shizuku.pingBinder() ? Shizuku.getUid() : -1;
            connection.setText(uid == 0 ? "SHIZUKU · ROOT" : uid == 2000 ? "SHIZUKU · ADB" : "SHIZUKU · 未连接");
            show(WatchService.readStatus());
        } catch (Exception error) {
            connection.setText("SHIZUKU · 未就绪");
            show(WatchService.latest);
        }
    }
    private void show(String text) { status.setText(text); }
    @Override protected void onDestroy() { Shizuku.removeRequestPermissionResultListener(permission); super.onDestroy(); }
}
