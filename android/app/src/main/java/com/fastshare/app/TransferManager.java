package com.fastshare.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * High-Speed Streaming Transfer Engine (Android -> PC / Other Android).
 * Streams selected photos, videos, and documents directly to remote Fast Share server.
 */
public class TransferManager {

    public interface TransferListener {
        void onTransferStart(String fileName, int currentIndex, int totalFiles, long totalBytes);
        void onTransferProgress(String fileName, int currentIndex, int totalFiles,
                                long bytesSent, long totalBytes, float speedMBps, int etaSec);
        void onFileCompleted(String fileName, int currentIndex, int totalFiles);
        void onAllCompleted(int successCount, int failCount);
        void onTransferError(String fileName, String error);
    }

    private final Context ctx;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isCancelled = false;

    public TransferManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void cancel() {
        isCancelled = true;
    }

    public void sendFiles(List<Uri> uris, String targetIp, int targetPort,
                          String user, String pass, TransferListener listener) {
        isCancelled = false;
        executor.execute(() -> {
            int success = 0;
            int failed = 0;
            int total = uris.size();

            for (int i = 0; i < total; i++) {
                if (isCancelled) break;
                Uri uri = uris.get(i);
                FileInfo info = queryFileInfo(uri);
                final int curIdx = i + 1;

                mainHandler.post(() -> {
                    if (listener != null) {
                        listener.onTransferStart(info.name, curIdx, total, info.size);
                    }
                });

                boolean ok = uploadSingleFile(uri, info, targetIp, targetPort, user, pass, curIdx, total, listener);
                if (ok) {
                    success++;
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onFileCompleted(info.name, curIdx, total);
                        }
                    });
                } else {
                    failed++;
                }
            }

            final int finalSuccess = success;
            final int finalFailed = failed;
            mainHandler.post(() -> {
                if (listener != null) {
                    listener.onAllCompleted(finalSuccess, finalFailed);
                }
            });
        });
    }

    private boolean uploadSingleFile(Uri uri, FileInfo info, String ip, int port,
                                    String user, String pass, int curIdx, int total,
                                    TransferListener listener) {
        HttpURLConnection conn = null;
        try {
            String encodedName = URLEncoder.encode(info.name, "UTF-8");
            String urlStr = "http://" + ip + ":" + port + "/api/raw?name=" + encodedName;
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(120000);

            if (user != null && pass != null && !pass.isEmpty()) {
                String auth = user + ":" + pass;
                String b64 = Base64.encodeToString(auth.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                conn.setRequestProperty("Authorization", "Basic " + b64);
            }

            conn.setRequestProperty("Connection", "Keep-Alive");
            if (info.size > 0) {
                conn.setFixedLengthStreamingMode(info.size);
            } else {
                conn.setChunkedStreamingMode(2 * 1024 * 1024);
            }

            long startTime = System.currentTimeMillis();
            long lastCalcTime = startTime;
            long lastCalcBytes = 0;
            float currentSpeed = 0f;

            try (InputStream rawIn = ctx.getContentResolver().openInputStream(uri);
                 BufferedInputStream in = (rawIn != null ? new BufferedInputStream(rawIn, 2 * 1024 * 1024) : null);
                 BufferedOutputStream out = new BufferedOutputStream(conn.getOutputStream(), 2 * 1024 * 1024)) {
                if (in == null) return false;

                byte[] buf = new byte[2 * 1024 * 1024];
                long sent = 0;
                int n;

                while ((n = in.read(buf)) != -1) {
                    if (isCancelled) return false;
                    out.write(buf, 0, n);
                    sent += n;

                    long now = System.currentTimeMillis();
                    if (now - lastCalcTime >= 200) {
                        long dTime = now - lastCalcTime;
                        long dBytes = sent - lastCalcBytes;
                        currentSpeed = ((float) dBytes / (1024 * 1024)) / ((float) dTime / 1000f);
                        lastCalcTime = now;
                        lastCalcBytes = sent;

                        int eta = 0;
                        if (currentSpeed > 0.05f && info.size > sent) {
                            eta = (int) ((info.size - sent) / (currentSpeed * 1024 * 1024));
                        }

                        final long finalSent = sent;
                        final float finalSpeed = currentSpeed;
                        final int finalEta = eta;
                        mainHandler.post(() -> {
                            if (listener != null) {
                                listener.onTransferProgress(info.name, curIdx, total,
                                        finalSent, info.size, finalSpeed, finalEta);
                            }
                        });
                    }
                }
                out.flush();
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return true;
            } else {
                mainHandler.post(() -> {
                    if (listener != null) {
                        listener.onTransferError(info.name, code == 401 ? "AUTH_REQUIRED" : ("HTTP " + code));
                    }
                });
                return false;
            }
        } catch (Exception e) {
            mainHandler.post(() -> {
                if (listener != null) {
                    listener.onTransferError(info.name, e.getMessage());
                }
            });
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static class FileInfo {
        String name = "file";
        long size = -1;
    }

    private FileInfo queryFileInfo(Uri uri) {
        FileInfo fi = new FileInfo();
        try (Cursor cursor = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIdx != -1) fi.name = cursor.getString(nameIdx);
                if (sizeIdx != -1) fi.size = cursor.getLong(sizeIdx);
            }
        } catch (Exception ignored) {}
        if (fi.name == null || fi.name.isEmpty()) {
            fi.name = "upload_" + System.currentTimeMillis();
        }
        return fi;
    }
}
