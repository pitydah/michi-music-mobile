package org.michimusic.mobile.ui.screens;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

/**
 * UI8-KILLCRITIC: the real playlist image path only accepts content:// URIs, because the
 * picker is the only production source of artwork. A test that imports its own banner
 * therefore needs a content URI, or it would be exercising a path the app refuses.
 *
 * <p>Written in Java on purpose: a provider declared by the test APK is started in its own
 * process, and that process does not get the app's Kotlin runtime, so a Kotlin provider
 * dies with NoClassDefFoundError: kotlin/jvm/internal/Intrinsics before answering getType.
 *
 * <p>The image is generated in memory and streamed through a pipe: the test APK and the app
 * have different package data directories, so a shared file would add a second failure mode
 * that has nothing to do with the import under test.
 */
public class ArtworkProbeProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        String[] columns = projection != null
                ? projection
                : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            // The size is unknown until the pipe is drained, and the importer only needs it
            // for the 25 MB ceiling.
            row[i] = OpenableColumns.DISPLAY_NAME.equals(columns[i]) ? nameOf(uri) : null;
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) {
        int[] colors = colorsOf(uri);
        if (colors == null) {
            throw new IllegalArgumentException("unknown artwork " + nameOf(uri));
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            final ParcelFileDescriptor.AutoCloseOutputStream out =
                    new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
            final Bitmap bitmap = gradient(colors);
            Thread writer = new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream stream = out) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                } catch (Exception ignored) {
                    // The reader closed early; nothing to report.
                } finally {
                    bitmap.recycle();
                }
            });
            writer.start();
            return pipe[0];
        } catch (Exception error) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private String nameOf(Uri uri) {
        String name = uri.getLastPathSegment();
        return name != null ? name : "artwork.png";
    }

    private int[] colorsOf(Uri uri) {
        String name = nameOf(uri);
        if ("blue.png".equals(name)) {
            return new int[] {0xFF1F6FEB, 0xFF0B1020};
        }
        if ("amber.png".equals(name)) {
            return new int[] {0xFFFFC857, 0xFF8A4B08};
        }
        return null;
    }

    private Bitmap gradient(int[] colors) {
        Bitmap bitmap = Bitmap.createBitmap(720, 480, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0f, 0f, 720f, 480f, colors, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, 720f, 480f, paint);
        return bitmap;
    }
}
