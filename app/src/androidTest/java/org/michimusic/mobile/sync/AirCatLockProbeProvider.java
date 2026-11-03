package org.michimusic.mobile.sync;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

/**
 * AIR-02 probe. It runs in its own process ({@code :aircatlockprobe}) and takes a FileLock
 * on a descriptor handed over by the instrumented test, so two real processes contend for
 * one inode. That is the only way to observe the cross-process guarantee
 * {@code SyncViewModel.acquireAirCatLease()} depends on.
 *
 * <p>Written in Java on purpose: the androidTest APK does not bundle kotlin-stdlib, so a
 * Kotlin provider crashes in its own process with NoClassDefFoundError.
 *
 * <p>Test-only code: it ships in the androidTest APK, never in the app.
 */
public class AirCatLockProbeProvider extends ContentProvider {

    public static final String METHOD_ACQUIRE = "acquire";
    public static final String METHOD_RELEASE = "release";
    /** Drops the channel without releasing the lock, so the kernel frees it. */
    public static final String METHOD_CLOSE = "close";
    public static final String KEY_ACQUIRED = "acquired";
    /** ParcelFileDescriptor to the lock file, opened by the test process. */
    public static final String KEY_DESCRIPTOR = "descriptor";

    private FileChannel channel;
    private FileLock lock;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        if (METHOD_ACQUIRE.equals(method)) {
            result.putBoolean(KEY_ACQUIRED, acquire(extras));
        } else if (METHOD_RELEASE.equals(method)) {
            release();
            result.putBoolean(KEY_ACQUIRED, true);
        } else if (METHOD_CLOSE.equals(method)) {
            closeWithoutReleasing();
            result.putBoolean(KEY_ACQUIRED, false);
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    private boolean acquire(Bundle extras) {
        if (lock != null && lock.isValid()) {
            return true;
        }
        release();
        if (extras == null) {
            return false;
        }
        ParcelFileDescriptor descriptor = extras.getParcelable(KEY_DESCRIPTOR);
        if (descriptor == null) {
            return false;
        }
        FileChannel opened;
        try {
            opened = new FileOutputStream(descriptor.getFileDescriptor()).getChannel();
        } catch (Exception error) {
            return false;
        }
        FileLock acquired;
        try {
            acquired = opened.tryLock();
        } catch (Exception error) {
            // OverlappingFileLockException extends IllegalStateException; IOException covers
            // a lock the platform refuses outright. Either way this process does not own it.
            acquired = null;
        }
        if (acquired == null) {
            try {
                opened.close();
            } catch (Exception ignored) {
                // nothing to release
            }
            return false;
        }
        channel = opened;
        lock = acquired;
        return true;
    }

    /**
     * Closes the descriptor while the lock is still held. Linux drops the advisory lock when
     * the last descriptor for the inode closes, which is exactly what happens when the owning
     * process dies: that is the guarantee a crashed AirCat owner relies on.
     */
    private void closeWithoutReleasing() {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (Exception ignored) {
            // already closed
        }
        lock = null;
        channel = null;
    }

    private void release() {
        try {
            if (lock != null) {
                lock.release();
            }
        } catch (Exception ignored) {
            // already released
        }
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (Exception ignored) {
            // already closed
        }
        lock = null;
        channel = null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
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
}
