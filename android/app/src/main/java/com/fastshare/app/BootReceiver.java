package com.fastshare.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.content.ContextCompat;

/** Restart sharing after device reboot when the user enabled it. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        boolean restart = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (restart && Prefs.getBoot(context)) {
            Intent s = new Intent(context, ShareService.class);
            ContextCompat.startForegroundService(context, s);
        }
    }
}
