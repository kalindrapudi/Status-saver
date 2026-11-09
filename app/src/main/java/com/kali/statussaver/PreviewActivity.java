package com.kali.statussaver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PreviewActivity extends Activity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        final Uri uri = Uri.parse(getIntent().getStringExtra("uri"));
        final String name = getIntent().getStringExtra("name");
        final String mime = getIntent().getStringExtra("mime");
        final boolean saved = getIntent().getBooleanExtra("saved", false);
        final boolean video = mime != null && mime.startsWith("video/");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);

        if (video) {
            VideoView vv = new VideoView(this);
            MediaController mc = new MediaController(this);
            mc.setAnchorView(vv);
            vv.setMediaController(mc);
            vv.setVideoURI(uri);
            vv.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                vv.start();
            });
            vv.setOnErrorListener((mp, what, extra) -> {
                toast("This video can't be played here, but you can still save it.");
                return true;
            });
            root.addView(vv, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        } else {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            final int w = getResources().getDisplayMetrics().widthPixels;
            io.execute(() -> {
                Bitmap b = StatusRepo.decode(this, uri, w);
                ui.post(() -> {
                    if (b != null) iv.setImageBitmap(b);
                    else toast("Could not open this image.");
                });
            });
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(0, 0, 0, dp(24));

        if (!saved) {
            Button save = pill("Save");
            save.setOnClickListener(v -> save(uri, name, mime));
            actions.addView(save, lp());
        }
        Button share = pill("Share");
        share.setOnClickListener(v -> share(uri, mime));
        actions.addView(share, lp());

        Button ai = pill("✧ AI");
        ai.setOnClickListener(v -> aiMenu(uri, mime, video));
        actions.addView(ai, lp());

        root.addView(actions, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
    }

    private void save(Uri uri, String name, String mime) {
        io.execute(() -> {
            String msg;
            try {
                if (StatusRepo.alreadySaved(this, name, mime)) {
                    msg = "Already saved";
                } else {
                    StatusRepo.save(this, uri, name, mime);
                    msg = mime.startsWith("video/")
                            ? "Saved to Movies/StatusSaver"
                            : "Saved to Pictures/StatusSaver";
                }
            } catch (Exception e) {
                msg = "Save failed: " + e.getMessage();
            }
            final String text = msg;
            ui.post(() -> toast(text));
        });
    }

    private void share(Uri uri, String mime) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.setClipData(ClipData.newRawUri("", uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "Share"));
        } catch (Exception e) {
            toast("Sharing failed. Save it first, then share from Gallery.");
        }
    }

    private void aiMenu(Uri uri, String mime, boolean video) {
        if (video) {
            AiApps.askDialog(this, uri, mime);
            return;
        }
        new AlertDialog.Builder(this)
                .setItems(new String[]{"Ask an AI app (ChatGPT, Gemini, Claude…)", "Restyle in Muse Studio"}, (d, w) -> {
                    if (w == 0) {
                        AiApps.askDialog(this, uri, mime);
                    } else {
                        Intent i = new Intent(this, MuseActivity.class);
                        i.setAction(Intent.ACTION_SEND);
                        i.setType(mime);
                        i.putExtra(Intent.EXTRA_STREAM, uri);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(i);
                    }
                })
                .show();
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(100), dp(46));
        p.setMargins(dp(6), 0, dp(6), 0);
        return p;
    }

    private Button pill(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setStateListAnimator(null);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(23));
        g.setColor(MainActivity.GREEN);
        b.setBackground(g);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
