package com.izimaster.btsms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts BTsms in the background when the TC52 turns on (and after an app update). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a);
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(a);
        if (!boot && !updated) return;
        if (boot && !Settings.startOnBoot(context)) return;
        if (!Settings.sharingWanted(context)) return;
        if (BleService.running) return;
        try {
            context.startForegroundService(new Intent(context, BleService.class));
        } catch (Exception ignored) {
        }
    }
}
