package com.fastshare.app;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.View;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * High-performance, low-latency native thumbnail caching and decoding engine.
 * Decodes images and videos asynchronously with an in-memory LRU cache.
 */
public class NativeThumbnailLoader {

    private static NativeThumbnailLoader instance;

    private final LruCache<String, Bitmap> memCache;
    private final ExecutorService pool;

    public static synchronized NativeThumbnailLoader get() {
        if (instance == null) {
            instance = new NativeThumbnailLoader();
        }
        return instance;
    }

    public static void init(Context ctx) {
        get();
    }

    private NativeThumbnailLoader() {
        int maxMem = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = Math.max(8 * 1024, maxMem / 8); // At least 8MB or 1/8th of RAM
        memCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "FS-ThumbLoader");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    public void loadThumbnail(File file, ImageView target, int targetWidth, int targetHeight, int fallbackRes) {
        if (file == null || target == null) return;
        final String key = file.getAbsolutePath() + "_" + file.lastModified() + "_" + targetWidth;

        Bitmap cached = memCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            target.setImageBitmap(cached);
            return;
        }

        if (fallbackRes != 0) {
            target.setImageResource(fallbackRes);
        } else {
            target.setImageDrawable(null);
        }
        target.setTag(key);

        pool.execute(() -> {
            Bitmap bmp = null;
            try {
                String name = file.getName().toLowerCase();
                if (isImage(name)) {
                    bmp = decodeImageThumbnail(file, targetWidth, targetHeight);
                } else if (isVideo(name)) {
                    bmp = decodeVideoThumbnail(file, targetWidth, targetHeight);
                }
            } catch (Throwable ignored) {}

            if (bmp != null) {
                memCache.put(key, bmp);
                final Bitmap finalBmp = bmp;
                target.post(() -> {
                    if (key.equals(target.getTag())) {
                        target.setImageBitmap(finalBmp);
                    }
                });
            }
        });
    }

    public void loadRemoteThumbnail(String urlStr, ImageView target, View fallbackIcon, int reqWidth, int reqHeight) {
        if (urlStr == null || urlStr.isEmpty() || target == null) return;
        final String key = "remote_" + urlStr;

        Bitmap cached = memCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            target.setImageBitmap(cached);
            target.setVisibility(View.VISIBLE);
            if (fallbackIcon != null) fallbackIcon.setVisibility(View.GONE);
            return;
        }

        target.setVisibility(View.GONE);
        if (fallbackIcon != null) fallbackIcon.setVisibility(View.VISIBLE);
        target.setTag(key);

        pool.execute(() -> {
            Bitmap bmp = null;
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(8000);
                conn.setDoInput(true);
                conn.connect();
                if (conn.getResponseCode() == 200) {
                    try (InputStream is = conn.getInputStream();
                         ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) != -1) {
                            baos.write(buffer, 0, len);
                        }
                        byte[] bytes = baos.toByteArray();
                        BitmapFactory.Options opt = new BitmapFactory.Options();
                        opt.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opt);
                        opt.inSampleSize = calculateInSampleSize(opt, reqWidth, reqHeight);
                        opt.inJustDecodeBounds = false;
                        opt.inPreferredConfig = Bitmap.Config.RGB_565;
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opt);
                    }
                }
            } catch (Throwable ignored) {}

            if (bmp != null) {
                memCache.put(key, bmp);
                final Bitmap finalBmp = bmp;
                target.post(() -> {
                    if (key.equals(target.getTag())) {
                        target.setImageBitmap(finalBmp);
                        target.setVisibility(View.VISIBLE);
                        if (fallbackIcon != null) fallbackIcon.setVisibility(View.GONE);
                    }
                });
            }
        });
    }

    private Bitmap decodeImageThumbnail(File file, int reqWidth, int reqHeight) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return ThumbnailUtils.createImageThumbnail(file, new Size(reqWidth, reqHeight), new CancellationSignal());
            } catch (Exception ignored) {}
        }

        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), opt);

        opt.inSampleSize = calculateInSampleSize(opt, reqWidth, reqHeight);
        opt.inJustDecodeBounds = false;
        opt.inPreferredConfig = Bitmap.Config.RGB_565; // Efficient memory usage
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opt);
    }

    private Bitmap decodeVideoThumbnail(File file, int reqWidth, int reqHeight) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return ThumbnailUtils.createVideoThumbnail(file, new Size(reqWidth, reqHeight), new CancellationSignal());
            } catch (Exception ignored) {}
        }

        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(file.getAbsolutePath());
            return mmr.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Exception e) {
            return null;
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    public static boolean isImage(String name) {
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                || name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".bmp")
                || name.endsWith(".heic") || name.endsWith(".avif");
    }

    public static boolean isVideo(String name) {
        return name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi")
                || name.endsWith(".mov") || name.endsWith(".webm") || name.endsWith(".3gp")
                || name.endsWith(".flv") || name.endsWith(".wmv");
    }

    public static boolean isMedia(String name) {
        return isImage(name) || isVideo(name);
    }
}
