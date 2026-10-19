package com.fastshare.app;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Automatic Zero-Configuration Device Discovery for Fast Share.
 * Broadcasts presence and discovers Windows PCs, laptops, and Android phones on local Wi-Fi.
 */
public class DiscoveryManager {

    public static final int DISCOVERY_PORT = 8002;

    public static class Device {
        public final String name;
        public final String ip;
        public final int port;
        public final String os;
        public final String user;
        public long lastSeen;

        public Device(String name, String ip, int port, String os, String user, long lastSeen) {
            this.name = name;
            this.ip = ip;
            this.port = port;
            this.os = os;
            this.user = user;
            this.lastSeen = lastSeen;
        }

        public boolean isWindows() {
            return os != null && os.toLowerCase().contains("win");
        }
    }

    public interface Callback {
        void onDevicesChanged(List<Device> devices);
    }

    private final Context ctx;
    private volatile Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Device> devices = new ConcurrentHashMap<>();

    private volatile boolean running = false;
    private DatagramSocket socket;
    private WifiManager.MulticastLock multicastLock;
    private Thread listenThread;
    private Thread beaconThread;

    /** Bounded, daemon probe pool — raw `new Thread()` here caused an OOM crash
     *  (tens of thousands of threads, one per ARP entry per 1.5s cycle). */
    private ExecutorService probePool;
    private final Set<String> probing = ConcurrentHashMap.newKeySet();
    private long lastProbeAt = 0L;
    private static final long PROBE_INTERVAL_MS = 5000;

    public DiscoveryManager(Context ctx, Callback callback) {
        this.ctx = ctx.getApplicationContext();
        this.callback = callback;
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
        mainHandler.removeCallbacks(notifyRunnable);
        mainHandler.post(notifyRunnable);
    }

