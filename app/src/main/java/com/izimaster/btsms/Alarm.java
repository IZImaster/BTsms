package com.izimaster.btsms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Loud "find my device" alarm: max alarm volume, vibration, flashing torch. */
public final class Alarm {

    public interface Listener { void onAlarmChanged(boolean ringing); }

    public static final String ACTION_STOP = "com.izimaster.btsms.STOP_ALARM";
    private static final String CHANNEL_ID = "btsms_alarm";
    private static final int NOTIFICATION_ID = 2;
    private static final long AUTO_STOP_MS = 120_000;

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static boolean ringing = false;
    private static MediaPlayer player;
    private static ToneGenerator tone;
    private static int savedVolume = -1;
    private static String torchId;
    private static boolean torchOn = false;
    private static Context app;

    public static boolean isRinging() { return ringing; }

    public static void addListener(Listener l) { listeners.add(l); }

    public static void removeListener(Listener l) { listeners.remove(l); }

    public static void start(Context ctx) {
        if (ringing) return;
        app = ctx.getApplicationContext();
        ringing = true;

        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        try {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        } catch (Exception ignored) { savedVolume = -1; }

        if (!playSound()) startTones();
        vibrate();
        startTorch();
        showNotification();
        main.postDelayed(Alarm::stop, AUTO_STOP_MS);
        notifyListeners();
    }

    public static void stop() {
        if (!ringing) return;
        ringing = false;
        main.removeCallbacksAndMessages(null);
        try { if (player != null) { player.stop(); player.release(); } } catch (Exception ignored) { }
        player = null;
        try { if (tone != null) tone.release(); } catch (Exception ignored) { }
        tone = null;
        try {
            Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.cancel();
        } catch (Exception ignored) { }
        setTorch(false);
        if (savedVolume >= 0) {
            try {
                AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0);
            } catch (Exception ignored) { }
        }
        try {
            ((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        } catch (Exception ignored) { }
        notifyListeners();
    }

    private static void notifyListeners() {
        boolean r = ringing;
        main.post(() -> { for (Listener l : listeners) l.onAlarmChanged(r); });
    }

    // ------------------------------------------------------------ sound

    private static boolean playSound() {
        Uri[] candidates = {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        };
        for (Uri uri : candidates) {
            if (uri == null) continue;
            try {
                MediaPlayer mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                mp.setDataSource(app, uri);
                mp.setLooping(true);
                mp.setVolume(1f, 1f);
                mp.prepare();
                mp.start();
                player = mp;
                return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    /** Fallback when the device has no ringtone files: repeating beeps. */
    private static void startTones() {
        try {
            tone = new ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME);
        } catch (Exception e) { return; }
        Runnable beep = new Runnable() {
            @Override
            public void run() {
                if (!ringing || tone == null) return;
                tone.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 900);
                main.postDelayed(this, 1000);
            }
        };
        main.post(beep);
    }

    private static void vibrate() {
        try {
            Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createWaveform(new long[]{0, 800, 400}, 0),
                        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
            }
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ torch

    private static void startTorch() {
        try {
            CameraManager cm = (CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) { torchId = id; break; }
            }
        } catch (Exception ignored) { torchId = null; }
        if (torchId == null) return;
        Runnable blink = new Runnable() {
            @Override
            public void run() {
                if (!ringing) return;
                setTorch(!torchOn);
                main.postDelayed(this, 400);
            }
        };
        main.post(blink);
    }

    private static void setTorch(boolean on) {
        if (torchId == null) return;
        try {
            CameraManager cm = (CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
            cm.setTorchMode(torchId, on);
            torchOn = on;
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ notification with Stop + full screen

    private static void showNotification() {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Find device alarm", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null); // sound is played by MediaPlayer
            nm.createNotificationChannel(ch);

            Intent openIntent = new Intent(app, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent open = PendingIntent.getActivity(app, 10, openIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop = PendingIntent.getService(app, 11,
                    new Intent(app, BleService.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            Notification n = new Notification.Builder(app, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("Found it!")
                    .setContentText("Tap STOP to silence the alarm")
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setOngoing(true)
                    .setContentIntent(open)
                    .setFullScreenIntent(open, true)
                    .addAction(new Notification.Action.Builder(null, "STOP", stop).build())
                    .build();
            nm.notify(NOTIFICATION_ID, n);
        } catch (Exception ignored) { }
    }

    private Alarm() { }
}
