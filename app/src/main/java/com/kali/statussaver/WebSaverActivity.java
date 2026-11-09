package com.kali.statussaver;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A plain browser where you log in to Instagram / Facebook yourself.
 * The Save button saves the biggest photo or video visible on screen —
 * the same thing a screenshot would capture, but at full quality.
 * No password is ever seen or stored by this app; login cookies stay inside Android's WebView.
 */
public class WebSaverActivity extends Activity {

    private static final String INSTAGRAM = "https://www.instagram.com/";
    private static final String FACEBOOK = "https://m.facebook.com/";

    // Finds the visible <video>/<img> elements, biggest first.
    private static final String GRAB_JS =
            "(function(){"
          + "function big(i){var s=i.currentSrc||i.src;if(i.srcset){var b=0;"
          + "i.srcset.split(',').forEach(function(p){var q=p.trim().split(/\\s+/);var w=parseInt(q[1])||0;"
          + "if(w>b){b=w;s=q[0];}});}return s;}"
          + "var out=[],H=window.innerHeight,W=window.innerWidth;"
          + "function vis(r){return r.width>80&&r.height>80&&r.bottom>0&&r.top<H&&r.right>0&&r.left<W;}"
          + "document.querySelectorAll('video').forEach(function(v){var r=v.getBoundingClientRect();"
          + "if(vis(r)){var s=v.currentSrc||v.src;if(!s){var so=v.querySelector('source');if(so)s=so.src;}"
          + "out.push({t:'v',s:s||'',a:r.width*r.height});}});"
          + "document.querySelectorAll('img').forEach(function(i){var r=i.getBoundingClientRect();"
          + "if(vis(r))out.push({t:'i',s:big(i),a:r.width*r.height});});"
          + "out.sort(function(a,b){return b.a-a.a;});"
          + "return JSON.stringify(out.slice(0,6));"
          + "})()";

    private WebView web;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(MainActivity.GREEN);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(MainActivity.GREEN);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        Button ig = pill("Instagram", Color.WHITE, MainActivity.GREEN);
        Button fb = pill("Facebook", Color.WHITE, MainActivity.GREEN);
        Button save = pill("⬇ Save", 0xFF075E54, Color.WHITE);
        ig.setOnClickListener(v -> web.loadUrl(INSTAGRAM));
        fb.setOnClickListener(v -> web.loadUrl(FACEBOOK));
        save.setOnClickListener(v -> grabVisible());
        bar.addView(ig, lp());
        bar.addView(fb, lp());
        bar.addView(save, lp());
        root.addView(bar);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String scheme = req.getUrl().getScheme();
                // Stay in this browser; block "open in app" redirects (intent://, instagram://, fb://).
                return !("http".equals(scheme) || "https".equals(scheme));
            }
        });
        // Long-press on a normal picture also saves it.
        web.setOnLongClickListener(v -> {
            WebView.HitTestResult r = web.getHitTestResult();
            int t = r.getType();
            String url = r.getExtra();
            if ((t == WebView.HitTestResult.IMAGE_TYPE || t == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
                    && url != null && url.startsWith("http")) {
                download(url, false);
                return true;
            }
            return false;
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        if (state != null) web.restoreState(state);
        else web.loadUrl(INSTAGRAM);

        toast("Log in, open a story or profile picture, then tap Save.");
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    private void grabVisible() {
        web.evaluateJavascript(GRAB_JS, result -> {
            try {
                Object v = new JSONTokener(result).nextValue();
                if (!(v instanceof String)) {
                    toast("Nothing to save on this screen.");
                    return;
                }
                JSONArray arr = new JSONArray((String) v);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String src = o.optString("s", "");
                    boolean isVideo = "v".equals(o.optString("t"));
                    if (src.startsWith("http")) {
                        download(src, isVideo);
                        return;
                    }
                    if (isVideo && src.startsWith("blob:")) {
                        toast("This video streams in pieces and can't be downloaded. "
                                + "Use your phone's screen recorder for it.");
                        return;
                    }
                }
                toast("Nothing to save on this screen.");
            } catch (Exception e) {
                toast("Could not read this page.");
            }
        });
    }

    private void download(String url, boolean isVideo) {
        final String ua = web.getSettings().getUserAgentString();
        final String page = web.getUrl() == null ? "" : web.getUrl();
        final String host = Uri.parse(page).getHost() == null ? "" : Uri.parse(page).getHost();
        toast("Saving…");
        io.execute(() -> {
            String msg;
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(url).openConnection();
                con.setInstanceFollowRedirects(true);
                con.setConnectTimeout(15000);
                con.setReadTimeout(30000);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) con.setRequestProperty("Cookie", cookie);
                con.setRequestProperty("User-Agent", ua);
                con.setRequestProperty("Referer", page);
                int code = con.getResponseCode();
                if (code < 200 || code > 299) throw new Exception("server said " + code);

                String mime = con.getContentType();
                mime = mime == null ? "" : mime.split(";")[0].trim().toLowerCase();
                if (!mime.startsWith("image/") && !mime.startsWith("video/")) {
                    mime = isVideo ? "video/mp4" : "image/jpeg";
                }
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                if (ext == null) ext = mime.startsWith("video/") ? "mp4" : "jpg";
                String prefix = host.contains("instagram") ? "IG_"
                        : host.contains("facebook") ? "FB_" : "WEB_";
                String name = prefix + System.currentTimeMillis() + "." + ext;

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
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(40), 1);
        p.setMargins(dp(4), 0, dp(4), 0);
        return p;
    }

    private Button pill(String text, int bg, int fg) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setStateListAnimator(null);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(20));
        g.setColor(bg);
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
