package com.kali.statussaver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Opens an AI assistant's website (ChatGPT, Gemini, Claude…) inside the app.
 * You sign in with your own account once; the login stays in Android's WebView
 * cookie store. No API key, and this app never sees your password.
 */
public class AiWebActivity extends Activity {

    static final String EXTRA_ID = "ai_id";
    static final String EXTRA_ATTACH = "ai_attach";
    static final String EXTRA_PROMPT = "ai_prompt";
    private static final int REQ_FILE = 7;

    private WebView web;
    private ProgressBar progress;
    private AiApps.App app;
    private ArrayList<Uri> pending;
    private String prompt;
    private ValueCallback<Uri[]> fileCallback;
    private boolean googleWarned;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = AiApps.find(String.valueOf(getIntent().getStringExtra(EXTRA_ID)));
        if (app == null) app = AiApps.ALL[0];
        pending = getIntent().getParcelableArrayListExtra(EXTRA_ATTACH);
        prompt = getIntent().getStringExtra(EXTRA_PROMPT);
        getWindow().setStatusBarColor(MainActivity.DARK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(MainActivity.DARK);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(6), dp(6), dp(6));
        TextView title = new TextView(this);
        title.setText(app.name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (prompt != null && !prompt.isEmpty()) {
            Button copy = pill("Copy prompt");
            copy.setOnClickListener(v -> {
                AiApps.copy(this, prompt);
                toast("Prompt copied. Long-press the message box and tap Paste.");
            });
            bar.addView(copy, lp());
        }
        Button ext = pill("Browser");
        ext.setOnClickListener(v -> openExternal(web.getUrl() != null ? web.getUrl() : app.web));
        bar.addView(ext, lp());
        root.addView(bar);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setAllowFileAccess(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    if ("accounts.google.com".equals(u.getHost())) googleNotice();
                    return false;
                }
                openSpecial(u.toString());
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                if (pending != null && !pending.isEmpty()) {
                    new AlertDialog.Builder(AiWebActivity.this)
                            .setTitle("Attach your photo?")
                            .setMessage("Attach the " + (pending.size() > 1 ? pending.size() + " photos" : "photo")
                                    + " you sent from Status Studio.")
                            .setPositiveButton("Attach", (d, w) -> {
                                deliverFiles(pending.toArray(new Uri[0]));
                                pending = null;
                            })
                            .setNegativeButton("Choose other", (d, w) -> pickFile(params))
                            .setOnCancelListener(d -> deliverFiles(null))
                            .show();
                    return true;
                }
                pickFile(params);
                return true;
            }
        });
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (url != null && url.startsWith("http")) download(url, mime);
            else toast("This file can't be downloaded here. Long-press the image, or use Browser.");
        });
        web.setOnLongClickListener(v -> {
            WebView.HitTestResult r = web.getHitTestResult();
            int t = r.getType();
            String url = r.getExtra();
            if ((t == WebView.HitTestResult.IMAGE_TYPE || t == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
                    && url != null && url.startsWith("http")) {
                new AlertDialog.Builder(this)
                        .setItems(new String[]{"Save image to Gallery"}, (d, w) -> download(url, null))
                        .show();
                return true;
            }
            return false;
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        if (state != null) web.restoreState(state);
        else web.loadUrl(app.web);

        if (pending != null && !pending.isEmpty()) {
            toast("Sign in if asked, then tap the attach (+ or 📎) button to add your photo."
                    + (prompt != null ? " The prompt is copied, ready to paste." : ""));
        } else {
            toast("Sign in with your own " + app.name + " account. No API key needed.");
        }
    }

    private void pickFile(WebChromeClient.FileChooserParams params) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        String[] types = params == null ? null : params.getAcceptTypes();
        String type = types != null && types.length == 1 && types[0] != null && !types[0].isEmpty()
                && types[0].contains("/") ? types[0] : "*/*";
        i.setType(type);
        if (params != null && params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            startActivityForResult(Intent.createChooser(i, "Choose a file"), REQ_FILE);
        } catch (Exception e) {
            deliverFiles(null);
            toast("No file picker available.");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILE) return;
        Uri[] out = null;
        if (res == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                out = new Uri[data.getClipData().getItemCount()];
                for (int k = 0; k < out.length; k++) out[k] = data.getClipData().getItemAt(k).getUri();
            } else if (data.getData() != null) {
                out = new Uri[]{data.getData()};
            }
        }
        deliverFiles(out);
    }

    private void deliverFiles(Uri[] uris) {
        if (fileCallback != null) fileCallback.onReceiveValue(uris);
        fileCallback = null;
    }

    private void googleNotice() {
        if (googleWarned) return;
        googleWarned = true;
        boolean gemini = "gemini".equals(app.id);
        new AlertDialog.Builder(this)
                .setTitle("Google sign-in")
                .setMessage("Google doesn't allow its sign-in page inside other apps, so it may show an error here.\n\n"
                        + (gemini
                        ? "Install the Gemini app (recommended), and Status Studio will send to it directly. You can also use Gemini in your browser."
                        : "Sign in with your email instead, or continue in your browser."))
                .setPositiveButton(gemini ? "Get Gemini app" : "Open in browser", (d, w) -> {
                    if (gemini) openExternal("market://details?id=" + app.pkg);
                    else openExternal(app.web);
                })
                .setNegativeButton("Stay here", null)
                .show();
    }

    private void openSpecial(String url) {
        try {
            Intent i = url.startsWith("intent:")
                    ? Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.setComponent(null);
            i.setSelector(null);
            startActivity(i);
        } catch (Exception ignored) {
            // No app for this link: stay on the page.
        }
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            if (url.startsWith("market:")) openExternal("https://play.google.com/store/apps/details?id=" + app.pkg);
            else toast("No browser found.");
        }
    }

    private void download(String url, String hintMime) {
        final String ua = web.getSettings().getUserAgentString();
        final String page = web.getUrl() == null ? "" : web.getUrl();
        toast("Saving…");
        io.execute(() -> {
            String msg;
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(url).openConnection();
                con.setInstanceFollowRedirects(true);
                con.setConnectTimeout(15000);
                con.setReadTimeout(60000);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) con.setRequestProperty("Cookie", cookie);
                con.setRequestProperty("User-Agent", ua);
                con.setRequestProperty("Referer", page);
                int code = con.getResponseCode();
                if (code < 200 || code > 299) throw new Exception("server said " + code);
                String mime = con.getContentType();
                if (mime == null || mime.isEmpty()) mime = hintMime;
                mime = mime == null ? "" : mime.split(";")[0].trim().toLowerCase();
                if (!mime.startsWith("image/") && !mime.startsWith("video/")) mime = "image/png";
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                if (ext == null) ext = mime.startsWith("video/") ? "mp4" : "png";
                String name = "AI_" + app.id + "_" + System.currentTimeMillis() + "." + ext;
                try (InputStream in = con.getInputStream()) {
                    StatusRepo.save(this, in, name, mime);
                }
                msg = "Saved to Gallery (" + (mime.startsWith("video/") ? "Movies" : "Pictures") + "/StatusSaver)";
            } catch (Exception e) {
                msg = "Save failed: " + e.getMessage();
            } finally {
                if (con != null) con.disconnect();
            }
            final String text = msg;
            ui.post(() -> toast(text));
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        deliverFiles(null);
        web.destroy();
        super.onDestroy();
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(36));
        p.setMargins(dp(4), 0, dp(4), 0);
        return p;
    }

    private Button pill(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(MainActivity.DARK);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        g.setColor(Color.WHITE);
        b.setBackground(g);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
