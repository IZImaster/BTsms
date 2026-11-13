package com.izimaster.btsms;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps the notes (newest first) and saves them on the device. */
public final class NoteStore {

    public static final class Note {
        public final String id;
        public String text;
        public long ts;

        Note(String id, String text, long ts) {
            this.id = id;
            this.text = text;
            this.ts = ts;
        }
    }

    public interface Listener {
        void onNotesChanged();
    }

    private static NoteStore instance;

    private final SharedPreferences prefs;
    private final List<Note> notes = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    public static synchronized NoteStore get(Context ctx) {
        if (instance == null) instance = new NoteStore(ctx.getApplicationContext());
        return instance;
    }

    private NoteStore(Context ctx) {
        prefs = Settings.storage(ctx).getSharedPreferences("btsms", Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(prefs.getString("notes", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                notes.add(new Note(o.getString("id"), o.optString("text", ""), o.optLong("ts", 0)));
            }
        } catch (Exception ignored) {
        }
    }

    public void addListener(Listener l) { listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    public synchronized List<Note> list() {
        List<Note> copy = new ArrayList<>();
        for (Note n : notes) copy.add(new Note(n.id, n.text, n.ts));
        return copy;
    }

    public void add(String text) {
        if (text == null || text.trim().isEmpty()) return;
        synchronized (this) {
            notes.add(0, new Note(UUID.randomUUID().toString().substring(0, 8), text, System.currentTimeMillis()));
        }
        changed();
    }

    public void edit(String id, String text) {
        if (text == null || text.trim().isEmpty()) return;
        boolean found = false;
        synchronized (this) {
            for (Note n : notes) {
                if (n.id.equals(id)) {
                    n.text = text;
                    n.ts = System.currentTimeMillis();
                    found = true;
                    break;
                }
            }
        }
        if (found) changed();
    }

    public void delete(String id) {
        boolean removed;
        synchronized (this) {
            removed = notes.removeIf(n -> n.id.equals(id));
        }
        if (removed) changed();
    }

    public synchronized JSONArray toJson() {
        JSONArray arr = new JSONArray();
        try {
            for (Note n : notes) {
                JSONObject o = new JSONObject();
                o.put("id", n.id);
                o.put("text", n.text);
                o.put("ts", n.ts);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        return arr;
    }

    private void changed() {
        prefs.edit().putString("notes", toJson().toString()).apply();
        main.post(() -> {
            for (Listener l : listeners) l.onNotesChanged();
        });
    }
}
