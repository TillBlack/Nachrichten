package de.nachrichten.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Kontakte und Nachrichten in SQLite. */
final class Db extends SQLiteOpenHelper {

    static final int PENDING = 0, SENT = 1;

    static final class Contact {
        String pub, name;
        long lastRead;
        // für die Liste
        String lastText;
        long lastTs;
        boolean lastOutgoing;
        int unread;
    }

    static final class Msg {
        String id, peer, body, raw;
        boolean outgoing;
        long ts;
        int status;
    }

    Db(Context c) { super(c, "nachrichten.db", null, 1); }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE contacts (pub TEXT PRIMARY KEY, name TEXT NOT NULL, created INTEGER NOT NULL, last_read INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, peer TEXT NOT NULL, outgoing INTEGER NOT NULL, body TEXT NOT NULL, ts INTEGER NOT NULL, status INTEGER NOT NULL, raw TEXT)");
        db.execSQL("CREATE INDEX messages_peer_ts ON messages(peer, ts)");
        db.execSQL("CREATE INDEX messages_status ON messages(status)");
        db.execSQL("CREATE TABLE deleted (id TEXT PRIMARY KEY)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) { }

    // ------------------------------------------------------------------ Kontakte

    synchronized Contact contact(String pub) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT pub, name, last_read FROM contacts WHERE pub = ?", new String[]{pub})) {
            if (!c.moveToFirst()) return null;
            Contact k = new Contact();
            k.pub = c.getString(0); k.name = c.getString(1); k.lastRead = c.getLong(2);
            return k;
        }
    }

    synchronized void addContact(String pub, String name) {
        ContentValues v = new ContentValues();
        v.put("pub", pub);
        v.put("name", name);
        v.put("created", System.currentTimeMillis());
        v.put("last_read", 0);
        getWritableDatabase().insertWithOnConflict("contacts", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    synchronized void rename(String pub, String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        getWritableDatabase().update("contacts", v, "pub = ?", new String[]{pub});
    }

    synchronized void deleteContact(String pub) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("INSERT OR IGNORE INTO deleted (id) SELECT id FROM messages WHERE peer = ?", new String[]{pub});
        db.delete("messages", "peer = ?", new String[]{pub});
        db.delete("contacts", "pub = ?", new String[]{pub});
    }

    synchronized void markRead(String pub) {
        ContentValues v = new ContentValues();
        v.put("last_read", System.currentTimeMillis() + 1000);
        getWritableDatabase().update("contacts", v, "pub = ?", new String[]{pub});
    }

    /** Alle Kontakte mit letzter Nachricht, neueste Unterhaltung zuerst. */
    synchronized List<Contact> contacts() {
        List<Contact> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT pub, name, last_read, created FROM contacts", null)) {
            while (c.moveToNext()) {
                Contact k = new Contact();
                k.pub = c.getString(0); k.name = c.getString(1); k.lastRead = c.getLong(2);
                k.lastTs = c.getLong(3);
                out.add(k);
            }
        }
        for (Contact k : out) {
            try (Cursor c = db.rawQuery("SELECT body, ts, outgoing FROM messages WHERE peer = ? ORDER BY ts DESC LIMIT 1", new String[]{k.pub})) {
                if (c.moveToFirst()) { k.lastText = c.getString(0); k.lastTs = c.getLong(1); k.lastOutgoing = c.getInt(2) == 1; }
            }
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM messages WHERE peer = ? AND outgoing = 0 AND ts > ?", new String[]{k.pub, String.valueOf(k.lastRead)})) {
                if (c.moveToFirst()) k.unread = c.getInt(0);
            }
        }
        out.sort((a, b) -> Long.compare(b.lastTs, a.lastTs));
        return out;
    }

    // ------------------------------------------------------------------ Nachrichten

    synchronized boolean hasMessage(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM messages WHERE id = ? UNION SELECT 1 FROM deleted WHERE id = ?", new String[]{id, id})) {
            return c.moveToFirst();
        }
    }

    /** Gibt true zurück, wenn die Nachricht neu war. */
    synchronized boolean insert(Msg m) {
        ContentValues v = new ContentValues();
        v.put("id", m.id);
        v.put("peer", m.peer);
        v.put("outgoing", m.outgoing ? 1 : 0);
        v.put("body", m.body);
        v.put("ts", m.ts);
        v.put("status", m.status);
        v.put("raw", m.raw);
        return getWritableDatabase().insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    /** Setzt den Status auf gesendet; gibt den Gesprächspartner zurück, wenn sich etwas geändert hat. */
    synchronized String markSent(String id) {
        String peer = null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT peer FROM messages WHERE id = ? AND status = ?", new String[]{id, String.valueOf(PENDING)})) {
            if (c.moveToFirst()) peer = c.getString(0);
        }
        if (peer == null) return null;
        ContentValues v = new ContentValues();
        v.put("status", SENT);
        v.putNull("raw");
        getWritableDatabase().update("messages", v, "id = ?", new String[]{id});
        return peer;
    }

    synchronized List<String> pendingRaw() {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT raw FROM messages WHERE status = ? AND raw IS NOT NULL ORDER BY ts", new String[]{String.valueOf(PENDING)})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    synchronized List<Msg> messages(String peer) {
        List<Msg> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, outgoing, body, ts, status FROM messages WHERE peer = ? ORDER BY ts, id", new String[]{peer})) {
            while (c.moveToNext()) {
                Msg m = new Msg();
                m.id = c.getString(0); m.outgoing = c.getInt(1) == 1; m.body = c.getString(2);
                m.ts = c.getLong(3); m.status = c.getInt(4); m.peer = peer;
                out.add(m);
            }
        }
        return out;
    }

    synchronized void deleteMessage(String id) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("INSERT OR IGNORE INTO deleted (id) VALUES (?)", new String[]{id});
        db.delete("messages", "id = ?", new String[]{id});
    }
}
