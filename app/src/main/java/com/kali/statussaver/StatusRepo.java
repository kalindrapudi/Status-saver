package com.kali.statussaver;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Size;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads statuses from WhatsApp's folder and saves media into the phone gallery. */
public final class StatusRepo {

    public static final String FOLDER = "StatusSaver";

    private StatusRepo() {}

    /** Lists images/videos inside a folder the user granted through the system folder picker. */
    public static List<MediaItem> listTree(Context c, Uri tree) {
        List<MediaItem> out = new ArrayList<>();
        String docId = DocumentsContract.getTreeDocumentId(tree);
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] proj = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (Cursor cur = c.getContentResolver().query(kids, proj, null, null, null)) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                String id = cur.getString(0);
                String name = cur.getString(1);
                String mime = cur.getString(2);
                long mod = cur.isNull(3) ? 0 : cur.getLong(3);
                if (name == null || name.startsWith(".")) continue;          // skips .nomedia
                if (mime == null || !(mime.startsWith("image/") || mime.startsWith("video/"))) continue;
                out.add(new MediaItem(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, mime, mod));
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    /** Lists files this app saved into Pictures/StatusSaver and Movies/StatusSaver. */
    public static List<MediaItem> listSaved(Context c) {
        List<MediaItem> out = new ArrayList<>();
        querySaved(c, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), out);
        querySaved(c, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), out);
        Collections.sort(out, (a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    private static void querySaved(Context c, Uri col, List<MediaItem> out) {
        String[] proj = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_MODIFIED
        };
        String sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
        String[] args = {"%" + FOLDER + "%"};
        try (Cursor cur = c.getContentResolver().query(col, proj, sel, args, null)) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                long id = cur.getLong(0);
                out.add(new MediaItem(ContentUris.withAppendedId(col, id),
                        cur.getString(1), cur.getString(2), cur.getLong(3) * 1000L));
            }
        } catch (Exception ignored) {
        }
    }

    public static boolean alreadySaved(Context c, String name, String mime) {
        Uri col = collectionFor(mime);
        String sel = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
        String[] args = {name, "%" + FOLDER + "%"};
        try (Cursor cur = c.getContentResolver().query(col,
                new String[]{MediaStore.MediaColumns._ID}, sel, args, null)) {
            return cur != null && cur.getCount() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static Uri save(Context c, Uri src, String name, String mime) throws IOException {
        try (InputStream in = c.getContentResolver().openInputStream(src)) {
            return save(c, in, name, mime);
        }
    }

    public static Uri save(Context c, InputStream in, String name, String mime) throws IOException {
        if (in == null) throw new IOException("Could not open the file");
        ContentResolver cr = c.getContentResolver();
        boolean video = mime.startsWith("video/");
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/" + FOLDER);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri out = cr.insert(collectionFor(mime), v);
        if (out == null) throw new IOException("Gallery refused the file");
        try (OutputStream os = cr.openOutputStream(out)) {
            if (os == null) throw new IOException("Could not write the file");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } catch (IOException e) {
            cr.delete(out, null, null);
            throw e;
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(out, v, null, null);
        return out;
    }

    private static Uri collectionFor(String mime) {
        return mime != null && mime.startsWith("video/")
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }

    /** Small preview picture for the grid. */
    public static Bitmap thumbnail(Context c, MediaItem m, int px) {
        ContentResolver cr = c.getContentResolver();
        try {
            return cr.loadThumbnail(m.uri, new Size(px, px), null);
        } catch (Exception ignored) {
        }
        if (m.isVideo()) {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(c, m.uri);
                return r.getScaledFrameAtTime(1_000_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, px, px);
            } catch (Exception ignored) {
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                }
            }
        } else {
            return decode(c, m.uri, px);
        }
        return null;
    }

    /** Decodes an image, shrinking it so its width is roughly maxWidth. */
    public static Bitmap decode(Context c, Uri uri, int maxWidth) {
        try {
            ImageDecoder.Source s = ImageDecoder.createSource(c.getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(s, (decoder, info, source) -> {
                int w = info.getSize().getWidth();
                int sample = 1;
                while (w / (sample * 2) >= maxWidth) sample *= 2;
                decoder.setTargetSampleSize(sample);
            });
        } catch (Exception e) {
            return null;
        }
    }
}