    public List<Device> getActiveDevices() {
        long now = System.currentTimeMillis();
        List<Device> list = new ArrayList<>();
        for (Device d : devices.values()) {
            if (now - d.lastSeen < 8000) {
                list.add(d);
            }
        }
        return list;
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("FastShareDiscovery");
                multicastLock.setReferenceCounted(true);
                multicastLock.acquire();
            }
        } catch (Exception ignored) {}

        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), DISCOVERY_PORT));
        } catch (Exception e) {
            try {
                if (socket == null || socket.isClosed()) {
                    socket = new DatagramSocket(DISCOVERY_PORT);
                    socket.setBroadcast(true);
                }
            } catch (Exception ignored) {}
        }

        listenThread = new Thread(this::listenLoop, "fs-udp-listen");
        listenThread.setDaemon(true);
        listenThread.start();

        beaconThread = new Thread(this::beaconLoop, "fs-udp-beacon");
        beaconThread.setDaemon(true);
        beaconThread.start();

        probePool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "fs-probe");
            t.setDaemon(true);
            return t;
        });

        triggerScan();
    }

    public synchronized void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            try { socket.close(); } catch (Exception ignored) {}
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            try { multicastLock.release(); } catch (Exception ignored) {}
        }
        if (probePool != null) {
            probePool.shutdownNow();
            probePool = null;
        }
        probing.clear();
        devices.clear();
        mainHandler.removeCallbacks(notifyRunnable);
        mainHandler.post(notifyRunnable);
    }

    public void triggerScan() {
        ExecutorService pool = probePool;
        if (pool == null || pool.isShutdown()) return;
        try {
            pool.execute(() -> {
                try {
                    String ping = new JSONObject()
                            .put("proto", "fastshare_v1")
                            .put("action", "ping")
                            .toString();
                    sendBroadcast(ping.getBytes(StandardCharsets.UTF_8));
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private void listenLoop() {
        byte[] buf = new byte[4096];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                if (socket == null || socket.isClosed()) break;
                socket.receive(packet);
                String data = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                String senderIp = packet.getAddress().getHostAddress();

                String myIp = getLocalIp();
                if (senderIp != null && senderIp.equals(myIp)) {
                    continue; // Ignore our own broadcast
                }

                JSONObject obj = new JSONObject(data);
                if ("fastshare_v1".equals(obj.optString("proto"))) {
                    String name = obj.optString("name", "Unknown Device");
                    String os = obj.optString("os", "device");
                    int port = obj.optInt("port", 8000);
                    String user = obj.optString("user", "share");
                    String action = obj.optString("action", "");

                    Device existing = devices.get(senderIp);
                    boolean isNewOrChanged = (existing == null)
                            || !name.equals(existing.name)
                            || port != existing.port
                            || !os.equals(existing.os);

                    Device dev = new Device(name, senderIp, port, os, user, System.currentTimeMillis());
                    devices.put(senderIp, dev);
                    if (isNewOrChanged) {
                        notifyDevices();
                    }

                    // Only reply if sender sent an explicit ping query to prevent infinite ping-pong packet storm
                    if ("ping".equals(action)) {
                        sendBeaconTo(packet.getAddress(), DISCOVERY_PORT, "pong");
                    }
                }
            } catch (Exception e) {
                if (running) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    private void beaconLoop() {
        while (running) {
            try {
                String myIp = getLocalIp();
                int myPort = Prefs.getPort(ctx);
                String myUser = Prefs.getUser(ctx);
                String myName = "Android " + Build.MODEL;

                String beacon = new JSONObject()
                        .put("proto", "fastshare_v1")
                        .put("action", "beacon")
                        .put("name", myName)
                        .put("os", "android")
                        .put("ip", myIp != null ? myIp : "")
                        .put("port", myPort)
                        .put("user", myUser)
                        .toString();

                byte[] b = beacon.getBytes(StandardCharsets.UTF_8);
                sendBroadcast(b);

                // Direct unicast to all known peers (bypasses router broadcast drops)
                for (Device d : devices.values()) {
                    try {
                        sendBeaconTo(InetAddress.getByName(d.ip), DISCOVERY_PORT, "beacon");
                    } catch (Exception ignored) {}
                }

                // Prune devices not seen in last 12 seconds
                long now = System.currentTimeMillis();
                boolean changed = false;
                Iterator<Map.Entry<String, Device>> it = devices.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Device> entry = it.next();
                    if (now - entry.getValue().lastSeen > 12000) {
                        it.remove();
                        changed = true;
                    }
                }
                if (changed) {
                    notifyDevices();
                }

                // Active hybrid ARP & subnet sweep
                probeArpCache();

                Thread.sleep(1500);
            } catch (InterruptedException ie) {
                break;
            } catch (Exception e) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
    }

    public String getDevicesJson() {
        long now = System.currentTimeMillis();
        org.json.JSONArray arr = new org.json.JSONArray();
        for (Device d : devices.values()) {
            if (now - d.lastSeen < 8000) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("name", d.name);
                    o.put("ip", d.ip);
                    o.put("port", d.port);
                    o.put("os", d.os);
                    o.put("user", d.user);
                    arr.put(o);
                } catch (Exception ignored) {}
            }
        }
        return arr.toString();
    }

    private void probeArpCache() {
        // Throttle: at most one sweep per PROBE_INTERVAL_MS, regardless of beacon cadence
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastProbeAt < PROBE_INTERVAL_MS) return;
        lastProbeAt = nowMs;

        ExecutorService pool = probePool;
        if (pool == null || pool.isShutdown()) return;

        String myIp = getLocalIp();
        java.util.Set<String> ips = new java.util.HashSet<>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader("/proc/net/arp"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] tokens = line.split("\\s+");
                if (tokens.length >= 4 && tokens[0].matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
                    String ip = tokens[0];
                    if (!ip.equals(myIp) && !ip.endsWith(".255")) {
                        ips.add(ip);
                    }
                }
            }
        } catch (Exception ignored) {}

        // Probe last connected host from user preferences
        try {
            String lastHost = Prefs.getConnHost(ctx);
            if (lastHost != null && !lastHost.isEmpty()) {
                String clean = lastHost.replaceAll("^https?://", "").replaceAll("/.*$", "").replaceAll(":.*$", "").trim();
                if (clean.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$") && !clean.equals(myIp)) {
                    ips.add(clean);
                }
            }
        } catch (Exception ignored) {}

        // Probe default Wi-Fi gateway IP
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.getDhcpInfo() != null) {
                int gw = wm.getDhcpInfo().gateway;
                if (gw != 0) {
                    String gwIp = (gw & 0xFF) + "." + ((gw >> 8) & 0xFF) + "." + ((gw >> 16) & 0xFF) + "." + ((gw >> 24) & 0xFF);
                    if (!gwIp.equals(myIp)) ips.add(gwIp);
                }
            }
        } catch (Exception ignored) {}

        for (String ip : ips) {
            if (!probing.add(ip)) continue; // already probing this host
            try {
                pool.execute(() -> {
                    try {
                        java.net.URL url = new java.net.URL("http://" + ip + ":8000/api/info");
                        java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                        c.setConnectTimeout(400);
                        c.setReadTimeout(400);
                        c.setRequestMethod("GET");
                        if (c.getResponseCode() == 200) {
                            java.io.InputStream in = c.getInputStream();
                            byte[] b = new byte[1024];
                            int n = in.read(b);
                            in.close();
                            if (n > 0) {
                                String resp = new String(b, 0, n, StandardCharsets.UTF_8);
                                JSONObject obj = new JSONObject(resp);
                                String name = obj.optString("device", obj.optString("hostname", "PC"));
                                String os = obj.optString("platform", "windows");
                                int port = obj.optInt("port", 8000);
                                Device existing = devices.get(ip);
                                boolean isNewOrChanged = (existing == null)
                                        || !name.equals(existing.name)
                                        || port != existing.port;
                                Device dev = new Device(name, ip, port, os, "share", System.currentTimeMillis());
                                devices.put(ip, dev);
                                if (isNewOrChanged) {
                                    notifyDevices();
                                }
                            }
                        }
                        c.disconnect();
                    } catch (Throwable ignored) {
                    } finally {
                        probing.remove(ip);
                    }
                });
            } catch (Throwable rejected) {
                probing.remove(ip); // pool shutting down or saturated
            }
        }
    }

    private void sendBeaconTo(InetAddress addr, int port) {
        sendBeaconTo(addr, port, "beacon");
    }

    private void sendBeaconTo(InetAddress addr, int port, String action) {
        try {
            if (socket == null || socket.isClosed()) return;
            String myIp = getLocalIp();
            String beacon = new JSONObject()
                    .put("proto", "fastshare_v1")
                    .put("action", action)
                    .put("name", "Android " + Build.MODEL)
                    .put("os", "android")
                    .put("ip", myIp != null ? myIp : "")
                    .put("port", Prefs.getPort(ctx))
                    .put("user", Prefs.getUser(ctx))
                    .toString();
            byte[] b = beacon.getBytes(StandardCharsets.UTF_8);
            DatagramPacket p = new DatagramPacket(b, b.length, addr, port);
            socket.send(p);
        } catch (Exception ignored) {}
    }

    private void sendBroadcast(byte[] data) {
        try {
            if (socket == null || socket.isClosed()) return;
            // Global broadcast
            DatagramPacket p1 = new DatagramPacket(data, data.length,
                    InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
            socket.send(p1);

            // Subnet broadcasts
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress bcast = ia.getBroadcast();
                    if (bcast != null) {
                        DatagramPacket p2 = new DatagramPacket(data, data.length, bcast, DISCOVERY_PORT);
                        socket.send(p2);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private final Runnable notifyRunnable = () -> {
        List<Device> list = getActiveDevices();
        if (callback != null) {
            callback.onDevicesChanged(list);
        }
    };

    private void notifyDevices() {
        mainHandler.removeCallbacks(notifyRunnable);
        mainHandler.postDelayed(notifyRunnable, 150);
    }

    private String getLocalIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
