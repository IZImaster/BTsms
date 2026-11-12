package com.izimaster.btsms;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements NoteStore.Listener, BleService.StatusListener {

    private static final int[] CARD_COLORS = {
            0xFFFFF8B8, 0xFFE2F6D3, 0xFFD4E4ED, 0xFFF6E2DD, 0xFFE9E3D4, 0xFFEFEFF1, 0xFFFAAFA8, 0xFFB4DDD3
    };

    private NoteStore store;
    private LinearLayout list;
    private TextView statusView;
    private Button startStop;
    private EditText input;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = NoteStore.get(this);
        buildUi();
        if (hasPermissions()) startSharing();
        else requestPermissions(neededPermissions(), 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.addListener(this);
        BleService.setStatusListener(this);
        onStatus(BleService.getStatus());
        render();
    }

    @Override
    protected void onPause() {
        store.removeListener(this);
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
        else onStatus("Bluetooth permission needed – tap Start");
    }

    private void startSharing() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter != null && !adapter.isEnabled()) {
            try {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (Exception ignored) { }
        }
        startForegroundService(new Intent(this, BleService.class));
        startStop.setText("Stop sharing");
    }

    private void stopSharing() {
        stopService(new Intent(this, BleService.class));
        startStop.setText("Start sharing");
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
        startStop = new Button(this);
        startStop.setText(BleService.running ? "Stop sharing" : "Start sharing");
        startStop.setOnClickListener(v -> {
            if (BleService.running) stopSharing();
            else if (hasPermissions()) startSharing();
            else requestPermissions(neededPermissions(), 1);
        });
        top.addView(startStop);
        root.addView(top);

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setPadding(0, dp(4), 0, dp(8));
        root.addView(statusView);

        input = new EditText(this);
        input.setHint("Type or scan text…");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(2);
        input.setMaxLines(6);
        input.setGravity(Gravity.TOP);
        root.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout addRow = new LinearLayout(this);
        addRow.setGravity(Gravity.END);
        Button paste = new Button(this);
        paste.setText("Paste");
        paste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                input.getText().insert(input.getSelectionStart(), t);
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
        root.addView(addRow);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, dp(24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

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

    // ------------------------------------------------------------ listeners

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
        if (startStop != null) startStop.setText(BleService.running ? "Stop sharing" : "Start sharing");
    }
}
