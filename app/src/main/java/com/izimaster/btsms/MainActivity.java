package com.izimaster.btsms;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements NoteStore.Listener, BleService.StatusListener, Alarm.Listener {

    private static final int[] CARD_COLORS = {
            0xFFFFF8B8, 0xFFE2F6D3, 0xFFD4E4ED, 0xFFF6E2DD, 0xFFE9E3D4, 0xFFEFEFF1, 0xFFFAAFA8, 0xFFB4DDD3
    };

    private NoteStore store;
    private LinearLayout list;
    private TextView statusView;
    private TextView signalView;
    private EditText input;
    private Button stopAlarm;
    private View notesArea;
    private TextView lockedNote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = NoteStore.get(this);
        buildUi();
        if (Settings.sharingWanted(this)) {
            if (hasPermissions()) startSharing();
            else requestPermissions(neededPermissions(), 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.addListener(this);
        BleService.setStatusListener(this);
        Alarm.addListener(this);
        onStatus(BleService.getStatus());
        Integer r = BleService.lastRssi;
        onRssi(r == null ? 0 : r);
        onAlarmChanged(Alarm.isRinging());
        // Shown over the lock screen (alarm)? Then only the STOP button, no notes.
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean locked = km != null && km.isKeyguardLocked();
        notesArea.setVisibility(locked ? View.GONE : View.VISIBLE);
        lockedNote.setVisibility(locked ? View.VISIBLE : View.GONE);
        render();
    }

    @Override
    protected void onPause() {
        store.removeListener(this);
        Alarm.removeListener(this);
        BleService.setStatusListener(null);
        super.onPause();
    }

    // ------------------------------------------------------------ permissions & service

    private String[] neededPermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        return p.toArray(new String[0]);
    }

    private boolean hasPermissions() {
        for (String p : neededPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (hasPermissions()) startSharing();
        else onStatus("Bluetooth permission needed – open Settings");
    }

    private void startSharing() {
        Settings.setSharingWanted(this, true);
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter != null && !adapter.isEnabled()) {
            try {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (Exception ignored) { }
        }
        startForegroundService(new Intent(this, BleService.class));
    }

    private void stopSharing() {
        Settings.setSharingWanted(this, false);
        stopService(new Intent(this, BleService.class));
        onStatus("Stopped");
    }

    // ------------------------------------------------------------ UI

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setBackgroundColor(0xFFFAFAFA);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("BTsms");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF202124);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        signalView = new TextView(this);
        signalView.setTextSize(15);
        signalView.setTypeface(Typeface.DEFAULT_BOLD);
        signalView.setPadding(0, 0, dp(8), 0);
        top.addView(signalView);
        Button settings = new Button(this, null, android.R.attr.borderlessButtonStyle);
        settings.setText("⚙ Settings");
        settings.setTextColor(0xFF1A73E8);
        settings.setOnClickListener(v -> openSettings());
        top.addView(settings);
        root.addView(top);

        stopAlarm = new Button(this);
        stopAlarm.setText("STOP ALARM");
        stopAlarm.setTextSize(22);
        stopAlarm.setTextColor(Color.WHITE);
        stopAlarm.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable red = new GradientDrawable();
        red.setColor(0xFFD93025);
        red.setCornerRadius(dp(12));
        stopAlarm.setBackground(red);
        stopAlarm.setVisibility(View.GONE);
        stopAlarm.setOnClickListener(v -> Alarm.stop());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90));
        slp.setMargins(0, dp(8), 0, dp(8));
        root.addView(stopAlarm, slp);

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setPadding(0, dp(4), 0, dp(8));
        root.addView(statusView);

        lockedNote = new TextView(this);
        lockedNote.setText("Unlock the device to see the notes.");
        lockedNote.setTextColor(0xFF80868B);
        lockedNote.setVisibility(View.GONE);
        root.addView(lockedNote);

        LinearLayout notes = new LinearLayout(this);
        notes.setOrientation(LinearLayout.VERTICAL);
        notesArea = notes;

        input = new EditText(this);
        input.setHint("Type or scan text…");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(2);
        input.setMaxLines(6);
        input.setGravity(Gravity.TOP);
        notes.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout addRow = new LinearLayout(this);
        addRow.setGravity(Gravity.END);
        Button paste = new Button(this);
        paste.setText("Paste");
        paste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                input.getText().insert(Math.max(0, input.getSelectionStart()), t);
            }
        });
        Button add = new Button(this);
        add.setText("Add note");
        add.setOnClickListener(v -> {
            String t = input.getText().toString();
            if (t.trim().isEmpty()) return;
            store.add(t);
            input.setText("");
        });
        addRow.addView(paste);
        addRow.addView(add);
        notes.addView(addRow);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, dp(24));
        scroll.addView(list);
        notes.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        root.addView(notes, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void render() {
        list.removeAllViews();
        List<NoteStore.Note> notes = store.list();
        if (notes.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No notes yet.");
            empty.setTextColor(0xFF80868B);
            empty.setPadding(dp(4), dp(16), 0, 0);
            list.addView(empty);
            return;
        }
        for (NoteStore.Note n : notes) list.addView(card(n));
    }

    private View card(NoteStore.Note n) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(8), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD_COLORS[Math.abs(n.id.hashCode()) % CARD_COLORS.length]);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0x22000000);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        card.setLayoutParams(lp);

        TextView text = new TextView(this);
        text.setText(n.text);
        text.setTextSize(16);
        text.setTextColor(0xFF202124);
        text.setTextIsSelectable(true);
        card.addView(text);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView time = new TextView(this);
        time.setText(DateFormat.format("dd MMM HH:mm", n.ts));
        time.setTextSize(12);
        time.setTextColor(0xFF5F6368);
        row.addView(time, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(smallButton("Copy", v -> copy(n.text)));
        row.addView(smallButton("Edit", v -> editDialog(n)));
        row.addView(smallButton("Delete", v -> new AlertDialog.Builder(this)
                .setMessage("Delete this note?")
                .setPositiveButton("Delete", (d, w) -> store.delete(n.id))
                .setNegativeButton("Cancel", null)
                .show()));
        card.addView(row);
        return card;
    }

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setTextColor(0xFF1A73E8);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setOnClickListener(l);
        return b;
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("note", text));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    private void editDialog(NoteStore.Note n) {
        EditText e = new EditText(this);
        e.setText(n.text);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setMinLines(3);
        e.setGravity(Gravity.TOP);
        new AlertDialog.Builder(this)
                .setTitle("Edit note")
                .setView(e)
                .setPositiveButton("Save", (d, w) -> store.edit(n.id, e.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------ settings (protected by the password)

    private void openSettings() {
        if (!Settings.hasPassword(this)) { showSettings(); return; }
        EditText pw = passwordField("Password");
        new AlertDialog.Builder(this)
                .setTitle("Enter password")
                .setView(padded(pw))
                .setPositiveButton("OK", (d, w) -> {
                    if (Settings.checkPassword(this, pw.getText().toString())) showSettings();
                    else Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView shareLabel = label("Bluetooth sharing: " + (BleService.running ? "ON" : "OFF"));
        box.addView(shareLabel);
        Button shareBtn = new Button(this);
        shareBtn.setText(BleService.running ? "Stop sharing" : "Start sharing");
        box.addView(shareBtn);

        CheckBox boot = new CheckBox(this);
        boot.setText("Start automatically when the TC52 turns on");
        boot.setChecked(Settings.startOnBoot(this));
        box.addView(boot);

        box.addView(label("Password (the laptop page needs it to connect, and it protects these settings)"));
        boolean has = Settings.hasPassword(this);
        TextView pwState = label(has ? "A password is set." : "No password set – anyone nearby can connect.");
        pwState.setTextColor(has ? 0xFF188038 : 0xFFC5221F);
        box.addView(pwState);
        EditText pw1 = passwordField(has ? "New password (leave empty to keep)" : "New password");
        EditText pw2 = passwordField("Repeat new password");
        box.addView(pw1);
        box.addView(pw2);
        CheckBox remove = new CheckBox(this);
        remove.setText("Remove password");
        if (has) box.addView(remove);

        ScrollView sv = new ScrollView(this);
        sv.addView(padded(box));

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(sv)
                .setPositiveButton("Save", (d, w) -> {
                    Settings.setStartOnBoot(this, boot.isChecked());
                    String a = pw1.getText().toString(), b = pw2.getText().toString();
                    if (remove.isChecked()) {
                        Settings.setPassword(this, null);
                        Toast.makeText(this, "Password removed", Toast.LENGTH_SHORT).show();
                    } else if (!a.isEmpty()) {
                        if (a.equals(b)) {
                            Settings.setPassword(this, a);
                            Toast.makeText(this, "Password saved", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Passwords don't match – not changed", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .create();
        shareBtn.setOnClickListener(v -> {
            if (BleService.running) stopSharing();
            else if (hasPermissions()) startSharing();
            else requestPermissions(neededPermissions(), 1);
            dlg.dismiss();
        });
        dlg.show();
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(0xFF3C4043);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    private EditText passwordField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setSingleLine(true);
        return e;
    }

    private View padded(View v) {
        LinearLayout f = new LinearLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return f;
    }

    // ------------------------------------------------------------ listeners

    @Override
    public void onAlarmChanged(boolean ringing) {
        if (stopAlarm != null) stopAlarm.setVisibility(ringing ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onNotesChanged() {
        render();
    }

    @Override
    public void onStatus(String status) {
        if (statusView == null) return;
        statusView.setText("● " + status);
        int color;
        if (status.startsWith("Laptop connected")) color = 0xFF188038;
        else if (status.startsWith("Waiting")) color = 0xFF1A73E8;
        else color = 0xFFC5221F;
        statusView.setTextColor(color);
    }

    @Override
    public void onRssi(int rssi) {
        if (signalView == null) return;
        if (rssi == 0) {
            signalView.setText("📶 –");
            signalView.setTextColor(0xFF9AA0A6);
            return;
        }
        String bars = rssi >= -60 ? "▂▄▆█" : rssi >= -70 ? "▂▄▆_" : rssi >= -80 ? "▂▄__" : rssi >= -90 ? "▂___" : "____";
        signalView.setText(bars + " " + rssi + " dBm");
        signalView.setTextColor(rssi >= -70 ? 0xFF188038 : rssi >= -80 ? 0xFFE37400 : 0xFFD93025);
    }
}
