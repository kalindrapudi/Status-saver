package com.kali.statussaver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * AI assistants the user can reach with their OWN account, no API key needed.
 *
 * If the official app is installed we hand the photo + prompt straight to it
 * (Android share). Otherwise the assistant's website opens in our built-in
 * browser, where the user signs in once and the photo is offered for attaching.
 */
public final class AiApps {

    public static final class App {
        public final String id, name, pkg, web, note;

        App(String id, String name, String pkg, String web, String note) {
            this.id = id;
            this.name = name;
            this.pkg = pkg;
            this.web = web;
            this.note = note;
        }
    }

    // Package names are also listed under <queries> in AndroidManifest.xml.
    public static final App[] ALL = {
            new App("chatgpt", "ChatGPT", "com.openai.chatgpt", "https://chatgpt.com/",
                    "Chat, photo questions, image creation"),
            new App("gemini", "Gemini", "com.google.android.apps.bard", "https://gemini.google.com/app",
                    "Chat, photo questions, image creation"),
            new App("claude", "Claude", "com.anthropic.claude", "https://claude.ai/new",
                    "Chat, writing, photo questions"),
            new App("copilot", "Copilot", "com.microsoft.copilot", "https://copilot.microsoft.com/",
                    "Chat, image creation"),
            new App("perplexity", "Perplexity", "ai.perplexity.app.android", "https://www.perplexity.ai/",
                    "Answers with web sources"),
            new App("grok", "Grok", "ai.x.grok", "https://grok.com/",
                    "Chat, image creation"),
            new App("deepseek", "DeepSeek", "com.deepseek.chat", "https://chat.deepseek.com/",
                    "Chat, reasoning"),
            new App("metaai", "Meta AI", null, "https://www.meta.ai/",
                    "Chat, image creation"),
    };

    private AiApps() {}

    public static App find(String id) {
        for (App a : ALL) if (a.id.equals(id)) return a;
        return null;
    }

    public static boolean installed(Context c, App a) {
        if (a.pkg == null) return false;
        try {
            c.getPackageManager().getPackageInfo(a.pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** JSON list used by the Muse web screen. */
    public static String toJson(Context c) {
        JSONArray arr = new JSONArray();
        try {
            for (App a : ALL) {
                arr.put(new JSONObject()
                        .put("id", a.id).put("name", a.name).put("note", a.note)
                        .put("installed", installed(c, a)));
            }
        } catch (Exception ignored) {
        }
        return arr.toString();
    }

    /** Opens the assistant: its app if installed, otherwise the built-in browser. */
    public static void open(Activity act, App a) {
        if (installed(act, a)) {
            Intent i = act.getPackageManager().getLaunchIntentForPackage(a.pkg);
            if (i != null) {
                act.startActivity(i);
                return;
            }
        }
        openWeb(act, a, null, null);
    }

    public static void openWeb(Activity act, App a, ArrayList<Uri> attach, String prompt) {
        Intent i = new Intent(act, AiWebActivity.class);
        i.putExtra(AiWebActivity.EXTRA_ID, a.id);
        if (attach != null && !attach.isEmpty()) i.putParcelableArrayListExtra(AiWebActivity.EXTRA_ATTACH, attach);
        if (prompt != null) i.putExtra(AiWebActivity.EXTRA_PROMPT, prompt);
        act.startActivity(i);
    }

    /**
     * Sends photos (content:// uris readable by us) and a prompt to the assistant.
     * The prompt is also copied, because some apps ignore text that comes with a picture.
     */
    public static void send(Activity act, App a, List<Uri> uris, String mime, String prompt) {
        if (prompt != null && !prompt.isEmpty()) copy(act, prompt);
        if (!installed(act, a)) {
            openWeb(act, a, new ArrayList<>(uris), prompt);
            return;
        }
        Intent i;
        if (uris.size() > 1) {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));
        } else {
            i = new Intent(Intent.ACTION_SEND);
            if (!uris.isEmpty()) i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        }
        if (uris.isEmpty()) i.setType("text/plain");
        else i.setType(mime == null ? "image/*" : mime);
        if (prompt != null && !prompt.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, prompt);
        if (!uris.isEmpty()) {
            ClipData clip = ClipData.newRawUri("", uris.get(0));
            for (int k = 1; k < uris.size(); k++) clip.addItem(new ClipData.Item(uris.get(k)));
            i.setClipData(clip);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        i.setPackage(a.pkg);
        try {
            act.startActivity(i);
            if (prompt != null && !prompt.isEmpty()) {
                Toast.makeText(act, "Sent to " + a.name + ". The prompt is also copied, so paste it if it's missing.",
                        Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            // App installed but doesn't accept this share: fall back to the website.
            openWeb(act, a, new ArrayList<>(uris), prompt);
        }
    }

    static void copy(Context c, String text) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("prompt", text));
    }

    /** "Ask AI" dialog: pick an assistant, optionally edit the question, then send. */
    public static void askDialog(Activity act, Uri uri, String mime) {
        String[] names = new String[ALL.length];
        for (int k = 0; k < ALL.length; k++) {
            names[k] = ALL[k].name + (installed(act, ALL[k]) ? "  ·  app" : "  ·  web");
        }
        new AlertDialog.Builder(act)
                .setTitle("Ask an AI app (your account, no API key)")
                .setItems(names, (d, which) -> promptDialog(act, ALL[which], uri, mime))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void promptDialog(Activity act, App a, Uri uri, String mime) {
        boolean video = mime != null && mime.startsWith("video/");
        EditText et = new EditText(act);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setMinLines(2);
        et.setText(video
                ? "Write a short, catchy caption for this video."
                : "Describe this picture and suggest a short, catchy caption for it.");
        et.selectAll();
        int pad = Math.round(20 * act.getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(act);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        new AlertDialog.Builder(act)
                .setTitle("Ask " + a.name)
                .setView(box)
                .setPositiveButton("Send", (d, w) -> {
                    List<Uri> list = new ArrayList<>();
                    list.add(uri);
                    send(act, a, list, mime, et.getText().toString().trim());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
