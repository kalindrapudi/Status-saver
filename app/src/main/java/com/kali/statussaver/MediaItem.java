package com.kali.statussaver;

import android.net.Uri;

public class MediaItem {
    public final Uri uri;
    public final String name;
    public final String mime;
    public final long modified;

    public MediaItem(Uri uri, String name, String mime, long modified) {
        this.uri = uri;
        this.name = name;
        this.mime = mime;
        this.modified = modified;
    }

    public boolean isVideo() {
        return mime != null && mime.startsWith("video/");
    }
}
