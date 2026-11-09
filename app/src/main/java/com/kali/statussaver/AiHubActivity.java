package com.kali.statussaver;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** One place for every AI feature: Muse Studio plus your AI apps (your own accounts, no API keys). */
public class AiHubActivity extends Activity {

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(MainActivity.DARK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(0xFFF3F5F4);

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(MainActivity.DARK);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView title = text("✧ AI Hub", 20, Color.WHITE, true);
        bar.addView(title);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(12), dp(12), dp(24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build(); // refresh "installed" labels after the user installs an app
    }

    private void build() {
        list.removeAllViews();

        LinearLayout muse = card(0xFF1F2A44);
        muse.addView(text("Muse Studio", 18, Color.WHITE, true));
        muse.addView(text("Restyle your photo with any look: pick a style, describe it, then create it with "
                + "ChatGPT, Gemini and other AI apps. No API key needed (keys are optional).", 14, 0xFFD9DEEA, false));
        TextView go = text("Open studio  →", 15, 0xFFFFD27A, true);
        go.setPadding(0, dp(10), 0, 0);
        muse.addView(go);
        muse.setOnClickListener(v -> startActivity(new Intent(this, MuseActivity.class)));
        list.addView(muse);

        TextView h = text("Your AI apps", 16, 0xFF222222, true);
        h.setPadding(dp(4), dp(18), dp(4), dp(2));
        list.addView(h);
        TextView sub = text("Use your own ChatGPT, Gemini, Claude and other accounts, free or paid, with no API key. "
                + "Installed apps open directly. Otherwise the website opens here and you sign in once.",
                13, 0xFF555555, false);
        sub.setPadding(dp(4), 0, dp(4), dp(8));
        list.addView(sub);

        for (AiApps.App a : AiApps.ALL) {
            boolean inst = AiApps.installed(this, a);
            LinearLayout row = card(Color.WHITE);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView badge = text(a.name.substring(0, 1), 18, Color.WHITE, true);
            badge.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(MainActivity.GREEN);
            badge.setBackground(bg);
            row.addView(badge, new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, dp(8), 0);
            col.addView(text(a.name, 16, 0xFF111111, true));
            col.addView(text(a.note, 13, 0xFF666666, false));
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            TextView state = text(inst ? "App" : "Web", 12, inst ? MainActivity.GREEN : 0xFF777777, true);
            row.addView(state);
            row.setOnClickListener(v -> AiApps.open(this, a));
            row.setOnLongClickListener(v -> {
                AiApps.openWeb(this, a, null, null);
                return true;
            });
            list.addView(row);
        }

        TextView tip = text("Tips:\n• In a status preview, tap ✧ AI to ask about a photo or video, or to get a caption.\n"
                + "• Long-press an app above to open its website even when the app is installed.\n"
                + "• Pictures you create in an AI app can be shared back to Muse Studio.",
                13, 0xFF555555, false);
        tip.setPadding(dp(4), dp(14), dp(4), 0);
        list.addView(tip);
    }

    private LinearLayout card(int color) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(color);
        c.setBackground(g);
        c.setClickable(true);
        c.setForeground(getDrawable(android.R.drawable.list_selector_background));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
