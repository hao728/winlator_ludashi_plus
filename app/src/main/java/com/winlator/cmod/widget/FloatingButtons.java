package com.winlator.cmod.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.ProcessHelper;

public class FloatingButtons implements View.OnClickListener {
    private XServerDisplayActivity activity;
    private TextView btnPause;
    private TextView btnTouch;
    private boolean paused = false;

    public FloatingButtons() {}

    private static int dp(Context ctx, int value) {
        float density = ctx.getResources().getDisplayMetrics().density;
        return (int)(value * density + 0.5f);
    }

    private TextView makeButton(Context ctx, String tag, String text) {
        TextView btn = new TextView(ctx);
        btn.setText(text);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x801F1F1F);
        bg.setCornerRadius(dp(ctx, 8));
        bg.setStroke(dp(ctx, 1), 0x55ffffff);
        btn.setBackground(bg);

        btn.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8));
        btn.setTextSize(12f);
        btn.setTextColor(0xFFFFFFFF);
        btn.setGravity(Gravity.CENTER);
        btn.setTag(tag);
        btn.setOnClickListener(this);
        return btn;
    }

    public static void setup(XServerDisplayActivity activity) {
        if (activity == null) return;

        FloatingButtons fb = new FloatingButtons();
        fb.activity = activity;

        View container = activity.findViewById(com.winlator.cmod.R.id.FLXServerDisplay);
        if (!(container instanceof FrameLayout)) return;
        FrameLayout frame = (FrameLayout) container;

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.END
        );
        lp.topMargin = dp(activity, 8);
        lp.rightMargin = dp(activity, 8);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);

        // 按钮1: 暂停/继续
        fb.btnPause = fb.makeButton(activity, "pause", "暂停");
        layout.addView(fb.btnPause);

        // 按钮2: 触屏/指针模式
        boolean touchMode = prefs.getBoolean("touchscreen_toggle", false);
        fb.btnTouch = fb.makeButton(activity, "touch", touchMode ? "触屏模式" : "指针模式");
        LinearLayout.LayoutParams touchLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        touchLp.topMargin = dp(activity, 8);
        fb.btnTouch.setLayoutParams(touchLp);
        layout.addView(fb.btnTouch);

        // 按钮3: 键盘
        TextView btnKeyboard = fb.makeButton(activity, "keyboard", "键盘");
        LinearLayout.LayoutParams kbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        kbLp.topMargin = dp(activity, 8);
        btnKeyboard.setLayoutParams(kbLp);
        layout.addView(btnKeyboard);

        frame.addView(layout, lp);
    }

    @Override
    public void onClick(View v) {
        try {
            if (activity == null) return;
            String tag = (String) v.getTag();

            if ("touch".equals(tag)) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
                boolean current = prefs.getBoolean("touchscreen_toggle", false);
                boolean next = !current;
                prefs.edit().putBoolean("touchscreen_toggle", next).apply();
                btnTouch.setText(next ? "触屏模式" : "指针模式");
            } else if ("keyboard".equals(tag)) {
                AppUtils.showKeyboard(activity);
            } else if ("pause".equals(tag)) {
                paused = !paused;
                if (paused) {
                    ProcessHelper.pauseAllWineProcesses();
                    btnPause.setText("继续");
                } else {
                    ProcessHelper.resumeAllWineProcesses();
                    btnPause.setText("暂停");
                }
            }
        } catch (Throwable ignored) {}
    }
}
