package com.fastshare.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.os.Build;
import android.os.Environment;
import android.util.Base64;
import android.util.LruCache;
import android.util.Size;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Tiny self-contained HTTP file server: the share.py backend ported to Java.
 * Basic-Auth protected, Range/resume downloads, streaming multipart uploads,
 * on-the-fly folder ZIP, mkdir/rename/delete. No dependencies.
 */
public class ShareServer {

    public interface AuthProvider {
        String getUser();
        String getPassword();
    }

    private static final int CHUNK = 2 * 1024 * 1024; // 2 MB buffer for gigabit Wi-Fi
    private static final String SERVER = "FastShare-Android/1.0";

    private final Context appCtx;
    private final int port;
    private final AuthProvider pw;
    private final String sessionToken = UUID.randomUUID().toString();
    private ServerSocketChannel serverChannel;
    private ExecutorService pool;
    private volatile boolean running;
    private byte[] uiCache;
    private final LruCache<String, byte[]> thumbCache = new LruCache<String, byte[]>(32 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return value.length;
        }
    };

    private final java.util.concurrent.atomic.AtomicLong downloadedBytes = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong uploadedBytes = new java.util.concurrent.atomic.AtomicLong(0);

    public long getDownloadedBytes() { return downloadedBytes.get(); }
    public long getUploadedBytes() { return uploadedBytes.get(); }
    public void addDownloaded(long b) { downloadedBytes.addAndGet(b); }
    public void addUploaded(long b) { uploadedBytes.addAndGet(b); }

    public ShareServer(Context ctx, int port, AuthProvider pw) {
        this.appCtx = ctx.getApplicationContext();
        this.port = port;
        this.pw = pw;
    }

    public synchronized boolean start() {
        if (running) return true;
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(true);
            ServerSocket ss = serverChannel.socket();
            ss.setReuseAddress(true);
            // RFC 1323 TCP Window Scaling: MUST be set prior to bind() on Linux/Android
            try {
                ss.setReceiveBufferSize(4 * 1024 * 1024);
            } catch (Exception ignored) {}
            ss.bind(new java.net.InetSocketAddress("0.0.0.0", port), 128);
        } catch (IOException e) {
            return false;
        }
        running = true;
        pool = Executors.newFixedThreadPool(24);
        Thread accept = new Thread(() -> {
            while (running) {
                try {
                    SocketChannel sc = serverChannel.accept();
                    sc.configureBlocking(true);
                    Socket s = sc.socket();
                    try {
                        s.setTcpNoDelay(true);
                        s.setSendBufferSize(4 * 1024 * 1024);
                        s.setReceiveBufferSize(4 * 1024 * 1024);
                        s.setPerformancePreferences(0, 1, 2); // prioritize bandwidth
                    } catch (Exception ignored) {}
                    pool.execute(() -> handle(sc, s));
                } catch (IOException e) {
                    if (running) {
                        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                    }
                }
            }
        }, "fs-accept");
        accept.setDaemon(true);
        accept.start();
        return true;
    }

    public synchronized void stop() {
        running = false;
        try { if (serverChannel != null) serverChannel.close(); } catch (IOException ignored) {}
        if (pool != null) pool.shutdownNow();
    }

    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ I/O

    private static final class Req {
        String method, rawPath, path, clientIp;
        Map<String, String> query = new HashMap<>();
        Map<String, String> headers = new HashMap<>();
    }

    /** Read request line + headers byte-wise so the body stream stays exact. */
    private Req readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream hb = new ByteArrayOutputStream();
        int[] tail = new int[4];
        int tl = 0;
        int total = 0;
        while (true) {
            int b = in.read();
            if (b < 0) return null;
            hb.write(b);
            tail[tl % 4] = b;
            tl++;
            total++;
            if (total > 65536) return null;
            if (tl >= 4 && tail[(tl - 4) % 4] == '\r' && tail[(tl - 3) % 4] == '\n'
                    && tail[(tl - 2) % 4] == '\r' && tail[(tl - 1) % 4] == '\n') break;
        }
        String head = hb.toString("ISO-8859-1");
        String[] lines = head.split("\r\n");
        if (lines.length == 0) return null;
        String[] rl = lines[0].split(" ", 3);
        if (rl.length < 2) return null;
        Req r = new Req();
        r.method = rl[0].toUpperCase(Locale.US);
        r.rawPath = rl[1];
        int qi = rl[1].indexOf('?');
        String q = "";
        if (qi >= 0) {
            r.path = rl[1].substring(0, qi);
            q = rl[1].substring(qi + 1);
        } else {
            r.path = rl[1];
        }
        for (String kv : q.split("&")) {
            if (kv.isEmpty()) continue;
            int ei = kv.indexOf('=');
            try {
                if (ei < 0) r.query.put(URLDecoder.decode(kv, "UTF-8"), "");
                else r.query.put(URLDecoder.decode(kv.substring(0, ei), "UTF-8"),
                        URLDecoder.decode(kv.substring(ei + 1), "UTF-8"));
            } catch (Exception ignored) {}
        }
        for (int i = 1; i < lines.length; i++) {
            int ci = lines[i].indexOf(':');
            if (ci > 0) {
                r.headers.put(lines[i].substring(0, ci).trim().toLowerCase(Locale.US),
                        lines[i].substring(ci + 1).trim());
            }
        }
        try {
            r.path = URLDecoder.decode(r.path, "UTF-8");
        } catch (Exception ignored) {}
        return r;
    }

    private boolean authorized(Req r, Socket s) {
        if (s != null && s.getInetAddress() != null && s.getInetAddress().isLoopbackAddress()) {
            return true;
        }
        String p = r.path;
        if ("/".equals(p) || "/index.html".equals(p) || "/api/info".equals(p)
                || "/api/nearby".equals(p) || "/api/login".equals(p) || "OPTIONS".equals(r.method)) {
            return true;
        }
        String user = pw.getUser();
        String pass = pw.getPassword();
        if ((pass == null || pass.isEmpty())) return true;
        String cookie = r.headers.get("cookie");
        if (cookie != null) {
            String expected = "fastshare_token=" + sessionToken;
            for (String part : cookie.split(";")) {
                if (expected.equals(part.trim())) return true;
            }
        }

        String ah = r.headers.get("authorization");
        if (ah != null && ah.startsWith("Basic ")) {
            try {
                String dec = new String(Base64.decode(ah.substring(6).trim(), Base64.DEFAULT),
                        StandardCharsets.UTF_8);
                int ci = dec.indexOf(':');
                String givenUser = ci >= 0 ? dec.substring(0, ci) : dec;
                String given = ci >= 0 ? dec.substring(ci + 1) : "";
                if (user.equals(givenUser) && pass.equals(given)) return true;
            } catch (Exception ignored) {}
        }
        // Direct browser download token authentication
        String qtok = r.query.get("token");
        if (sessionToken.equals(qtok)) return true;
        return false;
    }

    private void handle(SocketChannel sc, Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(120000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            Req r = readRequest(in);
            if (r == null) return;
            if (s.getInetAddress() != null) {
                r.clientIp = s.getInetAddress().getHostAddress();
            }
            if (!authorized(r, s)) {
                sendJson(out, "{\"error\":\"unauthorized\",\"login_required\":true}", 401);
                out.flush();
                return;
            }
            route(r, in, out, sc);
            out.flush();
        } catch (Exception ignored) {}
    }

    private static Map<String, String> linked(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private void sendStatus(OutputStream out, int code, String msg, Map<String, String> h)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(msg).append("\r\n");
        sb.append("Server: ").append(SERVER).append("\r\n");
        for (Map.Entry<String, String> e : h.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes("ISO-8859-1"));
    }

    private void sendJson(OutputStream out, String json, int code) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        sendStatus(out, code, code == 200 ? "OK" : "Error", linked(
                "Content-Type", "application/json; charset=utf-8",
                "Content-Length", String.valueOf(b.length),
                "Access-Control-Allow-Origin", "*",
                "Connection", "close"));
        out.write(b);
    }

    private void sendHtml(OutputStream out, byte[] b) throws IOException {
        sendStatus(out, 200, "OK", linked(
                "Content-Type", "text/html; charset=utf-8",
                "Content-Length", String.valueOf(b.length),
                "Connection", "close"));
        out.write(b);
    }

    // ---------------------------------------------------------------- routing

    private void route(Req r, InputStream in, OutputStream out, SocketChannel sc) throws IOException {
        String m = r.method;
        String p = r.path;
        if ("OPTIONS".equals(m)) {
            sendStatus(out, 200, "OK", linked(
                    "Access-Control-Allow-Origin", "*",
                    "Access-Control-Allow-Methods", "GET,POST,HEAD,OPTIONS",
                    "Access-Control-Allow-Headers", "*",
                    "Access-Control-Expose-Headers", "*",
                    "Access-Control-Allow-Private-Network", "true",
                    "Content-Length", "0", "Connection", "close"));
            return;
        }
        if (("GET".equals(m) || "HEAD".equals(m)) && ("/".equals(p) || "/index.html".equals(p))) {
            if (uiCache == null) {
                try (InputStream a = appCtx.getAssets().open("ui.html")) {
                    uiCache = readAll(a);
                }
            }
            boolean isLocal = r.clientIp != null && (r.clientIp.equals("127.0.0.1") || r.clientIp.equals("::1") || r.clientIp.equals("localhost"));
            String meta = "window.__FS_META__ = {\"is_local\":" + isLocal
                    + ",\"local_token\":\"android_local\""
                    + ",\"device\":" + js(deviceLabel())
                    + ",\"port\":" + port
                    + ",\"ip\":" + js(lanIp())
                    + ",\"authenticated\":true};";
            String rawHtml = new String(uiCache, StandardCharsets.UTF_8);
            byte[] pageBytes = rawHtml.replace("/*__SERVER_META__*/", meta).getBytes(StandardCharsets.UTF_8);
            if ("HEAD".equals(m)) {
                sendStatus(out, 200, "OK", linked("Content-Type", "text/html; charset=utf-8",
                        "Content-Length", String.valueOf(pageBytes.length), "Connection", "close"));
            } else {
                sendHtml(out, pageBytes);
            }
            return;
        }
        if ("GET".equals(m) && "/api/info".equals(p)) {
            List<Root> roots = computeRoots();
            sendJson(out, "{\"device\":" + js(deviceLabel())
                    + ",\"platform\":\"Android\",\"hostname\":" + js(Build.MODEL)
                    + ",\"ip\":" + js(lanIp()) + ",\"port\":" + port
                    + ",\"client_ip\":" + js(r.clientIp != null ? r.clientIp : "")
                    + ",\"is_android\":true,\"roots\":" + roots.size() + "}", 200);
            return;
        }
        if ("GET".equals(m) && "/api/nearby".equals(p)) {
            DiscoveryManager dm = ShareService.getDiscovery();
            String res = dm != null ? dm.getDevicesJson() : "[]";
            sendJson(out, res, 200);
            return;
        }
        if ("GET".equals(m) && "/api/drives".equals(p)) {
            sendJson(out, rootsJson(computeRoots()), 200);
            return;
        }
        if ("GET".equals(m) && "/api/list".equals(p)) {
            String raw = r.query.get("path");
            boolean showHidden = "1".equals(r.query.get("hidden"));
            List<Root> rts = computeRoots();
            if (raw == null || raw.isEmpty()) {
                StringBuilder items = new StringBuilder("[");
                boolean first = true;
                for (Root root : rts) {
                    if (!first) items.append(',');
                    first = false;
                    items.append("{\"name\":").append(js(root.name))
                            .append(",\"path\":").append(js(root.path))
                            .append(",\"is_dir\":true")
                            .append(",\"is_drive\":true")
                            .append(",\"size\":").append(root.total)
                            .append(",\"free\":").append(root.free)
                            .append(",\"used\":").append(root.used)
                            .append(",\"icon\":").append(js(root.icon))
                            .append(",\"mtime\":").append(System.currentTimeMillis() / 1000).append('}');
                }
                items.append(']');
                sendJson(out, "{\"path\":\"\""
                        + ",\"breadcrumbs\":[{\"name\":" + js(deviceLabel()) + ",\"path\":\"\"}]"
                        + ",\"items\":" + items
                        + ",\"dirs\":" + rts.size() + ",\"files\":0"
                        + ",\"total_size\":0"
                        + ",\"writable\":false"
                        + ",\"roots\":" + rootsJson(rts)
                        + ",\"quick\":" + quickJson()
                        + ",\"device\":" + js(deviceLabel()) + ",\"is_android\":true}", 200);
                return;
            }
            String ap = confinedPath(raw);
            File f = new File(ap);
            if (!f.exists()) {
                sendJson(out, "{\"error\":\"not found\"}", 404);
                return;
            }
            if (f.isFile()) {
                serveFile(r, out, ap, true, sc);
                return;
            }
            File[] kids = f.listFiles();
            if (kids == null) {
                sendJson(out, "{\"error\":\"no permission\"}", 403);
                return;
            }
            StringBuilder items = new StringBuilder("[");
            int dc = 0, fc = 0;
            long tsz = 0;
            boolean first = true;
            for (File k : kids) {
                String n = k.getName();
                if (!showHidden && n.startsWith(".")) continue;
                boolean isd = k.isDirectory();
                long sz = isd ? 0 : k.length();
                if (isd) dc++; else { fc++; tsz += sz; }
                if (!first) items.append(',');
                first = false;
                items.append("{\"name\":").append(js(n))
                        .append(",\"path\":").append(js(k.getAbsolutePath()))
                        .append(",\"is_dir\":").append(isd)
                        .append(",\"size\":").append(sz)
                        .append(",\"mtime\":").append(k.lastModified() / 1000).append('}');
            }
            items.append(']');
            sendJson(out, "{\"path\":" + js(ap)
                    + ",\"breadcrumbs\":" + breadcrumbsJson(ap)
                    + ",\"items\":" + items
                    + ",\"dirs\":" + dc + ",\"files\":" + fc
                    + ",\"total_size\":" + tsz
                    + ",\"writable\":" + f.canWrite()
                    + ",\"roots\":" + rootsJson(computeRoots())
                    + ",\"quick\":" + quickJson() + "}", 200);
            return;
        }
        if (("GET".equals(m) || "HEAD".equals(m))
                && ("/api/download".equals(p) || "/api/view".equals(p))) {
            serveFile(r, out, confinedPath(r.query.get("path")), "/api/download".equals(p), sc);
            return;
        }
        if (("GET".equals(m) || "HEAD".equals(m)) && "/api/thumb".equals(p)) {
            serveThumb(r, out, confinedPath(r.query.get("path")));
            return;
        }
        if ("GET".equals(m) && "/api/zip".equals(p)) {
            String ap = confinedPath(r.query.get("path"));
            if (ap.isEmpty() || !new File(ap).isDirectory()) {
                sendJson(out, "{\"error\":\"need folder\"}", 400);
                return;
            }
            serveZip(out, new File(ap));
            return;
        }
        if ("POST".equals(m) && p.startsWith("/api/")) {
            handlePost(r, in, out);
            return;
        }
        sendJson(out, "{\"error\":\"not found\"}", 404);
    }

    // ------------------------------------------------------------- file serve

    private void serveThumb(Req r, OutputStream out, String ap) throws IOException {
        File f = new File(ap);
        if (!f.isFile() || !f.canRead()) {
            sendJson(out, "{\"error\":\"not found\"}", 404);
            return;
        }
        String cacheKey = ap + ":" + f.lastModified();
        byte[] cached = thumbCache.get(cacheKey);
        if (cached != null) {
            sendStatus(out, 200, "OK", linked(
                    "Content-Type", "image/jpeg",
                    "Content-Length", String.valueOf(cached.length),
                    "Cache-Control", "public, max-age=604800, immutable",
                    "Access-Control-Allow-Origin", "*",
                    "Connection", "close"));
            if (!"HEAD".equals(r.method)) out.write(cached);
            return;
        }

        String name = f.getName().toLowerCase(Locale.US);
        Bitmap bmp = null;
        try {
            if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm")
                    || name.endsWith(".avi") || name.endsWith(".3gp") || name.endsWith(".mov")) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        bmp = ThumbnailUtils.createVideoThumbnail(f, new Size(320, 320), null);
                    } catch (Throwable ignored) {}
                }
                if (bmp == null) {
                    try {
                        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                        mmr.setDataSource(f.getAbsolutePath());
                        bmp = mmr.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        mmr.release();
                    } catch (Throwable ignored) {}
                }
            } else if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                    || name.endsWith(".webp") || name.endsWith(".bmp") || name.endsWith(".gif")
                    || name.endsWith(".heic") || name.endsWith(".heif")) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        bmp = ThumbnailUtils.createImageThumbnail(f, new Size(320, 320), null);
                    } catch (Throwable ignored) {}
                }
                if (bmp == null) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
                    int maxDim = Math.max(opts.outWidth, opts.outHeight);
                    int sample = 1;
                    while (maxDim / sample > 400) {
                        sample *= 2;
                    }
                    opts.inJustDecodeBounds = false;
                    opts.inSampleSize = sample;
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
                }
            }
        } catch (Throwable t) {
            bmp = null;
        }

        if (bmp != null) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                bmp.recycle();
                byte[] bytes = baos.toByteArray();
                thumbCache.put(cacheKey, bytes);
                sendStatus(out, 200, "OK", linked(
                        "Content-Type", "image/jpeg",
                        "Content-Length", String.valueOf(bytes.length),
                        "Cache-Control", "public, max-age=604800, immutable",
                        "Access-Control-Allow-Origin", "*",
                        "Connection", "close"));
                if (!"HEAD".equals(r.method)) out.write(bytes);
                return;
            } catch (Exception ignored) {}
        }

        if (f.length() <= 5 * 1024 * 1024 && (name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".svg"))) {
            serveFile(r, out, ap, false, null);
            return;
        }

        sendJson(out, "{\"error\":\"no thumbnail\"}", 404);
    }

    private void serveFile(Req r, OutputStream out, String ap, boolean download, SocketChannel sc)
            throws IOException {
        File f = new File(ap);
        if (!f.isFile()) {
            sendJson(out, "{\"error\":\"not found\"}", 404);
            return;
        }
        long size = f.length();
        String ctype = "application/octet-stream";
        if (!download) {
            String n = f.getName().toLowerCase(Locale.US);
            if (n.endsWith(".html") || n.endsWith(".htm")) ctype = "text/html; charset=utf-8";
            else if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log")) ctype = "text/plain; charset=utf-8";
            else if (n.endsWith(".pdf")) ctype = "application/pdf";
            else if (n.endsWith(".png")) ctype = "image/png";
            else if (n.endsWith(".jpg") || n.endsWith(".jpeg")) ctype = "image/jpeg";
            else if (n.endsWith(".gif")) ctype = "image/gif";
            else if (n.endsWith(".webp")) ctype = "image/webp";
            else if (n.endsWith(".mp4")) ctype = "video/mp4";
            else if (n.endsWith(".mp3")) ctype = "audio/mpeg";
            else if (n.endsWith(".json")) ctype = "application/json";
        }
        long start = 0, end = size - 1;
        boolean partial = false;
        String rh = r.headers.get("range");
        if (rh != null) {
            Matcher mm = Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(rh);
            if (mm.find()) {
                try {
                    String ss = mm.group(1), es = mm.group(2);
                    if (ss.isEmpty() && !es.isEmpty()) start = size - Long.parseLong(es);
                    else if (!es.isEmpty()) { start = Long.parseLong(ss); end = Long.parseLong(es); }
                    else if (!ss.isEmpty()) start = Long.parseLong(ss);
                    start = Math.max(0, start);
                    end = Math.min(size - 1, end);
                    if (start <= end) partial = true;
                } catch (NumberFormatException ignored) {}
            }
        }
        long length = partial ? (end - start + 1) : size;
        Map<String, String> h = linked(
                "Content-Type", ctype,
                "Accept-Ranges", "bytes",
                "Content-Length", String.valueOf(length),
                "Access-Control-Allow-Origin", "*",
                "Access-Control-Allow-Private-Network", "true",
                "Access-Control-Expose-Headers", "Content-Length, Content-Range, Content-Disposition",
                "Content-Disposition", (download ? "attachment" : "inline")
                        + "; filename*=UTF-8''" + urlEncodeName(f.getName()),
                "Connection", "close");
        if (partial) h.put("Content-Range", "bytes " + start + "-" + end + "/" + size);
        sendStatus(out, partial ? 206 : 200, partial ? "Partial Content" : "OK", h);
        if ("HEAD".equals(r.method)) return;
        out.flush();

        // Native Linux Kernel Zero-Copy: FileChannel.transferTo(SocketChannel) -> sendfile64()
        long off = start;
        long left = length;
        boolean zeroCopySuccess = false;
        if (sc != null && sc.isOpen()) {
            try (FileInputStream fis = new FileInputStream(f)) {
                FileChannel fc = fis.getChannel();
                int zeroCount = 0;
                while (left > 0) {
                    long n = fc.transferTo(off, left, sc);
                    if (n > 0) {
                        off += n;
                        left -= n;
                        zeroCount = 0;
                    } else {
                        zeroCount++;
                        if (zeroCount > 10) break;
                        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                    }
                }
                zeroCopySuccess = (left <= 0);
            } catch (IOException ignored) {
                // client disconnected or fallback
            }
        }

        // High-performance 2MB buffered streaming fallback
        if (!zeroCopySuccess && left > 0) {
            try (FileInputStream fis = new FileInputStream(f)) {
                FileChannel fc = fis.getChannel();
                if (off > 0) {
                    fc.position(off);
                }
                BufferedOutputStream bout = new BufferedOutputStream(out, 2 * 1024 * 1024);
                byte[] buf = new byte[2 * 1024 * 1024];
                long remaining = left;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buf.length, remaining);
                    int n = fis.read(buf, 0, toRead);
                    if (n <= 0) break;
                    bout.write(buf, 0, n);
                    remaining -= n;
                }
                bout.flush();
            } catch (IOException ignored) {
                // client went away
            }
        }
    }

    private static String urlEncodeName(String n) {
        try {
            return java.net.URLEncoder.encode(n, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "file";
        }
    }

    /**
     * Stream a folder as ZIP with zero compression (STORED): photos, videos
     * and APKs are already compressed, so DEFLATE only burns phone CPU.
     * Manual writer: one cheap CRC pre-pass per file, then real CRC+sizes in
     * the local header (no data descriptors — those break java.util.zip and
     * some phone unzip apps). Central directory closes the stream. First
     * byte leaves after the first file's CRC pass — no temp file, no
     * whole-tree pre-pass.
     */
    private void serveZip(OutputStream rawOut, File folder) throws IOException {
        String base = folder.getName().replaceAll("[^\\w\\-. ]+", "_");
        if (base.isEmpty()) base = "files";
        Map<String, String> h = linked(
                "Content-Type", "application/zip",
                "Content-Disposition", "attachment; filename*=UTF-8''" + urlEncodeName(base + ".zip"),
                "Access-Control-Allow-Origin", "*",
                "Access-Control-Expose-Headers", "Content-Disposition",
                "Transfer-Encoding", "chunked",
                "Connection", "close");
        sendStatus(rawOut, 200, "OK", h);
        ChunkedOut chunked = new ChunkedOut(new BufferedOutputStream(rawOut, 2 * 1024 * 1024));
        final String baseName = base;
        final Path root = folder.toPath();
        final List<String> names = new ArrayList<>();
        final List<Long> crcs = new ArrayList<>();
        final List<Long> sizes = new ArrayList<>();
        final List<Long> offsets = new ArrayList<>();
        final long[] pos = new long[]{0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        if (Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE;
                        String rel = root.relativize(file).toString()
                                .replace(File.separatorChar, '/');
                        byte[] nameB = (baseName + "/" + rel).getBytes(StandardCharsets.UTF_8);
                        long size = attrs.size();
                        // CRC pre-pass (sequential read, no compression) so the
                        // header carries real values and every unzipper works.
                        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                        try (InputStream ci = Files.newInputStream(file)) {
                            byte[] cbuf = new byte[65536];
                            int cn;
                            while ((cn = ci.read(cbuf)) >= 0) crc.update(cbuf, 0, cn);
                        }
                        long crcVal = crc.getValue();
                        offsets.add(pos[0]);
                        writeLe32(chunked, 0x04034b50); // local header
                        writeLe16(chunked, 20);
                        writeLe16(chunked, 0x800); // utf8 names
                        writeLe16(chunked, 0); // method STORED
                        writeLe16(chunked, 0); // time
                        writeLe16(chunked, 0); // date
                        writeLe32(chunked, crcVal);
                        writeLe32(chunked, size);
                        writeLe32(chunked, size);
                        writeLe16(chunked, nameB.length);
                        writeLe16(chunked, 0);
                        chunked.write(nameB, 0, nameB.length);
                        pos[0] += 30 + nameB.length;
                        try (InputStream fi = Files.newInputStream(file)) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = fi.read(buf)) >= 0) {
                                chunked.write(buf, 0, n);
                                pos[0] += n;
                            }
                        }
                        names.add(baseName + "/" + rel);
                        crcs.add(crcVal);
                        sizes.add(size);
                    } catch (IOException ignored) {}
                    return FileVisitResult.CONTINUE;
                }
            });
            long centralStart = pos[0];
            for (int i = 0; i < names.size(); i++) {
                byte[] nameB = names.get(i).getBytes(StandardCharsets.UTF_8);
                writeLe32(chunked, 0x02014b50); // central header
                writeLe16(chunked, 20); // version made by
                writeLe16(chunked, 20); // version needed
                writeLe16(chunked, 0x800); // utf8
                writeLe16(chunked, 0); // method STORED
                writeLe16(chunked, 0); // time
                writeLe16(chunked, 0); // date
                writeLe32(chunked, crcs.get(i));
                writeLe32(chunked, sizes.get(i));
                writeLe32(chunked, sizes.get(i));
                writeLe16(chunked, nameB.length);
                writeLe16(chunked, 0); // extra
                writeLe16(chunked, 0); // comment
                writeLe16(chunked, 0); // disk
                writeLe16(chunked, 0); // int attr
                writeLe32(chunked, 0); // ext attr
                writeLe32(chunked, offsets.get(i));
                chunked.write(nameB, 0, nameB.length);
                pos[0] += 46 + nameB.length;
            }
            long centralSize = pos[0] - centralStart;
            writeLe32(chunked, 0x06054b50); // end of central dir
            writeLe16(chunked, 0);
            writeLe16(chunked, 0);
            writeLe16(chunked, names.size());
            writeLe16(chunked, names.size());
            writeLe32(chunked, centralSize);
            writeLe32(chunked, centralStart);
            writeLe16(chunked, 0);
            chunked.flush();
        } catch (IOException ignored) {}
        chunked.finish();
    }

    private static void writeLe16(OutputStream o, long v) throws IOException {
        o.write((int) (v & 0xFF));
        o.write((int) ((v >> 8) & 0xFF));
    }

    private static void writeLe32(OutputStream o, long v) throws IOException {
        o.write((int) (v & 0xFF));
        o.write((int) ((v >> 8) & 0xFF));
        o.write((int) ((v >> 16) & 0xFF));
        o.write((int) ((v >> 24) & 0xFF));
    }

    private static final class ChunkedOut extends OutputStream {
        private final OutputStream out;
        private boolean closed;

        ChunkedOut(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len == 0) return;
            out.write(Integer.toHexString(len).getBytes("ISO-8859-1"));
            out.write("\r\n".getBytes("ISO-8859-1"));
            out.write(b, off, len);
            out.write("\r\n".getBytes("ISO-8859-1"));
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        void finish() throws IOException {
            if (closed) return;
            closed = true;
            out.write("0\r\n\r\n".getBytes("ISO-8859-1"));
            out.flush();
        }
    }

    // ------------------------------------------------------------- POST apis

    private void handlePost(Req r, InputStream in, OutputStream out) throws IOException {
        String p = r.path;
        if ("/api/login".equals(p)) {
            String body = readBody(in, r);
            Map<String, String> j = parseJson(body);
            String u = j.getOrDefault("user", "");
            String pVal = j.getOrDefault("password", "");
            String expU = pw.getUser();
            String expP = pw.getPassword();
            boolean valid = (expP == null || expP.isEmpty()) || ((u.isEmpty() || expU.equals(u)) && expP.equals(pVal));
            if (valid) {
                sendStatus(out, 200, "OK", linked(
                        "Content-Type", "application/json; charset=utf-8",
                        "Set-Cookie", "fastshare_token=" + sessionToken + "; Path=/; SameSite=Lax; Max-Age=2592000",
                        "Access-Control-Allow-Origin", "*",
                        "Connection", "close"));
                out.write(("{\"ok\":true,\"token\":\"" + sessionToken + "\"}").getBytes(StandardCharsets.UTF_8));
            } else {
                sendJson(out, "{\"ok\":false,\"error\":\"Invalid password\"}", 401);
            }
            return;
        }
        if ("/api/raw".equals(p)) {
            // Fast path: raw bytes, no multipart unpacking. Saved via .part + rename
            // so an interrupted transfer never leaves a fake-complete file.
            String target = confinedPath(r.query.get("path"));
            if (target.isEmpty()) {
                List<Root> roots = computeRoots();
                target = roots.isEmpty() ? Environment.getExternalStorageDirectory().getAbsolutePath()
                        : roots.get(0).path;
            }
            File td = new File(target);
            if (td.isFile()) td = td.getParentFile();
            List<String> parts = cleanRel(r.query.get("name"));
            if (td == null || parts.isEmpty()) {
                sendJson(out, "{\"error\":\"bad name\"}", 400);
                return;
            }
            File dir = td;
            for (int i = 0; i < parts.size() - 1; i++) dir = new File(dir, parts.get(i));
            if (!dir.isDirectory() && !dir.mkdirs()) {
                sendJson(out, "{\"error\":\"bad target\"}", 400);
                return;
            }
            long total;
            try {
                total = Long.parseLong(r.headers.getOrDefault("content-length", "-1"));
            } catch (NumberFormatException e) {
                total = -1;
            }
            if (total < 0 || total > 20L * 1024 * 1024 * 1024) {
                sendJson(out, "{\"error\":\"bad length\"}", 400);
                return;
            }
            File tmp = new File(dir, parts.get(parts.size() - 1) + ".part-" + System.nanoTime());
            byte[] buf = new byte[2 * 1024 * 1024];
            long left = total;
            try (BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(tmp), 2 * 1024 * 1024)) {
                while (left > 0) {
                    int toRead = (int) Math.min(buf.length, left);
                    int n = in.read(buf, 0, toRead);
                    if (n < 0) break;
                    fos.write(buf, 0, n);
                    left -= n;
                }
                fos.flush();
            } catch (IOException e) {
                tmp.delete();
                sendJson(out, "{\"error\":\"upload failed\"}", 500);
                return;
            }
            if (left != 0) {
                tmp.delete();
                sendJson(out, "{\"error\":\"truncated\"}", 500);
                return;
            }
            File dest = uniqueFile(dir, parts.get(parts.size() - 1));
            if (!tmp.renameTo(dest)) {
                tmp.delete();
                sendJson(out, "{\"error\":\"save failed\"}", 500);
                return;
            }
            sendJson(out, "{\"ok\":true,\"saved\":[" + js(dest.getName()) + "]}", 200);
            return;
        }
        if ("/api/upload".equals(p)) {
            String target = confinedPath(r.query.get("path"));
            if (target.isEmpty()) {
                List<Root> roots = computeRoots();
                target = roots.isEmpty() ? Environment.getExternalStorageDirectory().getAbsolutePath()
                        : roots.get(0).path;
            }
            File td = new File(target);
            if (td.isFile()) td = td.getParentFile();
            if (td == null || (!td.isDirectory() && !td.mkdirs())) {
                sendJson(out, "{\"error\":\"bad target\"}", 400);
                return;
            }
            long total;
            try {
                total = Long.parseLong(r.headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException e) {
                total = 0;
            }
            String ct = r.headers.getOrDefault("content-type", "");
            if (!ct.contains("multipart/form-data") || !ct.contains("boundary=") || total <= 0
                    || total > 20L * 1024 * 1024 * 1024) {
                sendJson(out, "{\"error\":\"bad upload\"}", 400);
                return;
            }
            String boundary = ct.substring(ct.indexOf("boundary=") + 9).trim();
            if ((boundary.startsWith("\"") && boundary.endsWith("\"")) && boundary.length() > 1) {
                boundary = boundary.substring(1, boundary.length() - 1);
            }
            try {
                List<String> saved = receiveMultipart(in, total, boundary, td);
                sendJson(out, "{\"ok\":true,\"saved\":" + strListJson(saved) + "}", 200);
            } catch (IOException e) {
                sendJson(out, "{\"error\":\"upload failed\"}", 500);
            }
            return;
        }
        String body = readBody(in, r);
        Map<String, String> j = parseJson(body);
        if ("/api/mkdir".equals(p)) {
            String base = confinedPath(j.get("path"));
            String name = baseName((j.get("name") == null ? "" : j.get("name")).trim());
            if (base.isEmpty() || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                sendJson(out, "{\"error\":\"bad name\"}", 400);
                return;
            }
            File d = new File(base, name);
            if (d.exists() || !d.mkdirs()) {
                sendJson(out, "{\"error\":\"failed\"}", 500);
                return;
            }
            sendJson(out, "{\"ok\":true}", 200);
            return;
        }
        if ("/api/delete".equals(p)) {
            String ap = confinedPath(j.get("path"));
            if (ap.isEmpty() || isRootPath(ap)) {
                sendJson(out, "{\"error\":\"refuse to delete storage root\"}", 400);
                return;
            }
            if (!deleteRecursive(new File(ap))) {
                sendJson(out, "{\"error\":\"failed\"}", 500);
                return;
            }
            sendJson(out, "{\"ok\":true}", 200);
            return;
        }
        if ("/api/delete_many".equals(p)) {
            List<String> paths = parseJsonArray(body, "paths");
            int ok = 0;
            List<String> fail = new ArrayList<>();
            for (int i = 0; i < Math.min(paths.size(), 500); i++) {
                String ap = confinedPath(paths.get(i));
                if (ap.isEmpty() || isRootPath(ap) || !deleteRecursive(new File(ap))) {
                    fail.add(paths.get(i));
                } else ok++;
            }
            sendJson(out, "{\"ok\":true,\"deleted\":" + ok + ",\"failed\":" + strListJson(fail) + "}", 200);
            return;
        }
        if ("/api/rename".equals(p)) {
            String ap = confinedPath(j.get("path"));
            String nn = baseName((j.get("newName") == null ? "" : j.get("newName")).trim());
            if (ap.isEmpty() || nn.isEmpty() || ".".equals(nn) || "..".equals(nn)) {
                sendJson(out, "{\"error\":\"bad name\"}", 400);
                return;
            }
            File src = new File(ap);
            if (!src.renameTo(new File(src.getParentFile(), nn))) {
                sendJson(out, "{\"error\":\"failed\"}", 500);
                return;
            }
            sendJson(out, "{\"ok\":true}", 200);
            return;
        }
        sendJson(out, "{\"error\":\"not found\"}", 404);
    }

    private String readBody(InputStream in, Req r) throws IOException {
        long total;
        try {
            total = Long.parseLong(r.headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException e) {
            return "";
        }
        if (total <= 0 || total > 10 * 1024 * 1024) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long left = total;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) break;
            b.write(buf, 0, n);
            left -= n;
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Streaming multipart receiver (low RAM): boundary search with keep-tail,
     * files written straight to disk, subfolders from relative names kept.
     */
    private List<String> receiveMultipart(InputStream in, long total, String boundary, File targetDir)
            throws IOException {
        byte[] bnd = ("--" + boundary).getBytes("ISO-8859-1");
        byte[] marker = ("\r\n--" + boundary).getBytes("ISO-8859-1");
        byte[] buf = new byte[CHUNK + marker.length + 4];
        int blen = 0;
        long[] rem = new long[]{total};
        List<String> saved = new ArrayList<>();
        FileOutputStream cur = null;
        String curName = null;

        blen += fill(in, buf, blen, (int) Math.min(CHUNK, rem[0]), rem);
        while (true) {
            if (cur == null) {
                int hi = indexOf(buf, 0, blen, "\r\n\r\n".getBytes("ISO-8859-1"));
                while (hi < 0 && rem[0] > 0) {
                    blen += fill(in, buf, blen, (int) Math.min(CHUNK, rem[0]), rem);
                    hi = indexOf(buf, 0, blen, "\r\n\r\n".getBytes("ISO-8859-1"));
                }
                if (hi < 0) break;
                String head = new String(buf, 0, hi, StandardCharsets.UTF_8);
                int shift = hi + 4;
                System.arraycopy(buf, shift, buf, 0, blen - shift);
                blen -= shift;
                String fn = partFilename(head);
                if (fn != null) {
                    String[] parts = fn.replace('\\', '/').split("/");
                    List<String> clean = new ArrayList<>();
                    for (String s : parts) {
                        if (!s.isEmpty() && !".".equals(s) && !"..".equals(s)) clean.add(s);
                    }
                    if (!clean.isEmpty()) {
                        File dir = targetDir;
                        for (int i = 0; i < clean.size() - 1; i++) {
                            dir = new File(dir, clean.get(i));
                        }
                        if (!dir.isDirectory() && !dir.mkdirs()) dir = targetDir;
                        File fp = uniqueFile(dir, clean.get(clean.size() - 1));
                        cur = new FileOutputStream(fp);
                        curName = fp.getName();
                    }
                }
                if (cur == null) {
                    // form field or bad part: skip to next boundary
                    int bi = indexOf(buf, 0, blen, bnd);
                    while (bi < 0 && rem[0] > 0) {
                        int add = fill(in, buf, blen, (int) Math.min(CHUNK, rem[0]), rem);
                        blen += add;
                        if (blen > bnd.length + 8) {
                            int drop = blen - (bnd.length + 8);
                            System.arraycopy(buf, drop, buf, 0, blen - drop);
                            blen -= drop;
                        }
                        bi = indexOf(buf, 0, blen, bnd);
                    }
                    if (bi >= 0) {
                        System.arraycopy(buf, bi, buf, 0, blen - bi);
                        blen -= bi;
                    } else break;
                }
            } else {
                int bi = indexOf(buf, 0, blen, marker);
                if (bi >= 0) {
                    cur.write(buf, 0, bi);
                    cur.close();
                    cur = null;
                    saved.add(curName);
                    curName = null;
                    int shift = bi + 2; // keep --boundary...
                    System.arraycopy(buf, shift, buf, 0, blen - shift);
                    blen -= shift;
                    if (startsWith(buf, 0, blen, concat(bnd, "--".getBytes("ISO-8859-1")))) break;
                    continue;
                }
                if (rem[0] > 0) {
                    int keep = marker.length + 2;
                    if (blen > keep) {
                        cur.write(buf, 0, blen - keep);
                        System.arraycopy(buf, blen - keep, buf, 0, keep);
                        blen = keep;
                    }
                    blen += fill(in, buf, blen, (int) Math.min(CHUNK, rem[0]), rem);
                } else {
                    int bi2 = indexOf(buf, 0, blen, bnd);
                    if (bi2 >= 0) {
                        int end = bi2;
                        if (end >= 2 && buf[end - 2] == '\r' && buf[end - 1] == '\n') end -= 2;
                        cur.write(buf, 0, end);
                    } else {
                        cur.write(buf, 0, blen);
                    }
                    cur.close();
                    cur = null;
                    saved.add(curName == null ? "file" : curName);
                    curName = null;
                    break;
                }
            }
            if (rem[0] <= 0 && blen == 0 && cur == null) break;
            // Refill, but never beyond the fixed buffer (blen can be near CHUNK here).
            int space = buf.length - blen;
            if (rem[0] > 0 && space > 0) {
                blen += fill(in, buf, blen,
                        (int) Math.min(space, Math.min(CHUNK, rem[0])), rem);
            }
            if (rem[0] <= 0 && blen == 0) break;
        }
        if (cur != null) {
            try { cur.close(); } catch (IOException ignored) {}
        }
        return saved;
    }

    private int fill(InputStream in, byte[] buf, int blen, int want, long[] rem) throws IOException {
        int got = 0;
        while (want > 0 && rem[0] > 0) {
            int n = in.read(buf, blen + got, (int) Math.min(want, rem[0]));
            if (n < 0) break;
            got += n;
            want -= n;
            rem[0] -= n;
        }
        return got;
    }

    private static String partFilename(String head) {
        Matcher m = Pattern.compile("filename\\*=UTF-8''([^;\\r\\n]+)").matcher(head);
        if (m.find()) {
            try {
                return URLDecoder.decode(m.group(1), "UTF-8");
            } catch (Exception ignored) {}
        }
        m = Pattern.compile("filename=\"([^\"]*)\"").matcher(head);
        if (m.find()) return m.group(1);
        return null;
    }

    private static List<String> cleanRel(String rel) {
        List<String> clean = new ArrayList<>();
        if (rel == null) return clean;
        for (String s : rel.replace('\\', '/').split("/")) {
            if (!s.isEmpty() && !".".equals(s) && !"..".equals(s)) clean.add(s);
        }
        return clean;
    }

    private static File uniqueFile(File dir, String name) {
        if (name.isEmpty()) name = "file";
        File fp = new File(dir, name);
        if (!fp.exists()) return fp;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int k = 1;
        while (fp.exists()) {
            fp = new File(dir, stem + " (" + (k++) + ")" + ext);
        }
        return fp;
    }

    private static int indexOf(byte[] hay, int off, int len, byte[] needle) {
        outer:
        for (int i = off; i + needle.length <= off + len; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static boolean startsWith(byte[] hay, int off, int len, byte[] needle) {
        if (needle.length > len) return false;
        for (int j = 0; j < needle.length; j++) {
            if (hay[off + j] != needle[j]) return false;
        }
        return true;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    // ------------------------------------------------------------- roots etc.

    private static final class Root {
        final String name, path, icon;
        final long total, free, used;

        Root(String name, String path, String icon) {
            this.name = name;
            this.path = path;
            this.icon = icon;
            File f = new File(path);
            long t = 0, fr = 0;
            try {
                t = f.getTotalSpace();
                fr = f.getFreeSpace();
            } catch (Exception ignored) {}
            this.total = t;
            this.free = fr;
            this.used = Math.max(0, t - fr);
        }
    }

    private List<Root> computeRoots() {
        List<Root> roots = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        File primary = Environment.getExternalStorageDirectory();
        addRoot(roots, seen, "Internal storage", "/sdcard", "\uD83D\uDCF1");
        addRoot(roots, seen, "Internal storage", "/storage/emulated/0", "\uD83D\uDCF1");
        if (primary != null) {
            addRoot(roots, seen, "Internal storage", primary.getAbsolutePath(), "\uD83D\uDCF1");
        }
        File[] ext = appCtx.getExternalFilesDirs(null);
        if (ext != null) {
            for (File f : ext) {
                if (f == null) continue;
                File v = f;
                // .../storage/XXXX-XXXX/Android/data/<pkg>/files -> volume root (up 4)
                for (int i = 0; i < 4 && v != null; i++) v = v.getParentFile();
                if (v == null) continue;
                String label = "SD card";
                String vn = v.getName();
                if (!vn.isEmpty() && !"0".equals(vn) && !"emulated".equals(vn)
                        && !"self".equals(vn) && !"storage".equals(vn)) {
                    label = "SD card (" + vn + ")";
                }
                addRoot(roots, seen, label, v.getAbsolutePath(), "\uD83D\uDCBE");
            }
        }
        String[] subs = {"Download", "DCIM", "Pictures", "Music", "Movies", "Documents"};
        String[] bases = {"/sdcard", "/storage/emulated/0",
                primary == null ? "" : primary.getAbsolutePath()};
        for (String b : bases) {
            if (b.isEmpty()) continue;
            for (String s : subs) {
                addRoot(roots, seen, s, b + "/" + s, "\uD83D\uDCC1");
            }
        }
        return roots;
    }

    private static void addRoot(List<Root> roots, List<String> seen, String name, String path,
                                String icon) {
        try {
            File f = new File(path);
            if (!f.exists() || !f.canRead()) return;
            String canon = f.getCanonicalPath();
            for (String s : seen) {
                if (s.equals(canon)) return;
            }
            seen.add(canon);
            roots.add(new Root(name, f.getAbsolutePath(), icon));
        } catch (Exception ignored) {}
    }

    private String rootsJson(List<Root> roots) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < roots.size(); i++) {
            if (i > 0) sb.append(',');
            Root r = roots.get(i);
            sb.append("{\"name\":").append(js(r.name))
                    .append(",\"path\":").append(js(r.path))
                    .append(",\"total\":").append(r.total)
                    .append(",\"free\":").append(r.free)
                    .append(",\"used\":").append(r.used)
                    .append(",\"icon\":").append(js(r.icon)).append('}');
        }
        return sb.append(']').toString();
    }

    private String quickJson() {
        File primary = Environment.getExternalStorageDirectory();
        String base = primary == null ? "/sdcard" : primary.getAbsolutePath();
        String[] subs = {"Download", "DCIM", "Pictures", "Music", "Movies", "Documents"};
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String s : subs) {
            File f = new File(base, s);
            if (!f.isDirectory()) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":").append(js(s)).append(",\"path\":")
                    .append(js(f.getAbsolutePath())).append('}');
        }
        return sb.append(']').toString();
    }

    private boolean isRootPath(String ap) {
        for (Root r : computeRoots()) {
            if (r.path.equals(ap)) return true;
        }
        return false;
    }

    private String breadcrumbsJson(String path) {
        StringBuilder sb = new StringBuilder("[");
        if (path.startsWith("/")) {
            sb.append("{\"name\":\"/\",\"path\":\"/\"}");
            String rest = path.substring(1);
            if (!rest.isEmpty()) {
                String cur = "";
                for (String part : rest.split("/")) {
                    if (part.isEmpty()) continue;
                    cur += "/" + part;
                    sb.append(",{\"name\":").append(js(part)).append(",\"path\":")
                            .append(js(cur)).append('}');
                }
            }
        }
        return sb.append(']').toString();
    }

    private static String safePath(String p) {
        if (p == null || p.isEmpty()) return "";
        try {
            String d = URLDecoder.decode(p.contains("%") ? p : p, "UTF-8");
            return new File(d).getCanonicalPath();
        } catch (Exception e) {
            return "";
        }
    }

    private String confinedPath(String raw) {
        String candidate = safePath(raw);
        if (candidate.isEmpty()) return "";
        try {
            File target = new File(candidate).getCanonicalFile();
            for (Root root : computeRoots()) {
                File allowed = new File(root.path).getCanonicalFile();
                if (target.equals(allowed) || target.toPath().startsWith(allowed.toPath())) {
                    return target.getPath();
                }
            }
        } catch (IOException ignored) {}
        return "";
    }

    // ------------------------------------------------------------------ misc

    private static String deviceLabel() {
        String model = Build.MODEL == null ? "" : Build.MODEL;
        return ("Android " + model).trim();
    }

    private static String lanIp() {
        try {
            Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[32768];
        int n;
        while ((n = in.read(buf)) >= 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private static boolean deleteRecursive(File f) {
        if (!f.exists()) return true;
        if (f.isDirectory() && !isSymlink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (!deleteRecursive(k)) return false;
                }
            }
        }
        return f.delete();
    }

    private static boolean isSymlink(File f) {
        try {
            return !f.getCanonicalPath().equals(f.getAbsolutePath()) && f.isDirectory()
                    && java.nio.file.Files.isSymbolicLink(f.toPath());
        } catch (Exception e) {
            return false;
        }
    }

    private static String baseName(String s) {
        int i = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        return i >= 0 ? s.substring(i + 1) : s;
    }

    /** Minimal JSON string escaper. */
    private static String js(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String strListJson(List<String> l) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(js(l.get(i)));
        }
        return sb.append(']').toString();
    }

    /** Tiny flat-JSON reader: string values + string arrays only. */
    private static Map<String, String> parseJson(String body) {
        Map<String, String> m = new HashMap<>();
        if (body == null) return m;
        Matcher mm = Pattern.compile("\"(path|name|newName|user|password)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                .matcher(body);
        while (mm.find()) {
            m.put(mm.group(1), unescape(mm.group(2)));
        }
        return m;
    }

    private static List<String> parseJsonArray(String body, String key) {
        List<String> out = new ArrayList<>();
        if (body == null) return out;
        Matcher mm = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL)
                .matcher(body);
        if (!mm.find()) return out;
        Matcher im = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"").matcher(mm.group(1));
        while (im.find()) out.add(unescape(im.group(1)));
        return out;
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append('u');
                            }
                        } else sb.append('u');
                        break;
                    default: sb.append(n);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }
}
