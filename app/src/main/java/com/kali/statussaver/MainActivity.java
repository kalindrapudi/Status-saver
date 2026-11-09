package com.kali.statussaver;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final int GREEN = 0xFF128C7E;
    static final int DARK = 0xFF075E54;
    private static final String PREFS = "statussaver";
    private static final String[] TAB_NAMES = {"WhatsApp", "WA Business", "Saved"};
    private static final String[] KEYS = {"tree_wa", "tree_wb", null};
    private static final String[] INITIAL = {
            "primary:Android/media/com.whatsapp/WhatsApp/Media/.Statuses",
            "primary:Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses",
            null
    };
    private static final int REQ_BASE = 100;

    private int tab = 0;
    private int loadToken = 0;
    private final Button[] tabButtons = new Button[3];
    private GridView grid;
    private LinearLayout emptyBox;
    private TextView emptyText;
    private Button grantBtn;
    private final List<MediaItem> items = new ArrayList<>();
    private MediaAdapter adapter;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(GREEN);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(0xFFF3F5F4);

        // Top bar
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(GREEN);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(10), dp(10));
        TextView title = new TextView(this);
        title.setText("Status Studio");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button web = pill("Insta / FB", Color.WHITE, GREEN);
        web.setOnClickListener(v -> startActivity(new Intent(this, WebSaverActivity.class)));
        bar.addView(web);
        Button ai = pill("✧ AI", 0xFFFFD27A, 0xFF1F2A44);
        ai.setOnClickListener(v -> startActivity(new Intent(this, AiHubActivity.class)));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.leftMargin = dp(8);
        bar.addView(ai, alp);
        root.addView(bar);

        // Tabs
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(8), dp(8), dp(8), dp(4));
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            Button b = pill(TAB_NAMES[i], GREEN, Color.WHITE);
            b.setOnClickListener(v -> {
                tab = idx;
                loadTab();
            });
            if (i < 2) {
                b.setOnLongClickListener(v -> {
                    askFolder(idx);
                    return true;
                });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1);
            lp.setMargins(dp(4), 0, dp(4), 0);
            tabs.addView(b, lp);
            tabButtons[i] = b;
        }
        root.addView(tabs);

        FrameLayout body = new FrameLayout(this);

        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(dp(4));
        grid.setVerticalSpacing(dp(4));
        grid.setPadding(dp(6), dp(6), dp(6), dp(6));
        grid.setClipToPadding(false);
        adapter = new MediaAdapter(this);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> openPreview(items.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            if (tab < 2) quickSave(items.get(pos));
            return true;
        });
        body.addView(grid);

        emptyBox = new LinearLayout(this);
        emptyBox.setOrientation(LinearLayout.VERTICAL);
        emptyBox.setGravity(Gravity.CENTER);
        emptyBox.setPadding(dp(32), dp(32), dp(32), dp(32));
        emptyText = new TextView(this);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        emptyText.setTextColor(0xFF444444);
        emptyBox.addView(emptyText);
        grantBtn = pill("Allow folder access", Color.WHITE, GREEN);
        grantBtn.setOnClickListener(v -> askFolder(tab));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, dp(46));
        glp.topMargin = dp(18);
        emptyBox.addView(grantBtn, glp);
        body.addView(emptyBox);

        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadTab();
    }

    private void loadTab() {
        for (int i = 0; i < 3; i++) styleTab(tabButtons[i], i == tab);
        final int token = ++loadToken;
        final int t = tab;
        final Uri tree = t < 2 ? getTree(t) : null;
        if (t < 2 && tree == null) {
            showEmpty("Give Status Studio one-time access to the " + TAB_NAMES[t]
                    + " statuses folder.\n\nOn the next screen just tap \"Use this folder\" and then \"Allow\".\n\n"
                    + "Tip: open " + TAB_NAMES[t] + " and view some statuses first.", true);
            return;
        }
        io.execute(() -> {
            List<MediaItem> list;
            try {
                list = t < 2 ? StatusRepo.listTree(this, tree) : StatusRepo.listSaved(this);
            } catch (Exception e) {
                list = null;
            }
            final List<MediaItem> result = list;
            ui.post(() -> {
                if (token != loadToken) return;
                items.clear();
                if (result == null) {
                    adapter.notifyDataSetChanged();
                    showEmpty("Could not read this folder. Tap below to choose it again.", true);
                    return;
                }
                items.addAll(result);
                adapter.notifyDataSetChanged();
                if (items.isEmpty()) {
                    showEmpty(t < 2
                            ? "No statuses yet.\nOpen " + TAB_NAMES[t] + ", watch some statuses, then come back."
                            : "Nothing saved yet.\nTap a status and press Save, or long-press it to save quickly.", false);
                } else {
                    emptyBox.setVisibility(View.GONE);
                    grid.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void showEmpty(String msg, boolean showGrant) {
        items.clear();
        adapter.notifyDataSetChanged();
        grid.setVisibility(View.GONE);
        emptyBox.setVisibility(View.VISIBLE);
        emptyText.setText(msg);
        grantBtn.setVisibility(showGrant ? View.VISIBLE : View.GONE);
    }

    private Uri getTree(int t) {
        String s = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEYS[t], null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isReadPermission()) return u;
        }
        return null;
    }

    private void askFolder(int t) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", INITIAL[t]));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_BASE + t);
        } catch (Exception e) {
            toast("Your phone has no folder picker available.");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        int t = req - REQ_BASE;
        if (t < 0 || t > 1 || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            toast("Permission was not granted. Please try again.");
            return;
        }
        SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        ed.putString(KEYS[t], uri.toString()).apply();
        if (!Uri.decode(uri.toString()).contains(".Statuses")) {
            toast("That folder isn't .Statuses, so it may show other media too.");
        }
        tab = t;
        loadTab();
    }

    private void openPreview(MediaItem m) {
        Intent i = new Intent(this, PreviewActivity.class);
        i.putExtra("uri", m.uri.toString());
        i.putExtra("name", m.name);
        i.putExtra("mime", m.mime);
        i.putExtra("saved", tab == 2);
        startActivity(i);
    }

    private void quickSave(MediaItem m) {
        io.execute(() -> {
            String msg;
            try {
                if (StatusRepo.alreadySaved(this, m.name, m.mime)) {
                    msg = "Already saved";
                } else {
                    StatusRepo.save(this, m.uri, m.name, m.mime);
                    msg = "Saved to Gallery";
                }
            } catch (Exception e) {
                msg = "Save failed: " + e.getMessage();
            }
            final String text = msg;
            ui.post(() -> toast(text));
        });
    }

    // ---------- UI helpers ----------

    private void styleTab(Button b, boolean active) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(20));
        g.setColor(active ? GREEN : Color.WHITE);
        g.setStroke(dp(1), GREEN);
        b.setBackground(g);
        b.setTextColor(active ? Color.WHITE : GREEN);
    }

    Button pill(String text, int bg, int fg) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(20));
        g.setColor(bg);
        b.setBackground(g);
        return b;
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------- Grid adapter ----------

    static class SquareImageView extends ImageView {
        SquareImageView(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, w);
        }
    }

    class MediaAdapter extends BaseAdapter {
        private final ExecutorService pool = Executors.newFixedThreadPool(3);
        private final LruCache<String, Bitmap> cache;
        private final int px;

        MediaAdapter(Context c) {
            int kb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
            cache = new LruCache<String, Bitmap>(kb) {
                @Override
                protected int sizeOf(String k, Bitmap b) {
                    return b.getByteCount() / 1024;
                }
            };
            px = c.getResources().getDisplayMetrics().widthPixels / 3;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public MediaItem getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            FrameLayout f;
            ImageView iv;
            TextView play;
            if (convert == null) {
                f = new FrameLayout(MainActivity.this);
                iv = new SquareImageView(MainActivity.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(0xFFDDE3E1);
                f.addView(iv, new FrameLayout.LayoutParams(-1, -2));
                play = new TextView(MainActivity.this);
                play.setText("▶");
                play.setTextColor(Color.WHITE);
                play.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
                play.setShadowLayer(6, 0, 0, Color.BLACK);
                f.addView(play, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
                f.setTag(new View[]{iv, play});
            } else {
                f = (FrameLayout) convert;
                View[] h = (View[]) f.getTag();
                iv = (ImageView) h[0];
                play = (TextView) h[1];
            }
            MediaItem m = getItem(pos);
            play.setVisibility(m.isVideo() ? View.VISIBLE : View.GONE);
            final String key = m.uri.toString();
            iv.setTag(key);
            Bitmap cached = cache.get(key);
            if (cached != null) {
                iv.setImageBitmap(cached);
            } else {
                iv.setImageDrawable(null);
                pool.execute(() -> {
                    Bitmap b = StatusRepo.thumbnail(MainActivity.this, m, px);
                    if (b != null) cache.put(key, b);
                    ui.post(() -> {
                        if (key.equals(iv.getTag()) && b != null) iv.setImageBitmap(b);
                    });
                });
            }
            return f;
        }
    }
}
