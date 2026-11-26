package de.nachrichten.desktop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Datenablage der Windows-Version (Gegenstück zur Android-Datenbank).
 * Alles liegt als JSON im Benutzerordner (%APPDATA%\Nachrichten); andere Windows-Benutzer kommen nicht heran.
 * Alle Methoden sind synchronisiert; gespeichert wird gebündelt im Hintergrund.
 */
final class Store {

    static final int PENDING = 0, SENT = 1, DELIVERED = 2, READ = 3;
    static final int TEXT = 0, IMAGE = 1, FILE = 2;

    static final class Contact {
        String pub, name = "", profileName, avatar;
        long avatarTs, profileSent, lastTs;
        int unread;
    }

    static final class Group {
        String gid, name = "", gkey, owner, avatar;
        long created, lastTs;
        int unread;
        List<String> members = new ArrayList<>();
        Set<String> admins = new HashSet<>();
    }

    static final class Msg {
        String id, peer, sender, body = "", replyTo, fileName, fileMime, filePath, raw;
        boolean outgoing;
        long ts, fileSize;
        int status, kind, total, receipt;

        boolean complete() { return kind == TEXT || filePath != null; }
    }

    final File dir, mediaDir, msgDir;
    JSONObject prefs = new JSONObject();
    private final Map<String, Contact> contacts = new LinkedHashMap<>();
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Map<String, List<Msg>> convs = new HashMap<>();
    private final Map<String, Msg> byId = new HashMap<>();
    /** Reaktionen: Nachrichten-ID → (Schlüssel → Emoji). */
    private final Map<String, LinkedHashMap<String, String>> reactions = new HashMap<>();
    private final Map<String, Long> reactTs = new HashMap<>();
    /** Sperren: Schlüssel oder "d:"+Geräte-Hash → {bis, Zeitstempel}. */
    private final Map<String, long[]> bans = new HashMap<>();
    /** Vom Admin überschriebene Namen: Schlüssel → {Name, bis, Zeitstempel}. */
    private final Map<String, String[]> nameOv = new HashMap<>();
    private final Set<String> deleted = new LinkedHashSet<>();
    /** Noch nicht bestätigte Ereignisse: ID → {raw, zugehörige Nachricht oder ""}. */
    private final Map<String, String[]> outbox = new LinkedHashMap<>();
    /** Eingehende Anhang-Stücke (nur im Speicher). */
    private final Map<String, byte[][]> parts = new HashMap<>();

