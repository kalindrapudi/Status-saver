# Status Studio

One ad-free Android app that combines **Status Saver** and **Muse Studio**, and connects to the AI apps you already use (ChatGPT, Gemini, Claude, Copilot, Perplexity, Grok, DeepSeek and Meta AI). The AI features use **your own account, with no API key needed**.

## What's inside

| Screen | What it does |
|---|---|
| **WhatsApp / WA Business** | Shows statuses you have already viewed. Preview, save, share, or long-press to save instantly. |
| **Saved** | Everything saved by the app (Pictures/StatusSaver and Movies/StatusSaver), including pictures from AI apps and Muse. |
| **Insta / FB** | A built-in browser. Log in yourself, open a story or post, then tap **Save** to save it at full quality. |
| **✧ AI → AI Hub** | One list of AI apps. If an app is installed, tapping it opens the app. If not, its website opens inside Status Studio, where you sign in once. Long-press an app to always use its website. |
| **✧ AI on a status** | Sends the photo or video and your question to ChatGPT, Gemini, Claude and others, for example "write a caption". You can also send a photo to Muse Studio to restyle it. |
| **Muse Studio** | Restyle your photo: pick a look, describe it, then tick **AI apps**. Your photo and a ready-made prompt go to the AI app you choose. Share the result back to Muse Studio to compare, rank and keep it. |

### How "no API key" works

Status Studio doesn't call AI services behind your back. It hands your photo and prompt to the official app with Android's normal share feature, and also copies the prompt in case the app ignores shared text. If the app isn't installed, the app's own website opens in a built-in browser, and when you tap its attach button, Status Studio offers your photo. Usage counts against your normal ChatGPT, Gemini or Claude plan, free or paid.

Your logins stay in Android's WebView, and the app never sees your password. **Google doesn't allow signing in inside other apps**, so for Gemini install the Gemini app or use the **Browser** button.

OpenAI and Gemini **API keys are still optional** in Muse Studio's Settings. They're for automatic generation, AI suggestions and ranking. If you add keys, they are encrypted with the Android Keystore.

## Get the APK

1. Push to this repository, or open **Actions → Build APK → Run workflow**.
2. When the run finishes, download the **StatusStudio-apk** artifact and unzip it to get `StatusStudio.apk`.
3. Open it on your phone and allow "Install unknown apps". If Android says the app conflicts with an existing package, uninstall the old Status Saver first. Each GitHub build is signed with a new debug key, so it can't update an older build.

To build it on a laptop instead, open the folder in Android Studio and choose **Build → Build APK(s)**.

Requires Android 10 or newer.

## First use

- **WhatsApp tab → Allow folder access**. The `.Statuses` folder opens already selected, so tap **Use this folder**, then **Allow**. Long-press a tab to choose the folder again.
- **✧ AI → Muse Studio**. Choose your photo, pick a look, keep **AI apps** ticked, tap **Create**, then tap ChatGPT or Gemini.

## Honest limits

- WhatsApp keeps only the statuses you have **viewed**, and only for 24 hours.
- Instagram and Facebook videos that stream in pieces (`blob:`) can't be downloaded, so use the screen recorder for those. Meta's terms don't allow automated downloading, so keep saved content for personal use.
- AI apps decide for themselves whether to accept shared text. When the text is missing, the prompt is already copied, so long-press and **Paste**.
- Image creation and its limits depend on your plan with each AI service.

## Project layout

```
app/src/main/java/com/kali/statussaver/
  MainActivity, PreviewActivity, StatusRepo, MediaItem   status saver
  WebSaverActivity                                       Insta / FB saver
  AiHubActivity, AiApps, AiWebActivity                   AI apps (no API key)
  MuseActivity, SecretStore, ShareProvider               Muse Studio shell
app/src/main/assets/muse/                                Muse Studio web app
.github/workflows/build.yml                              builds a signed APK
```

There are no third-party libraries, no ads and no trackers.
