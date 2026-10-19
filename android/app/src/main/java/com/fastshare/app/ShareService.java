package com.fastshare.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Foreground service hosting the file server.
 * START_STICKY + onTaskRemoved restart + boot receiver = stays alive when the
 * app is closed or swiped away. Holds a Wi-Fi lock so transfers never stall.
 */
public class ShareService extends Service {

    public static final String ACTION_STOP = "com.fastshare.app.STOP";
    private static final String CHANNEL = "share";
    private static final int NOTIF_ID = 41;

    private ShareServer server;
    private static volatile ShareServer serverInstance;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private static DiscoveryManager discovery;

    public static DiscoveryManager getDiscovery() {
        return discovery;
    }

    public static ShareServer getServer() {
        return serverInstance;
    }

    public static void start(Context c) {
        ContextCompat.startForegroundService(c, new Intent(c, ShareService.class));
    }

    public static void stop(Context c) {
        c.startService(new Intent(c, ShareService.class).setAction(ACTION_STOP));
    }

    public static boolean isUp() {
        return running;
    }

    private static volatile boolean running = false;

    public static String getServerUrl(Context c) {
        String ip = getLanIp();
        int port = Prefs.getPort(c);
        return ip != null ? ("http://" + ip + ":" + port) : ("http://localhost:" + port);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "File sharing",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps Fast Share reachable on your Wi-Fi");
        nm.createNotificationChannel(ch);
        WifiManager wm = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        int wifiMode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wifiMode = WifiManager.WIFI_MODE_FULL_LOW_LATENCY;
        }
        wifiLock = wm.createWifiLock(wifiMode, "FastShare:lock");
        wifiLock.setReferenceCounted(false);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FastShare:cpu");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (server != null && server.isRunning()) {
            return START_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, baseNotif(null).build(), fgsType());
            } else {
                startForeground(NOTIF_ID, baseNotif(null).build());
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int port = Prefs.getPort(this);
        server = new ShareServer(this, port, new ShareServer.AuthProvider() {
            @Override public String getUser() { return Prefs.getUser(ShareService.this); }
            @Override public String getPassword() { return Prefs.getPassword(ShareService.this); }
        });
        if (!server.start()) {
            // Port busy: show a one-shot note and stop.
            Notification n = baseNotif("Port " + port + " is busy — change it in the app")
                    .build();
            getSystemService(NotificationManager.class).notify(NOTIF_ID, n);
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        serverInstance = server;
        if (discovery == null) {
            discovery = new DiscoveryManager(this, null);
            discovery.start();
        }
        if (!wifiLock.isHeld()) wifiLock.acquire();
        if (!wakeLock.isHeld()) wakeLock.acquire(4 * 60 * 60 * 1000L);
        return START_STICKY;
    }

    private int fgsType() {
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
    }

    public static String getLanIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private NotificationCompat.Builder baseNotif(@Nullable String override) {
        String ip = getLanIp();
        String url = ip != null ? ("http://" + ip + ":" + Prefs.getPort(this)) : ("http://localhost:" + Prefs.getPort(this));
        String text = override != null ? override : ("Sharing at " + url + " • Tap to Open");
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent piOpen = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ShareService.class).setAction(ACTION_STOP);
        PendingIntent piStop = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Fast Share is Active")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                    text + "\nLogin: " + Prefs.getUser(this)
                    + " • Open app to change password or stop server."))
                .setContentIntent(piOpen)
                .addAction(0, "Open", piOpen)
                .addAction(0, "Stop", piStop)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiped away: bring ourselves back if we were sharing.
        if (running) {
            Intent restart = new Intent(this, ShareService.class);
            PendingIntent pi = PendingIntent.getService(this, 3, restart,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1500, pi);
        }
        super.onTaskRemoved(rootIntent);
    }

    private void shutdown() {
        running = false;
        if (discovery != null) {
            discovery.stop();
            discovery = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        serverInstance = null;
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
