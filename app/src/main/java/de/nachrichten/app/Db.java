package de.nachrichten.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Kontakte, Nachrichten, Datei-Stücke und Ausgang in SQLite. */
final class Db extends SQLiteOpenHelper {

    static final int PENDING = 0, SENT = 1, DELIVERED = 2, READ = 3;
    static final int TEXT = 0, IMAGE = 1, FILE = 2;

    static final class Contact {
        String pub, name, avatar, bio, pronouns;
        long lastRead, avatarTs, profileSent;
        int accBehind = -1, accFront = -1, accFrame = -1;
        // für die Liste
        String lastText;
        long lastTs;
        boolean lastOutgoing;
        int lastKind;
        int unread;
    }

    static final class Msg {
        String id, peer, body, raw, replyTo;
        /** Bei Gruppen: öffentlicher Schlüssel des Absenders (sonst null). */
        String sender;
        int deleted;
        boolean outgoing;
        long ts;
        int status, kind;
        // Anhang
        String fileName, fileMime, filePath;
        long fileSize;
        int have, total;
        /** nur empfangene Nachrichten: schon verschickte Quittung (0, 2 zugestellt, 3 gelesen) */
        int receipt;

        boolean complete() { return kind == TEXT || (filePath != null && have >= total); }
    }

    Db(Context c) { super(c, "nachrichten.db", null, 9); }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE contacts (pub TEXT PRIMARY KEY, name TEXT NOT NULL, created INTEGER NOT NULL, last_read INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, peer TEXT NOT NULL, outgoing INTEGER NOT NULL, body TEXT NOT NULL, ts INTEGER NOT NULL, status INTEGER NOT NULL, raw TEXT)");
        db.execSQL("CREATE INDEX messages_peer_ts ON messages(peer, ts)");
        db.execSQL("CREATE INDEX messages_status ON messages(status)");
        db.execSQL("CREATE TABLE deleted (id TEXT PRIMARY KEY)");
        upgradeTo2(db);
        upgradeTo3(db);
        upgradeTo4(db);
        upgradeTo5(db);
        upgradeTo6(db);
        upgradeTo7(db);
        upgradeTo8(db);
        upgradeTo9(db);
    }

    /** Löschzustand pro Nachricht (0 = normal, 1 = gelöscht → Platzhalter). */
    private static void upgradeTo9(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE messages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0");
    }

    /** Anonyme Verbesserungsvorschläge (nur beim Entwickler befüllt). */
    private static void upgradeTo7(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE suggestions (id TEXT PRIMARY KEY, body TEXT NOT NULL, ts INTEGER NOT NULL)");
    }

    /** Gruppen: Absender-Spalte für Nachrichten + Gruppen- und Mitgliedertabellen. */
    private static void upgradeTo8(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE messages ADD COLUMN sender TEXT");
        db.execSQL("CREATE TABLE groups (gid TEXT PRIMARY KEY, name TEXT NOT NULL, gkey TEXT NOT NULL, owner TEXT NOT NULL, created INTEGER NOT NULL, last_read INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE group_members (gid TEXT NOT NULL, pub TEXT NOT NULL, admin INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (gid, pub))");
    }

    private static void upgradeTo6(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE contacts ADD COLUMN accb INTEGER NOT NULL DEFAULT -1");
        db.execSQL("ALTER TABLE contacts ADD COLUMN accf INTEGER NOT NULL DEFAULT -1");
        db.execSQL("ALTER TABLE contacts ADD COLUMN accr INTEGER NOT NULL DEFAULT -1");
    }

    private static void upgradeTo5(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE contacts ADD COLUMN bio TEXT");
        db.execSQL("ALTER TABLE contacts ADD COLUMN pronouns TEXT");
    }

    /** Spalte receipt: welche Quittung ich für eine empfangene Nachricht schon geschickt habe. */
    private static void upgradeTo4(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE messages ADD COLUMN receipt INTEGER NOT NULL DEFAULT 0");
        // für alles, was schon da war, keine Quittungen mehr nachschicken
        db.execSQL("UPDATE messages SET receipt = 3 WHERE outgoing = 0");
    }

    private static void upgradeTo3(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE contacts ADD COLUMN avatar TEXT");
        db.execSQL("ALTER TABLE contacts ADD COLUMN avatar_ts INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE contacts ADD COLUMN profile_sent INTEGER NOT NULL DEFAULT 0");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {
        if (o < 2) upgradeTo2(db);
        if (o < 3) upgradeTo3(db);
        if (o < 4) upgradeTo4(db);
        if (o < 5) upgradeTo5(db);
        if (o < 6) upgradeTo6(db);
        if (o < 7) upgradeTo7(db);
        if (o < 8) upgradeTo8(db);
        if (o < 9) upgradeTo9(db);
    }

    /** Nachricht als gelöscht markieren (zeigt den Platzhalter). */
    synchronized void markDeleted(String id) {
        ContentValues v = new ContentValues();
        v.put("deleted", 1);
        getWritableDatabase().update("messages", v, "id = ?", new String[]{id});
    }

    /** Eine vom Gegenüber gesendete Nachricht als gelöscht markieren (nur eingehende). */
    synchronized void markDeletedByPeer(String id) {
        ContentValues v = new ContentValues();
        v.put("deleted", 1);
        getWritableDatabase().update("messages", v, "id = ? AND outgoing = 0", new String[]{id});
    }

    // ------------------------------------------------------------------ Vorschläge

    static final class Suggestion { String id, body; long ts; }

    /** Speichert einen Vorschlag. Gibt true zurück, wenn er neu war. */
    synchronized boolean addSuggestion(String id, String body, long tsSec) {
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("body", body == null ? "" : body);
        v.put("ts", tsSec * 1000L);
        return getWritableDatabase().insertWithOnConflict("suggestions", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    synchronized int suggestionCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM suggestions", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    synchronized java.util.List<Suggestion> suggestions() {
        java.util.List<Suggestion> out = new java.util.ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, body, ts FROM suggestions ORDER BY ts DESC", null)) {
            while (c.moveToNext()) {
                Suggestion s = new Suggestion();
                s.id = c.getString(0); s.body = c.getString(1); s.ts = c.getLong(2);
                out.add(s);
            }
        }
        return out;
    }

    synchronized void deleteSuggestion(String id) {
        getWritableDatabase().delete("suggestions", "id = ?", new String[]{id});
    }

    // ------------------------------------------------------------------ Gruppen

    static final class Group {
        String gid, name, gkey, owner;
        long created, lastRead, lastTs;
        String lastText, lastSender;
        boolean lastOutgoing;
        int memberCount, unread;
    }

    static final class Member { String pub; boolean admin; }

    synchronized void upsertGroup(String gid, String name, String gkey, String owner, long created) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("name", name); v.put("gkey", gkey); v.put("owner", owner);
        if (db.update("groups", v, "gid = ?", new String[]{gid}) == 0) {
            v.put("gid", gid); v.put("created", created); v.put("last_read", 0);
            db.insertWithOnConflict("groups", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    synchronized void setMembers(String gid, java.util.List<String> members, java.util.Set<String> admins) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("group_members", "gid = ?", new String[]{gid});
        for (String pub : members) {
            ContentValues v = new ContentValues();
            v.put("gid", gid); v.put("pub", pub); v.put("admin", admins.contains(pub) ? 1 : 0);
            db.insertWithOnConflict("group_members", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    synchronized Group group(String gid) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT gid,name,gkey,owner,created,last_read FROM groups WHERE gid = ?", new String[]{gid})) {
            if (!c.moveToFirst()) return null;
            Group g = new Group();
            g.gid = c.getString(0); g.name = c.getString(1); g.gkey = c.getString(2); g.owner = c.getString(3);
            g.created = c.getLong(4); g.lastRead = c.getLong(5);
            return g;
        }
    }

    synchronized java.util.List<Member> members(String gid) {
        java.util.List<Member> out = new java.util.ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT pub, admin FROM group_members WHERE gid = ?", new String[]{gid})) {
            while (c.moveToNext()) { Member m = new Member(); m.pub = c.getString(0); m.admin = c.getInt(1) == 1; out.add(m); }
        }
        return out;
    }

    synchronized java.util.List<String> memberPubs(String gid) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT pub FROM group_members WHERE gid = ?", new String[]{gid})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    synchronized boolean isAdmin(String gid, String pub) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT admin FROM group_members WHERE gid=? AND pub=?", new String[]{gid, pub})) {
            return c.moveToFirst() && c.getInt(0) == 1;
        }
    }

    synchronized boolean isMember(String gid, String pub) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM group_members WHERE gid=? AND pub=?", new String[]{gid, pub})) {
            return c.moveToFirst();
        }
    }

    synchronized java.util.List<Group> groupList() {
        java.util.List<Group> out = new java.util.ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT gid,name,gkey,owner,created,last_read FROM groups", null)) {
            while (c.moveToNext()) {
                Group g = new Group();
                g.gid = c.getString(0); g.name = c.getString(1); g.gkey = c.getString(2); g.owner = c.getString(3);
                g.created = c.getLong(4); g.lastRead = c.getLong(5); g.lastTs = g.created;
                out.add(g);
            }
        }
        for (Group g : out) {
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM group_members WHERE gid=?", new String[]{g.gid})) {
                if (c.moveToFirst()) g.memberCount = c.getInt(0);
            }
            try (Cursor c = db.rawQuery("SELECT body, ts, outgoing, sender FROM messages WHERE peer=? ORDER BY ts DESC LIMIT 1", new String[]{g.gid})) {
                if (c.moveToFirst()) { g.lastText = c.getString(0); g.lastTs = c.getLong(1); g.lastOutgoing = c.getInt(2) == 1; g.lastSender = c.getString(3); }
            }
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM messages WHERE peer=? AND outgoing=0 AND ts>?", new String[]{g.gid, String.valueOf(g.lastRead)})) {
                if (c.moveToFirst()) g.unread = c.getInt(0);
            }
        }
        return out;
    }

    synchronized void markGroupRead(String gid) {
        ContentValues v = new ContentValues();
        v.put("last_read", System.currentTimeMillis() + 1000);
        getWritableDatabase().update("groups", v, "gid = ?", new String[]{gid});
    }

    synchronized void deleteGroup(String gid) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("messages", "peer = ?", new String[]{gid});
        db.delete("group_members", "gid = ?", new String[]{gid});
        db.delete("groups", "gid = ?", new String[]{gid});
    }

    synchronized java.util.List<String> gids() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT gid FROM groups", null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    private static void upgradeTo2(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE messages ADD COLUMN kind INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE messages ADD COLUMN reply_to TEXT");
        db.execSQL("ALTER TABLE messages ADD COLUMN file_name TEXT");
        db.execSQL("ALTER TABLE messages ADD COLUMN file_mime TEXT");
        db.execSQL("ALTER TABLE messages ADD COLUMN file_size INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE messages ADD COLUMN file_path TEXT");
        db.execSQL("ALTER TABLE messages ADD COLUMN have INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE messages ADD COLUMN total INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE TABLE chunks (fid TEXT NOT NULL, idx INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY (fid, idx))");
        db.execSQL("CREATE TABLE outbox (event_id TEXT PRIMARY KEY, msg_id TEXT NOT NULL, raw TEXT NOT NULL, ord INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX outbox_msg ON outbox(msg_id)");
    }

    // ------------------------------------------------------------------ Kontakte

    synchronized Contact contact(String pub) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT pub, name, last_read, avatar, avatar_ts, profile_sent, bio, pronouns, accb, accf, accr FROM contacts WHERE pub = ?", new String[]{pub})) {
            if (!c.moveToFirst()) return null;
            Contact k = new Contact();
            k.pub = c.getString(0); k.name = c.getString(1); k.lastRead = c.getLong(2);
            k.avatar = c.getString(3); k.avatarTs = c.getLong(4); k.profileSent = c.getLong(5);
            k.bio = c.getString(6); k.pronouns = c.getString(7);
            k.accBehind = c.getInt(8); k.accFront = c.getInt(9); k.accFrame = c.getInt(10);
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

    /** Löscht Kontakt und Nachrichten; gibt die Pfade der Anhänge zurück (zum Löschen der Dateien). */
    synchronized List<String> deleteContact(String pub) {
        List<String> files = new ArrayList<>();
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.rawQuery("SELECT file_path FROM messages WHERE peer = ? AND file_path IS NOT NULL", new String[]{pub})) {
            while (c.moveToNext()) files.add(c.getString(0));
        }
        db.execSQL("INSERT OR IGNORE INTO deleted (id) SELECT id FROM messages WHERE peer = ?", new String[]{pub});
        db.execSQL("DELETE FROM chunks WHERE fid IN (SELECT id FROM messages WHERE peer = ?)", new String[]{pub});
        db.execSQL("DELETE FROM outbox WHERE msg_id IN (SELECT id FROM messages WHERE peer = ?)", new String[]{pub});
        db.delete("messages", "peer = ?", new String[]{pub});
        db.delete("contacts", "pub = ?", new String[]{pub});
        return files;
    }

    synchronized void setProfile(String pub, String path, long ts, String bio, String pronouns, int ab, int af, int ar) {
        ContentValues v = new ContentValues();
        v.put("avatar", path);
        v.put("avatar_ts", ts);
        v.put("bio", bio);
        v.put("pronouns", pronouns);
        v.put("accb", ab);
        v.put("accf", af);
        v.put("accr", ar);
        getWritableDatabase().update("contacts", v, "pub = ?", new String[]{pub});
    }

    synchronized void setProfileSent(String pub, long ts) {
        ContentValues v = new ContentValues();
        v.put("profile_sent", ts);
        getWritableDatabase().update("contacts", v, "pub = ?", new String[]{pub});
    }

    /** Zahl der von mir gesendeten Nachrichten (für Accessoire-Freischaltung). */
    synchronized int sentCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM messages WHERE outgoing = 1", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    synchronized List<String> contactKeys() {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT pub FROM contacts", null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
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
        try (Cursor c = db.rawQuery("SELECT pub, name, last_read, created, avatar, accb, accf, accr FROM contacts", null)) {
            while (c.moveToNext()) {
                Contact k = new Contact();
                k.pub = c.getString(0); k.name = c.getString(1); k.lastRead = c.getLong(2);
                k.lastTs = c.getLong(3); k.avatar = c.getString(4);
                k.accBehind = c.getInt(5); k.accFront = c.getInt(6); k.accFrame = c.getInt(7);
                out.add(k);
            }
        }
        for (Contact k : out) {
            try (Cursor c = db.rawQuery("SELECT body, ts, outgoing, kind, file_name FROM messages WHERE peer = ? ORDER BY ts DESC LIMIT 1", new String[]{k.pub})) {
                if (c.moveToFirst()) {
                    k.lastKind = c.getInt(3);
                    k.lastText = k.lastKind == TEXT ? c.getString(0) : (c.isNull(4) ? "" : c.getString(4));
                    k.lastTs = c.getLong(1);
                    k.lastOutgoing = c.getInt(2) == 1;
                }
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

    synchronized boolean isDeleted(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM deleted WHERE id = ?", new String[]{id})) {
            return c.moveToFirst();
        }
    }

    /** Gibt true zurück, wenn die Nachricht neu war. */
    synchronized boolean insert(Msg m) {
        ContentValues v = new ContentValues();
        v.put("id", m.id);
        v.put("peer", m.peer);
        v.put("outgoing", m.outgoing ? 1 : 0);
        v.put("body", m.body == null ? "" : m.body);
        v.put("ts", m.ts);
        v.put("status", m.status);
        v.put("raw", m.raw);
        v.put("kind", m.kind);
        v.put("reply_to", m.replyTo);
        v.put("file_name", m.fileName);
        v.put("file_mime", m.fileMime);
        v.put("file_size", m.fileSize);
        v.put("file_path", m.filePath);
        v.put("have", m.have);
        v.put("total", m.total);
        v.put("receipt", m.receipt);
        v.put("sender", m.sender);
        v.put("deleted", m.deleted);
        return getWritableDatabase().insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    private static final String COLS = "id, peer, outgoing, body, ts, status, kind, reply_to, file_name, file_mime, file_size, file_path, have, total, sender, deleted";

    private static Msg read(Cursor c) {
        Msg m = new Msg();
        m.id = c.getString(0); m.peer = c.getString(1); m.outgoing = c.getInt(2) == 1; m.body = c.getString(3);
        m.ts = c.getLong(4); m.status = c.getInt(5); m.kind = c.getInt(6); m.replyTo = c.getString(7);
        m.fileName = c.getString(8); m.fileMime = c.getString(9); m.fileSize = c.getLong(10); m.filePath = c.getString(11);
        m.have = c.getInt(12); m.total = c.getInt(13); m.sender = c.getString(14); m.deleted = c.getInt(15);
        return m;
    }

    synchronized Msg message(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM messages WHERE id = ?", new String[]{id})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    synchronized List<Msg> messages(String peer) {
        List<Msg> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM messages WHERE peer = ? ORDER BY ts, id", new String[]{peer})) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }

    /** Löscht eine Nachricht; gibt den Pfad eines Anhangs zurück (oder null). */
    synchronized String deleteMessage(String id) {
        Msg m = message(id);
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("INSERT OR IGNORE INTO deleted (id) VALUES (?)", new String[]{id});
        db.delete("chunks", "fid = ?", new String[]{id});
        db.delete("outbox", "msg_id = ?", new String[]{id});
        db.delete("messages", "id = ?", new String[]{id});
        return m != null ? m.filePath : null;
    }

    // ------------------------------------------------------------------ Senden / Bestätigung

    /** Ein Relay hat ein Ereignis angenommen. Gibt den Gesprächspartner zurück, wenn sich etwas geändert hat. */
    synchronized String markSent(String eventId) {
        SQLiteDatabase db = getWritableDatabase();
        // Textnachricht?
        String peer = null;
        try (Cursor c = db.rawQuery("SELECT peer FROM messages WHERE id = ? AND status = ?", new String[]{eventId, String.valueOf(PENDING)})) {
            if (c.moveToFirst()) peer = c.getString(0);
        }
        if (peer != null) {
            ContentValues v = new ContentValues();
            v.put("status", SENT);
            v.putNull("raw");
            db.update("messages", v, "id = ?", new String[]{eventId});
            return peer;
        }
        // Stück eines Anhangs?
        String msgId = null;
        try (Cursor c = db.rawQuery("SELECT msg_id FROM outbox WHERE event_id = ?", new String[]{eventId})) {
            if (c.moveToFirst()) msgId = c.getString(0);
        }
        if (msgId == null) return null;
        db.delete("outbox", "event_id = ?", new String[]{eventId});
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM outbox WHERE msg_id = ?", new String[]{msgId})) {
            if (c.moveToFirst() && c.getInt(0) > 0) return null;
        }
        try (Cursor c = db.rawQuery("SELECT peer FROM messages WHERE id = ?", new String[]{msgId})) {
            if (c.moveToFirst()) peer = c.getString(0);
        }
        ContentValues v = new ContentValues();
        v.put("status", SENT);
        db.update("messages", v, "id = ?", new String[]{msgId});
        return peer;
    }

    /** Alles, was noch an Relays geschickt werden muss, in Reihenfolge. */
    synchronized List<String> pendingRaw() {
        List<String> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT raw FROM messages WHERE status = ? AND raw IS NOT NULL ORDER BY ts", new String[]{String.valueOf(PENDING)})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        try (Cursor c = db.rawQuery("SELECT raw FROM outbox ORDER BY msg_id, ord", null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    synchronized void addOutbox(String eventId, String msgId, String raw, int ord) {
        ContentValues v = new ContentValues();
        v.put("event_id", eventId);
        v.put("msg_id", msgId);
        v.put("raw", raw);
        v.put("ord", ord);
        getWritableDatabase().insertWithOnConflict("outbox", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    // ------------------------------------------------------------------ Quittungen

    /** Status meiner gesendeten Nachrichten erhöhen (zugestellt / gelesen). */
    synchronized boolean applyReceipt(String peer, List<String> ids, int status) {
        SQLiteDatabase db = getWritableDatabase();
        int changed = 0;
        for (String id : ids) {
            ContentValues v = new ContentValues();
            v.put("status", status);
            changed += db.update("messages", v, "id = ? AND peer = ? AND outgoing = 1 AND status < ?",
                    new String[]{id, peer, String.valueOf(status)});
        }
        return changed > 0;
    }

    /** Empfangene, vollständige Nachrichten, für die noch keine Quittung dieser Stufe verschickt wurde. */
    synchronized List<String> needReceipt(String peer, int level) {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id FROM messages WHERE peer = ? AND outgoing = 0 AND receipt < ? AND (kind = 0 OR file_path IS NOT NULL) ORDER BY ts LIMIT 200",
                new String[]{peer, String.valueOf(level)})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    synchronized void markReceipt(List<String> ids, int level) {
        SQLiteDatabase db = getWritableDatabase();
        for (String id : ids) {
            ContentValues v = new ContentValues();
            v.put("receipt", level);
            db.update("messages", v, "id = ? AND receipt < ?", new String[]{id, String.valueOf(level)});
        }
    }

    // ------------------------------------------------------------------ Empfang von Anhängen

    /** Speichert ein Stück. Gibt die Zahl der vorhandenen Stücke zurück. */
    synchronized int addChunk(String fid, int idx, byte[] data) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("fid", fid);
        v.put("idx", idx);
        v.put("data", data);
        db.insertWithOnConflict("chunks", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        int have = 0;
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM chunks WHERE fid = ?", new String[]{fid})) {
            if (c.moveToFirst()) have = c.getInt(0);
        }
        db.execSQL("UPDATE messages SET have = ? WHERE id = ?", new Object[]{have, fid});
        return have;
    }

    synchronized byte[][] chunks(String fid, int total) {
        byte[][] out = new byte[total][];
        try (Cursor c = getReadableDatabase().rawQuery("SELECT idx, data FROM chunks WHERE fid = ?", new String[]{fid})) {
            while (c.moveToNext()) {
                int i = c.getInt(0);
                if (i >= 0 && i < total) out[i] = c.getBlob(1);
            }
        }
        return out;
    }

    /** Fügt Angaben zum Anhang hinzu (aus Stück 0), wenn die Nachricht schon als Platzhalter existiert. */
    synchronized void setFileMeta(String fid, int kind, String name, String mime, long size, int total, String replyTo, String sha) {
        ContentValues v = new ContentValues();
        v.put("body", sha); // bei Anhängen steht hier die Prüfsumme
        v.put("kind", kind);
        v.put("file_name", name);
        v.put("file_mime", mime);
        v.put("file_size", size);
        v.put("total", total);
        if (replyTo != null) v.put("reply_to", replyTo);
        getWritableDatabase().update("messages", v, "id = ?", new String[]{fid});
    }

    synchronized void fileDone(String fid, String path) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("file_path", path);
        db.update("messages", v, "id = ?", new String[]{fid});
        db.delete("chunks", "fid = ?", new String[]{fid});
    }
}
