package com.kali.statussaver;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Muse Studio: restyle your photos with AI. The screen is a local web page
 * (assets/muse) served from a private https origin, so its photo library lives
 * in this app's own storage. window.MuseAndroid gives it gallery saving,
 * encrypted key storage, share-in, and hand-off to AI apps (no API key).
 */
public class MuseActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/muse/index.html#create";
    private static final int REQ_FILE = 11;
    private static final long MAX_SHARE = 25L * 1024 * 1024;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private volatile String sharedJson = "[]";
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFFF6F4EE);
        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        web = new WebView(this);
        web.setFitsSystemWindows(true);
        web.setBackgroundColor(Color.parseColor("#f6f4ee"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "MuseAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath() == null ? "" : u.getPath();
                if (!path.startsWith("/muse/") || path.contains("..")) return notFound();
                try {
                    InputStream in = getAssets().open(path.substring(1));
                    return new WebResourceResponse(mime(path), "utf-8", in);
                } catch (Exception e) {
                    return notFound();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                openExternal(u.toString());
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/jpeg", "image/png", "image/webp"});
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(i, REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    cb.onReceiveValue(null);
                    toast("No photo picker available.");
                }
                return true;
            }
        });
        setContentView(web);

        readShared(getIntent());
        if (state != null) web.restoreState(state);
        else web.loadUrl(START);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        readShared(intent);
    }

    /** Images shared to "Muse Studio" (or sent from the status preview) become Muse photos. */
    private void readShared(Intent intent) {
        if (intent == null) return;
        List<Uri> uris = new ArrayList<>();
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        if (uris.isEmpty()) return;
        io.execute(() -> {
            JSONArray arr = new JSONArray();
            for (Uri u : uris.subList(0, Math.min(uris.size(), 10))) {
                try {
                    String mime = getContentResolver().getType(u);
                    if (mime == null || !mime.startsWith("image/")) continue;
                    byte[] data = readAll(getContentResolver().openInputStream(u));
                    arr.put(new JSONObject()
                            .put("name", String.valueOf(u.getLastPathSegment()))
                            .put("data", "data:" + mime + ";base64," + Base64.encodeToString(data, Base64.NO_WRAP)));
                } catch (Exception ignored) {
                }
            }
            sharedJson = arr.toString();
            if (arr.length() == 0) {
                runOnUiThread(() -> toast("Only photos can be used in Muse."));
            } else {
                // If the page is still loading, its own startup picks the images up instead.
                runOnUiThread(() -> web.evaluateJavascript("window.museOnShare && window.museOnShare()", null));
            }
        });
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) throw new Exception("unreadable");
        try (InputStream src = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            long total = 0;
            while ((n = src.read(buf)) > 0) {
                total += n;
                if (total > MAX_SHARE) throw new Exception("too big");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILE || fileCallback == null) return;
        Uri[] out = null;
        if (res == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                out = new Uri[data.getClipData().getItemCount()];
                for (int k = 0; k < out.length; k++) out[k] = data.getClipData().getItemAt(k).getUri();
            } else if (data.getData() != null) {
                out = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(out);
        fileCallback = null;
    }

    private static String mime(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".webmanifest") || path.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private static WebResourceResponse notFound() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8",
                new ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        return r;
    }

    private void openExternal(String url) {
        try {
            Uri u = Uri.parse(url);
            if (!"http".equals(u.getScheme()) && !"https".equals(u.getScheme())) return;
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception e) {
            toast("No browser found.");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        web.destroy();
        super.onDestroy();
    }

    /** Methods the Muse page can call as window.MuseAndroid.*. */
    private class Bridge {

        @JavascriptInterface
        public String getSecret(String name) {
            return SecretStore.get(MuseActivity.this, "key." + name);
        }

        @JavascriptInterface
        public void setSecret(String name, String value) throws Exception {
            SecretStore.set(MuseActivity.this, "key." + name, value);
        }

        @JavascriptInterface
        public String saveImage(String b64, String mime, String name) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                StatusRepo.save(MuseActivity.this, new ByteArrayInputStream(data), name,
                        mime == null || !mime.startsWith("image/") ? "image/png" : mime);
                return "ok";
            } catch (Exception e) {
                return String.valueOf(e.getMessage());
            }
        }

        @JavascriptInterface
        public void openExternal(String url) {
            runOnUiThread(() -> MuseActivity.this.openExternal(url));
        }

        @JavascriptInterface
        public String takeShared() {
            String s = sharedJson;
            sharedJson = "[]";
            return s;
        }

        @JavascriptInterface
        public String aiApps() {
            return AiApps.toJson(MuseActivity.this);
        }

        /** Hands the prepared photos + prompt to an AI app (no API key). */
        @JavascriptInterface
        public String sendToAi(String id, String prompt, String imagesJson) {
            AiApps.App app = AiApps.find(id);
            if (app == null) return "Unknown app";
            try {
                File dir = ShareProvider.dir(MuseActivity.this);
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                JSONArray arr = new JSONArray(imagesJson == null ? "[]" : imagesJson);
                ArrayList<Uri> uris = new ArrayList<>();
                long stamp = System.currentTimeMillis();
                for (int k = 0; k < arr.length() && k < 6; k++) {
                    String dataUrl = arr.getString(k);
                    int comma = dataUrl.indexOf(',');
                    String head = comma > 0 ? dataUrl.substring(0, comma) : "";
                    String ext = head.contains("png") ? "png" : head.contains("webp") ? "webp" : "jpg";
                    File f = new File(dir, "muse-" + stamp + "-" + (k + 1) + "." + ext);
                    try (FileOutputStream os = new FileOutputStream(f)) {
                        os.write(Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT));
                    }
                    uris.add(ShareProvider.uriFor(f));
                }
                runOnUiThread(() -> AiApps.send(MuseActivity.this, app, uris, "image/*", prompt));
                return "ok";
            } catch (Exception e) {
                return String.valueOf(e.getMessage());
            }
        }

        @JavascriptInterface
        public void openAi(String id) {
            AiApps.App app = AiApps.find(id);
            if (app != null) runOnUiThread(() -> AiApps.open(MuseActivity.this, app));
        }
    }
}
