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
        String pub, name, avatar, bio, pronouns, tags;
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

    Db(Context c) { super(c, "nachrichten.db", null, 15); }

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
        upgradeTo10(db);
        upgradeTo11(db);
        upgradeTo12(db);
        upgradeTo13(db);
        upgradeTo14(db);
        upgradeTo15(db);
    }

    /** Moderation von Profilbild (noav) und Sichtbarkeit in der Nutzerliste (hide). */
    private static void upgradeTo15(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE mods ADD COLUMN noav_until INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE mods ADD COLUMN noav_ts INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE mods ADD COLUMN hide_until INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE mods ADD COLUMN hide_ts INTEGER NOT NULL DEFAULT 0");
    }

    /** Posteingang für Moderation: Einsprüche (type=1) und Meldungen (type=2). */
    private static void upgradeTo14(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE modbox (id TEXT PRIMARY KEY, type INTEGER NOT NULL, frompub TEXT, "
                + "target TEXT, body TEXT NOT NULL, ts INTEGER NOT NULL, replied INTEGER NOT NULL DEFAULT 0, reply TEXT)");
    }

    /** Selbst gewählter Name eines Kontakts (pname) und Pronomen-Überschreibung durch den Admin. */
    private static void upgradeTo13(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE contacts ADD COLUMN pname TEXT");
        db.execSQL("ALTER TABLE mods ADD COLUMN opron TEXT");
        db.execSQL("ALTER TABLE mods ADD COLUMN opron_until INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE mods ADD COLUMN opron_ts INTEGER NOT NULL DEFAULT 0");
    }

    /** Moderation: Bann, Namens-/Bio-Überschreibung, Verwarnung je Nutzer (mit Zeitstempel-Schutz pro Feld). */
    private static void upgradeTo12(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE mods (pub TEXT PRIMARY KEY, "
                + "ban_until INTEGER NOT NULL DEFAULT 0, ban_ts INTEGER NOT NULL DEFAULT 0, "
                + "oname TEXT, oname_until INTEGER NOT NULL DEFAULT 0, oname_ts INTEGER NOT NULL DEFAULT 0, "
                + "obio TEXT, obio_until INTEGER NOT NULL DEFAULT 0, obio_ts INTEGER NOT NULL DEFAULT 0, "
                + "warn TEXT, warn_ts INTEGER NOT NULL DEFAULT 0)");
    }

    /** Umfragen und Stimmen. */
    private static void upgradeTo11(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE polls (id TEXT PRIMARY KEY, title TEXT, opts TEXT, ts INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE votes (poll TEXT NOT NULL, voter TEXT NOT NULL, opt INTEGER NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY (poll, voter))");
    }

    /** Löschzustand pro Nachricht (0 = normal, 1 = gelöscht → Platzhalter). */
    private static void upgradeTo9(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE messages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0");
    }

    /** Hashtags im Profil eines Kontakts. */
    private static void upgradeTo10(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE contacts ADD COLUMN tags TEXT");
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
        if (o < 10) upgradeTo10(db);
        if (o < 11) upgradeTo11(db);
        if (o < 12) upgradeTo12(db);
        if (o < 13) upgradeTo13(db);
        if (o < 14) upgradeTo14(db);
        if (o < 15) upgradeTo15(db);
    }

    // ------------------------------------------------------------------ Moderation

    private void ensureMod(String pub) {
        getWritableDatabase().execSQL("INSERT OR IGNORE INTO mods (pub) VALUES (?)", new Object[]{pub});
    }

    long modTs(String pub, String col) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + col + " FROM mods WHERE pub = ?", new String[]{pub})) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    /** Bann setzen: until = -1 dauerhaft, 0 aufheben, sonst Zeitstempel (ms). evTs = Zeit des Admin-Befehls. */
    synchronized void setBan(String pub, long until, long evTs) {
        ensureMod(pub);
        if (evTs < modTs(pub, "ban_ts")) return;
        ContentValues v = new ContentValues();
        v.put("ban_until", until);
        v.put("ban_ts", evTs);
        getWritableDatabase().update("mods", v, "pub = ?", new String[]{pub});
    }

    synchronized void setNameOverride(String pub, String name, long until, long evTs) {
        ensureMod(pub);
        if (evTs < modTs(pub, "oname_ts")) return;
        ContentValues v = new ContentValues();
        v.put("oname", name);
        v.put("oname_until", until);
        v.put("oname_ts", evTs);
        getWritableDatabase().update("mods", v, "pub = ?", new String[]{pub});
    }

    synchronized void setBioOverride(String pub, String bio, long until, long evTs) {
        ensureMod(pub);
        if (evTs < modTs(pub, "obio_ts")) return;
        ContentValues v = new ContentValues();
        v.put("obio", bio);
        v.put("obio_until", until);
        v.put("obio_ts", evTs);
        getWritableDatabase().update("mods", v, "pub = ?", new String[]{pub});
    }

    /** Generische Moderations-Flagge mit Zeitstempel-Schutz (Spalten *_until / *_ts). */
    private synchronized void setFlag(String pub, String uCol, String tsCol, long until, long evTs) {
        ensureMod(pub);
        if (evTs < modTs(pub, tsCol)) return;
        ContentValues v = new ContentValues();
        v.put(uCol, until);
        v.put(tsCol, evTs);
        getWritableDatabase().update("mods", v, "pub = ?", new String[]{pub});
    }
    private synchronized boolean flagActive(String pub, String uCol) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + uCol + " FROM mods WHERE pub = ?", new String[]{pub})) {
            if (c.moveToFirst()) return active(c.getLong(0));
        }
        return false;
    }
    synchronized void setNoAvatar(String pub, long until, long evTs) { setFlag(pub, "noav_until", "noav_ts", until, evTs); }
    synchronized boolean noAvatar(String pub) { return flagActive(pub, "noav_until"); }
    synchronized void setHidden(String pub, long until, long evTs) { setFlag(pub, "hide_until", "hide_ts", until, evTs); }
    synchronized boolean hidden(String pub) { return flagActive(pub, "hide_until"); }

    synchronized void setPronOverride(String pub, String pron, long until, long evTs) {
        ensureMod(pub);
        if (evTs < modTs(pub, "opron_ts")) return;
        ContentValues v = new ContentValues();
        v.put("opron", pron);
        v.put("opron_until", until);
        v.put("opron_ts", evTs);
        getWritableDatabase().update("mods", v, "pub = ?", new String[]{pub});
    }

    synchronized void setWarn(String pub, String text, long ts) {
        ensureMod(pub);
        if (ts < modTs(pub, "warn_ts")) return;
        ContentValues v = new ContentValues();
        v.put("warn", text);
        v.put("warn_ts", ts);
        getWritableDatabase().update("mods", v, "pub = ?", new String[]{pub});
    }

    private static boolean active(long until) { return until == -1 || until > System.currentTimeMillis(); }

    synchronized boolean isBanned(String pub) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT ban_until FROM mods WHERE pub = ?", new String[]{pub})) {
            return c.moveToFirst() && active(c.getLong(0));
        }
    }

    /** Bann-Ablauf in ms (-1 dauerhaft, 0 = nicht gebannt). */
    synchronized long banUntil(String pub) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT ban_until FROM mods WHERE pub = ?", new String[]{pub})) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    /** Vom Admin gesetzter Name (oder null, wenn keiner aktiv). */
    synchronized String nameOverride(String pub) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT oname, oname_until FROM mods WHERE pub = ?", new String[]{pub})) {
            if (c.moveToFirst() && c.getString(0) != null && active(c.getLong(1))) return c.getString(0);
        }
        return null;
    }

    synchronized String bioOverride(String pub) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT obio, obio_until FROM mods WHERE pub = ?", new String[]{pub})) {
            if (c.moveToFirst() && c.getString(0) != null && active(c.getLong(1))) return c.getString(0);
        }
        return null;
    }

    synchronized String pronOverride(String pub) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT opron, opron_until FROM mods WHERE pub = ?", new String[]{pub})) {
            if (c.moveToFirst() && c.getString(0) != null && active(c.getLong(1))) return c.getString(0);
        }
        return null;
    }

    /** Vom Kontakt selbst gewählter Name (pname), oder null. */
    synchronized String profileName(String pub) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT pname FROM contacts WHERE pub = ?", new String[]{pub})) {
            if (c.moveToFirst()) { String s = c.getString(0); return s == null || s.trim().isEmpty() ? null : s; }
        }
        return null;
    }

    /** Selbst gewählten Namen eines Kontakts speichern. */
    synchronized void setProfileName(String pub, String name) {
        if (name == null) return;
        ContentValues v = new ContentValues();
        v.put("pname", name);
        getWritableDatabase().update("contacts", v, "pub = ?", new String[]{pub});
    }

    /** Aktuelle Verwarnung {text, ts} oder null. */
    synchronized String[] warnFor(String pub) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT warn, warn_ts FROM mods WHERE pub = ?", new String[]{pub})) {
            if (c.moveToFirst() && c.getString(0) != null) return new String[]{c.getString(0), String.valueOf(c.getLong(1))};
        }
        return null;
    }

    synchronized void clearWarn(String pub) {
        getWritableDatabase().execSQL("UPDATE mods SET warn = NULL WHERE pub = ?", new Object[]{pub});
    }

    // ------------------------------------------------------------------ Umfragen

    static final class Poll {
        String id, title;
        java.util.List<String> options = new java.util.ArrayList<>();
        long ts;
        boolean deleted;
    }

    /** Umfrage einfügen/aktualisieren (nur wenn neuer als die vorhandene). */
    synchronized void upsertPoll(String id, String title, String optsJson, long ts, boolean deleted) {
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.rawQuery("SELECT ts FROM polls WHERE id = ?", new String[]{id})) {
            if (c.moveToFirst() && c.getLong(0) >= ts) return;
        }
        ContentValues v = new ContentValues();
        v.put("id", id); v.put("title", title); v.put("opts", optsJson);
        v.put("ts", ts); v.put("deleted", deleted ? 1 : 0);
        db.insertWithOnConflict("polls", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static Poll readPoll(Cursor c) {
        Poll p = new Poll();
        p.id = c.getString(0); p.title = c.getString(1); p.ts = c.getLong(3); p.deleted = c.getInt(4) == 1;
        try {
            org.json.JSONArray a = new org.json.JSONArray(c.getString(2) == null ? "[]" : c.getString(2));
            for (int i = 0; i < a.length(); i++) p.options.add(a.optString(i));
        } catch (Exception ignored) { }
        return p;
    }

    synchronized java.util.List<Poll> polls() {
        java.util.List<Poll> out = new java.util.ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,title,opts,ts,deleted FROM polls WHERE deleted = 0 ORDER BY ts DESC", null)) {
            while (c.moveToNext()) out.add(readPoll(c));
        }
        return out;
    }

    synchronized Poll poll(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,title,opts,ts,deleted FROM polls WHERE id = ?", new String[]{id})) {
            return c.moveToFirst() ? readPoll(c) : null;
        }
    }

    synchronized int pollCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM polls WHERE deleted = 0", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    synchronized java.util.List<String> pollIds() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM polls", null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    /** Stimme einfügen/aktualisieren (eine pro Wähler und Umfrage, neueste gilt). */
    synchronized void upsertVote(String poll, String voter, int opt, long ts) {
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.rawQuery("SELECT ts FROM votes WHERE poll = ? AND voter = ?", new String[]{poll, voter})) {
            if (c.moveToFirst() && c.getLong(0) >= ts) return;
        }
        ContentValues v = new ContentValues();
        v.put("poll", poll); v.put("voter", voter); v.put("opt", opt); v.put("ts", ts);
        db.insertWithOnConflict("votes", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Stimmenzahl je Option. */
    synchronized int[] voteCounts(String poll, int optionCount) {
        int[] counts = new int[optionCount];
        try (Cursor c = getReadableDatabase().rawQuery("SELECT opt, COUNT(*) FROM votes WHERE poll = ? GROUP BY opt", new String[]{poll})) {
            while (c.moveToNext()) {
                int o = c.getInt(0);
                if (o >= 0 && o < optionCount) counts[o] = c.getInt(1);
            }
        }
        return counts;
    }

    /** Welche Option ich gewählt habe (-1 = keine). */
    synchronized int myVote(String poll, String me) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT opt FROM votes WHERE poll = ? AND voter = ?", new String[]{poll, me})) {
            return c.moveToFirst() ? c.getInt(0) : -1;
        }
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

    // ------------------------------------------------------------------ Moderations-Posteingang

    static final int MOD_APPEAL = 1, MOD_REPORT = 2;

    static final class ModItem {
        String id, frompub, target, body, reply;
        int type;
        long ts;
        boolean replied;
    }

    synchronized boolean addModItem(String id, int type, String frompub, String target, String body, long tsSec) {
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("type", type);
        v.put("frompub", frompub);
        v.put("target", target);
        v.put("body", body == null ? "" : body);
        v.put("ts", tsSec * 1000L);
        return getWritableDatabase().insertWithOnConflict("modbox", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    synchronized int modCount(int type) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM modbox WHERE type = ?", new String[]{String.valueOf(type)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    synchronized java.util.List<ModItem> modItems(int type) {
        java.util.List<ModItem> out = new java.util.ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, type, frompub, target, body, ts, replied, reply FROM modbox WHERE type = ? ORDER BY ts DESC",
                new String[]{String.valueOf(type)})) {
            while (c.moveToNext()) {
                ModItem m = new ModItem();
                m.id = c.getString(0); m.type = c.getInt(1); m.frompub = c.getString(2); m.target = c.getString(3);
                m.body = c.getString(4); m.ts = c.getLong(5); m.replied = c.getInt(6) != 0; m.reply = c.getString(7);
                out.add(m);
            }
        }
        return out;
    }

    synchronized void setModReply(String id, String reply) {
        ContentValues v = new ContentValues();
        v.put("replied", 1);
        v.put("reply", reply);
        getWritableDatabase().update("modbox", v, "id = ?", new String[]{id});
    }

    synchronized void deleteModItem(String id) {
        getWritableDatabase().delete("modbox", "id = ?", new String[]{id});
    }

    /** Neueste eingegangene Nachricht von einem bestimmten Absender (z. B. Admin-Antwort), oder null. */
    synchronized String lastIncomingFrom(String peer) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT body FROM messages WHERE peer = ? AND outgoing = 0 ORDER BY ts DESC LIMIT 1", new String[]{peer})) {
            if (c.moveToFirst()) return c.getString(0);
        }
        return null;
    }

    /** Alle aktuell gesperrten öffentlichen Schlüssel (für die Übersicht). */
    synchronized java.util.List<String> bannedList() {
        java.util.List<String> out = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT pub, ban_until FROM mods", null)) {
            while (c.moveToNext()) {
                long until = c.getLong(1);
                if (until == -1 || until > now) out.add(c.getString(0));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Gruppen

    static final class Group {
        String gid, name, gkey, owner;
        long created, lastRead, lastTs;
        String lastText, lastSender, lastFileName;
        boolean lastOutgoing;
        int memberCount, unread, lastKind;
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
            try (Cursor c = db.rawQuery("SELECT body, ts, outgoing, sender, kind, file_name FROM messages WHERE peer=? ORDER BY ts DESC LIMIT 1", new String[]{g.gid})) {
                if (c.moveToFirst()) {
                    g.lastText = c.getString(0); g.lastTs = c.getLong(1); g.lastOutgoing = c.getInt(2) == 1;
                    g.lastSender = c.getString(3); g.lastKind = c.getInt(4); g.lastFileName = c.getString(5);
                }
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
        try (Cursor c = getReadableDatabase().rawQuery("SELECT pub, name, last_read, avatar, avatar_ts, profile_sent, bio, pronouns, accb, accf, accr, tags FROM contacts WHERE pub = ?", new String[]{pub})) {
            if (!c.moveToFirst()) return null;
            Contact k = new Contact();
            k.pub = c.getString(0); k.name = c.getString(1); k.lastRead = c.getLong(2);
            k.avatar = c.getString(3); k.avatarTs = c.getLong(4); k.profileSent = c.getLong(5);
            k.bio = c.getString(6); k.pronouns = c.getString(7);
            k.accBehind = c.getInt(8); k.accFront = c.getInt(9); k.accFrame = c.getInt(10);
            k.tags = c.getString(11);
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

    synchronized void setProfile(String pub, String path, long ts, String bio, String pronouns, int ab, int af, int ar, String tags) {
        ContentValues v = new ContentValues();
        v.put("avatar", path);
        v.put("avatar_ts", ts);
        v.put("bio", bio);
        v.put("pronouns", pronouns);
        v.put("accb", ab);
        v.put("accf", af);
        v.put("accr", ar);
        v.put("tags", tags);
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
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM messages WHERE peer = ? ORDER BY ts, rowid", new String[]{peer})) {
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

    /** Löscht nur eine eingehende Nachricht (vom Gegenüber „für alle" gelöscht). Gibt Anhangpfad zurück. */
    synchronized String deleteIncoming(String id) {
        Msg m = message(id);
        if (m == null || m.outgoing) return null;
        return deleteMessage(id);
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