    private final Set<String> dirtyConvs = new HashSet<>();
    private boolean dirtyState;
    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "speichern");
        t.setDaemon(true);
        return t;
    });

    Store(File dir) {
        this.dir = dir;
        this.mediaDir = new File(dir, "media");
        this.msgDir = new File(dir, "chats");
        mediaDir.mkdirs();
        msgDir.mkdirs();
        load();
    }

    static File defaultDir() {
        String appdata = System.getenv("APPDATA");
        if (appdata != null && !appdata.isEmpty()) return new File(appdata, "Nachrichten");
        return new File(System.getProperty("user.home"), ".nachrichten-desktop");
    }

    // ================================================================== Laden / Speichern

    private void load() {
        File st = new File(dir, "state.json");
        if (st.exists()) {
            try {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(st.toPath()), StandardCharsets.UTF_8));
                JSONObject p = o.optJSONObject("prefs");
                if (p != null) prefs = p;
                JSONArray ca = o.optJSONArray("contacts");
                if (ca != null) for (int i = 0; i < ca.length(); i++) {
                    JSONObject c = ca.optJSONObject(i);
                    if (c == null) continue;
                    Contact x = new Contact();
                    x.pub = c.optString("pub");
                    x.name = c.optString("name");
                    x.profileName = c.optString("pname", null);
                    x.avatar = c.optString("avatar", null);
                    x.avatarTs = c.optLong("avatarTs");
                    x.profileSent = c.optLong("profileSent");
                    x.lastTs = c.optLong("lastTs");
                    x.unread = c.optInt("unread");
                    if (x.pub.matches("[0-9a-f]{64}")) contacts.put(x.pub, x);
                }
                JSONArray ga = o.optJSONArray("groups");
                if (ga != null) for (int i = 0; i < ga.length(); i++) {
                    JSONObject g = ga.optJSONObject(i);
                    if (g == null) continue;
                    Group x = new Group();
                    x.gid = g.optString("gid");
                    x.name = g.optString("name");
                    x.gkey = g.optString("gkey");
                    x.owner = g.optString("owner");
                    x.avatar = g.optString("avatar", null);
                    x.created = g.optLong("created");
                    x.lastTs = g.optLong("lastTs");
                    x.unread = g.optInt("unread");
                    JSONArray m = g.optJSONArray("members");
                    if (m != null) for (int j = 0; j < m.length(); j++) x.members.add(m.optString(j));
                    JSONArray a = g.optJSONArray("admins");
                    if (a != null) for (int j = 0; j < a.length(); j++) x.admins.add(a.optString(j));
                    if (x.gid.matches("[0-9a-f]{64}")) groups.put(x.gid, x);
                }
                JSONObject r = o.optJSONObject("reactions");
                if (r != null) for (java.util.Iterator<String> it = r.keys(); it.hasNext(); ) {
                    String mid = it.next();
                    JSONObject m = r.optJSONObject(mid);
                    if (m == null) continue;
                    LinkedHashMap<String, String> map = new LinkedHashMap<>();
                    for (java.util.Iterator<String> it2 = m.keys(); it2.hasNext(); ) { String k = it2.next(); map.put(k, m.optString(k)); }
                    reactions.put(mid, map);
                }
                JSONObject b = o.optJSONObject("bans");
                if (b != null) for (java.util.Iterator<String> it = b.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONArray v = b.optJSONArray(k);
                    if (v != null) bans.put(k, new long[]{v.optLong(0), v.optLong(1)});
                }
                JSONObject n = o.optJSONObject("names");
                if (n != null) for (java.util.Iterator<String> it = n.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONArray v = n.optJSONArray(k);
                    if (v != null) nameOv.put(k, new String[]{v.optString(0), v.optString(1), v.optString(2)});
                }
                JSONArray d = o.optJSONArray("deleted");
                if (d != null) for (int i = 0; i < d.length(); i++) deleted.add(d.optString(i));
                JSONObject ob = o.optJSONObject("outbox");
                if (ob != null) for (java.util.Iterator<String> it = ob.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONArray v = ob.optJSONArray(k);
                    if (v != null) outbox.put(k, new String[]{v.optString(0), v.optString(1)});
                }
            } catch (Exception e) {
                // beschädigte Datei sichern statt überschreiben
                try { Files.copy(st.toPath(), new File(dir, "state.defekt.json").toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) { }
            }
        }
        File[] files = msgDir.listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) for (File f : files) {
            String conv = f.getName().substring(0, f.getName().length() - 5);
            try {
                JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                List<Msg> list = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject m = a.optJSONObject(i);
                    if (m == null) continue;
                    Msg x = new Msg();
                    x.id = m.optString("id");
                    x.peer = conv;
                    x.sender = m.optString("sender", null);
                    x.body = m.optString("body");
                    x.replyTo = m.optString("reply", null);
                    x.fileName = m.optString("fname", null);
                    x.fileMime = m.optString("fmime", null);
                    x.filePath = m.optString("fpath", null);
                    x.raw = m.optString("raw", null);
                    x.outgoing = m.optBoolean("out");
                    x.ts = m.optLong("ts");
                    x.fileSize = m.optLong("fsize");
                    x.status = m.optInt("status");
                    x.kind = m.optInt("kind");
                    x.total = m.optInt("total");
                    x.receipt = m.optInt("receipt");
                    list.add(x);
                    byId.put(x.id, x);
                }
                list.sort((p, q) -> Long.compare(p.ts, q.ts));
                convs.put(conv, list);
            } catch (Exception ignored) { }
        }
    }

    private void markState() { dirtyState = true; scheduleSave(); }
    private void markConv(String conv) { dirtyConvs.add(conv); scheduleSave(); }

    private boolean saveScheduled;

    private void scheduleSave() {
        if (saveScheduled) return;
        saveScheduled = true;
        saver.schedule(this::saveNow, 700, TimeUnit.MILLISECONDS);
    }

    /** Sofort speichern (z. B. beim Beenden). */
    synchronized void saveNow() {
        saveScheduled = false;
        try {
            if (dirtyState) {
                dirtyState = false;
                JSONObject o = new JSONObject();
                o.put("prefs", prefs);
                JSONArray ca = new JSONArray();
                for (Contact c : contacts.values()) {
                    ca.put(new JSONObject().put("pub", c.pub).put("name", c.name == null ? "" : c.name)
                            .put("pname", c.profileName).put("avatar", c.avatar).put("avatarTs", c.avatarTs)
                            .put("profileSent", c.profileSent).put("lastTs", c.lastTs).put("unread", c.unread));
                }
                o.put("contacts", ca);
                JSONArray ga = new JSONArray();
                for (Group g : groups.values()) {
                    ga.put(new JSONObject().put("gid", g.gid).put("name", g.name).put("gkey", g.gkey).put("owner", g.owner)
                            .put("avatar", g.avatar).put("created", g.created).put("lastTs", g.lastTs).put("unread", g.unread)
                            .put("members", new JSONArray(g.members)).put("admins", new JSONArray(g.admins)));
                }
                o.put("groups", ga);
                JSONObject r = new JSONObject();
                for (Map.Entry<String, LinkedHashMap<String, String>> e : reactions.entrySet()) {
                    JSONObject m = new JSONObject();
                    for (Map.Entry<String, String> e2 : e.getValue().entrySet()) m.put(e2.getKey(), e2.getValue());
                    r.put(e.getKey(), m);
                }
                o.put("reactions", r);
                JSONObject b = new JSONObject();
                for (Map.Entry<String, long[]> e : bans.entrySet()) b.put(e.getKey(), new JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));
                o.put("bans", b);
                JSONObject n = new JSONObject();
                for (Map.Entry<String, String[]> e : nameOv.entrySet()) n.put(e.getKey(), new JSONArray().put(e.getValue()[0]).put(e.getValue()[1]).put(e.getValue()[2]));
                o.put("names", n);
                o.put("deleted", new JSONArray(new ArrayList<>(deleted)));
                JSONObject ob = new JSONObject();
                for (Map.Entry<String, String[]> e : outbox.entrySet()) ob.put(e.getKey(), new JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));
                o.put("outbox", ob);
                writeAtomic(new File(dir, "state.json"), o.toString());
            }
            for (String conv : new ArrayList<>(dirtyConvs)) {
                dirtyConvs.remove(conv);
                List<Msg> list = convs.get(conv);
                File f = new File(msgDir, conv + ".json");
                if (list == null || list.isEmpty()) { f.delete(); continue; }
                JSONArray a = new JSONArray();
                for (Msg m : list) {
                    a.put(new JSONObject().put("id", m.id).put("sender", m.sender).put("body", m.body).put("reply", m.replyTo)
                            .put("fname", m.fileName).put("fmime", m.fileMime).put("fpath", m.filePath)
                            .put("raw", m.status == PENDING ? m.raw : null).put("out", m.outgoing).put("ts", m.ts)
                            .put("fsize", m.fileSize).put("status", m.status).put("kind", m.kind).put("total", m.total)
                            .put("receipt", m.receipt));
                }
                writeAtomic(f, a.toString());
            }
        } catch (Exception ignored) { }
    }

    private static void writeAtomic(File f, String text) throws Exception {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ================================================================== Einstellungen

    synchronized String pref(String k, String def) { return prefs.optString(k, def); }
    synchronized long prefLong(String k, long def) { return prefs.optLong(k, def); }
    synchronized boolean prefBool(String k, boolean def) { return prefs.optBoolean(k, def); }
    synchronized void setPref(String k, Object v) { prefs.put(k, v); markState(); }
    synchronized void setPref(String k, long v) { prefs.put(k, v); markState(); }
    synchronized void setPref(String k, boolean v) { prefs.put(k, v); markState(); }

    // ================================================================== Kontakte

    synchronized Contact contact(String pub) { return contacts.get(pub); }

    synchronized List<Contact> contactList() { return new ArrayList<>(contacts.values()); }

    synchronized List<String> contactKeys() { return new ArrayList<>(contacts.keySet()); }

    synchronized boolean addContact(String pub, String name) {
        if (contacts.containsKey(pub)) return false;
        Contact c = new Contact();
        c.pub = pub;
        c.name = name == null ? "" : name;
        c.lastTs = System.currentTimeMillis();
        contacts.put(pub, c);
        markState();
        return true;
    }

    synchronized void rename(String pub, String name) {
        Contact c = contacts.get(pub);
        if (c == null) return;
        c.name = name == null ? "" : name;
        markState();
    }

    synchronized void deleteContact(String pub) {
        contacts.remove(pub);
        List<Msg> list = convs.remove(pub);
        if (list != null) for (Msg m : list) { byId.remove(m.id); if (m.filePath != null) new File(m.filePath).delete(); }
        markState();
        markConv(pub);
    }

    synchronized void setProfileName(String pub, String name) {
        Contact c = contacts.get(pub);
        if (c == null) return;
        c.profileName = name;
        markState();
    }

    synchronized String profileName(String pub) { Contact c = contacts.get(pub); return c == null ? null : c.profileName; }

    synchronized void setProfile(String pub, String avatarPath, long ts) {
        Contact c = contacts.get(pub);
        if (c == null) return;
        c.avatar = avatarPath;
        c.avatarTs = ts;
        markState();
    }

    synchronized void setProfileSent(String pub, long ts) {
        Contact c = contacts.get(pub);
        if (c == null) return;
        c.profileSent = ts;
        markState();
    }

    // ================================================================== Nachrichten

    synchronized boolean hasMessage(String id) { return byId.containsKey(id); }
    synchronized Msg message(String id) { return byId.get(id); }

    synchronized List<Msg> messages(String conv) {
        List<Msg> l = convs.get(conv);
        return l == null ? new ArrayList<>() : new ArrayList<>(l);
    }

    synchronized Msg lastMessage(String conv) {
        List<Msg> l = convs.get(conv);
        return l == null || l.isEmpty() ? null : l.get(l.size() - 1);
    }

    /** Neue Nachricht einfügen. false, wenn es sie schon gibt. */
    synchronized boolean insert(Msg m) {
        if (byId.containsKey(m.id)) return false;
        List<Msg> l = convs.computeIfAbsent(m.peer, k -> new ArrayList<>());
        int pos = l.size();
        while (pos > 0 && l.get(pos - 1).ts > m.ts) pos--;
        l.add(pos, m);
        byId.put(m.id, m);
        Contact c = contacts.get(m.peer);
        Group g = groups.get(m.peer);
        if (c != null && m.ts > c.lastTs) c.lastTs = m.ts;
        if (g != null && m.ts > g.lastTs) g.lastTs = m.ts;
        markConv(m.peer);
        markState();
        return true;
    }

    synchronized void changedMsg(Msg m) { if (m != null) markConv(m.peer); }

    synchronized String deleteMessage(String id) {
        Msg m = byId.remove(id);
        if (m == null) return null;
        List<Msg> l = convs.get(m.peer);
        if (l != null) l.remove(m);
        deleted.add(id);
        while (deleted.size() > 2000) deleted.remove(deleted.iterator().next());
        reactions.remove(id);
        markConv(m.peer);
        markState();
        return m.filePath;
    }

    /** Vom Gegenüber „für alle“ gelöscht – nur eingehende eigene Kopien des Absenders entfernen. */
    synchronized String deleteIncoming(String id) {
        Msg m = byId.get(id);
        if (m == null || m.outgoing) return null;
        return deleteMessage(id);
    }

    synchronized boolean isDeleted(String id) { return deleted.contains(id); }

    /** Relay hat ein Ereignis bestätigt. Gibt die betroffene Unterhaltung zurück oder null. */
    synchronized String markSent(String eventId) {
        Msg m = byId.get(eventId);
        if (m != null && m.status == PENDING) {
            m.status = SENT;
            m.raw = null;
            markConv(m.peer);
            return m.peer;
        }
        String[] ob = outbox.remove(eventId);
        if (ob == null) return null;
        markState();
        String fid = ob[1];
        if (fid == null || fid.isEmpty()) return null;
        for (String[] v : outbox.values()) if (fid.equals(v[1])) return null; // noch Stücke offen
        Msg f = byId.get(fid);
        if (f != null && f.status == PENDING) { f.status = SENT; markConv(f.peer); return f.peer; }
        return null;
    }

    synchronized void addOutbox(String eventId, String fid, String raw) {
        outbox.put(eventId, new String[]{raw, fid == null ? "" : fid});
        markState();
    }

    /** Alles, was noch nicht bestätigt ist (beim Verbinden erneut senden). */
    synchronized List<String> pendingRaw() {
        List<String> out = new ArrayList<>();
        for (List<Msg> l : convs.values()) for (Msg m : l) if (m.status == PENDING && m.raw != null) out.add(m.raw);
        for (String[] v : outbox.values()) out.add(v[0]);
        return out;
    }

    synchronized boolean applyReceipt(String peer, List<String> ids, int level) {
        boolean ch = false;
        for (String id : ids) {
            Msg m = byId.get(id);
            if (m == null || !m.outgoing || !peer.equals(m.peer) || m.status >= level) continue;
            m.status = level;
            ch = true;
        }
        if (ch) markConv(peer);
        return ch;
    }

    synchronized List<String> needReceipt(String peer, int level) {
        List<String> out = new ArrayList<>();
        List<Msg> l = convs.get(peer);
        if (l != null) for (Msg m : l) if (!m.outgoing && m.complete() && m.receipt < level) out.add(m.id);
        return out;
    }

    synchronized void markReceipt(List<String> ids, int level) {
        for (String id : ids) {
            Msg m = byId.get(id);
            if (m != null && m.receipt < level) { m.receipt = level; markConv(m.peer); }
        }
    }

    synchronized void setUnread(String conv, int n) {
        Contact c = contacts.get(conv);
        if (c != null) { c.unread = n; markState(); return; }
        Group g = groups.get(conv);
        if (g != null) { g.unread = n; markState(); }
    }

    synchronized void addUnread(String conv) {
        Contact c = contacts.get(conv);
        if (c != null) { c.unread++; markState(); return; }
        Group g = groups.get(conv);
        if (g != null) { g.unread++; markState(); }
    }

    // ================================================================== Anhänge

    synchronized void setFileMeta(String fid, int kind, String name, String mime, long size, int total, String replyTo, String sha) {
        Msg m = byId.get(fid);
        if (m == null) return;
        m.kind = kind;
        m.fileName = name;
        m.fileMime = mime;
        m.fileSize = size;
        m.total = total;
        m.replyTo = replyTo;
        m.body = sha;
        markConv(m.peer);
    }

    /** Stück speichern; gibt die Zahl der vorhandenen Stücke zurück. */
    synchronized int addChunk(String fid, int i, byte[] data, int n) {
        byte[][] p = parts.get(fid);
        if (p == null || p.length != n) { p = new byte[n][]; parts.put(fid, p); }
        p[i] = data;
        int have = 0;
        for (byte[] b : p) if (b != null) have++;
        return have;
    }

    synchronized byte[][] chunks(String fid) { return parts.get(fid); }

    synchronized void fileDone(String fid, String path) {
        parts.remove(fid);
        Msg m = byId.get(fid);
        if (m == null) return;
        m.filePath = path;
        markConv(m.peer);
    }

    // ================================================================== Reaktionen

    synchronized void setReaction(String msgId, String pub, String emoji, long ts) {
        if (msgId == null) return;
        String key = msgId + ":" + pub;
        Long old = reactTs.get(key);
        if (old != null && old > ts) return;
        reactTs.put(key, ts);
        LinkedHashMap<String, String> m = reactions.computeIfAbsent(msgId, k -> new LinkedHashMap<>());
        if (emoji == null || emoji.isEmpty()) m.remove(pub); else m.put(pub, emoji);
        if (m.isEmpty()) reactions.remove(msgId);
        markState();
    }

    /** Emoji → Anzahl, in Reihenfolge des ersten Auftretens. */
    synchronized LinkedHashMap<String, Integer> reactions(String msgId) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        Map<String, String> m = reactions.get(msgId);
        if (m != null) for (String e : m.values()) out.merge(e, 1, Integer::sum);
        return out;
    }

    synchronized String myReaction(String msgId, String me) {
        Map<String, String> m = reactions.get(msgId);
        return m == null ? null : m.get(me);
    }

    // ================================================================== Gruppen

    synchronized Group group(String gid) { return groups.get(gid); }
    synchronized List<Group> groupList() { return new ArrayList<>(groups.values()); }
    synchronized List<String> gids() { return new ArrayList<>(groups.keySet()); }

    synchronized void upsertGroup(String gid, String name, String gkey, String owner, long created) {
        Group g = groups.get(gid);
        if (g == null) { g = new Group(); g.gid = gid; g.lastTs = System.currentTimeMillis(); groups.put(gid, g); }
        g.name = name == null ? "" : name;
        g.gkey = gkey;
        g.owner = owner;
        g.created = created;
        markState();
    }

    synchronized void setMembers(String gid, List<String> members, Set<String> admins) {
        Group g = groups.get(gid);
        if (g == null) return;
        g.members = new ArrayList<>(members);
        g.admins = new HashSet<>(admins);
        markState();
    }

    synchronized void setGroupAvatar(String gid, String path) {
        Group g = groups.get(gid);
        if (g == null) return;
        g.avatar = path;
        markState();
    }

    synchronized boolean isMember(String gid, String pub) { Group g = groups.get(gid); return g != null && g.members.contains(pub); }
    synchronized boolean isAdmin(String gid, String pub) { Group g = groups.get(gid); return g != null && (g.admins.contains(pub) || pub.equals(g.owner)); }
    synchronized List<String> memberPubs(String gid) { Group g = groups.get(gid); return g == null ? new ArrayList<>() : new ArrayList<>(g.members); }
    synchronized Set<String> adminPubs(String gid) { Group g = groups.get(gid); return g == null ? new HashSet<>() : new HashSet<>(g.admins); }

    synchronized void deleteGroup(String gid) {
        groups.remove(gid);
        List<Msg> list = convs.remove(gid);
        if (list != null) for (Msg m : list) { byId.remove(m.id); if (m.filePath != null) new File(m.filePath).delete(); }
        markState();
        markConv(gid);
    }

    // ================================================================== Moderation (vom Admin)

    synchronized void setBan(String key, long until, long evTs) {
        long[] b = bans.get(key);
        if (b != null && evTs < b[1]) return;
        bans.put(key, new long[]{until, evTs});
        markState();
    }

    synchronized boolean isBanned(String key) {
        long[] b = bans.get(key);
        if (b == null) return false;
        return b[0] == -1 || b[0] > System.currentTimeMillis();
    }

    synchronized long banUntil(String key) { long[] b = bans.get(key); return b == null ? 0 : b[0]; }

    synchronized void setNameOverride(String pub, String name, long until, long evTs) {
        String[] o = nameOv.get(pub);
        if (o != null) { try { if (evTs < Long.parseLong(o[2])) return; } catch (Exception ignored) { } }
        nameOv.put(pub, new String[]{name == null ? "" : name, String.valueOf(until), String.valueOf(evTs)});
        markState();
    }

    /** Aktiver Admin-Name oder null. */
    synchronized String nameOverride(String pub) {
        String[] o = nameOv.get(pub);
        if (o == null) return null;
        long until;
        try { until = Long.parseLong(o[1]); } catch (Exception e) { return null; }
        if (until == 0) return null;
        if (until != -1 && until <= System.currentTimeMillis()) return null;
        return o[0];
    }

    // ================================================================== Übersicht

    /** Alle Unterhaltungen (Kontakte und Gruppen), neueste zuerst. */
    synchronized List<Object> conversations() {
        List<Object> all = new ArrayList<>();
        all.addAll(contacts.values());
        all.addAll(groups.values());
        all.sort((a, b) -> Long.compare(lastTs(b), lastTs(a)));
        return all;
    }

    static long lastTs(Object o) { return o instanceof Group ? ((Group) o).lastTs : ((Contact) o).lastTs; }

    synchronized int totalUnread() {
        int n = 0;
        for (Contact c : contacts.values()) n += c.unread;
        for (Group g : groups.values()) n += g.unread;
        return n;
    }

    static List<String> emptyIfNull(List<String> l) { return l == null ? Collections.emptyList() : l; }
}
