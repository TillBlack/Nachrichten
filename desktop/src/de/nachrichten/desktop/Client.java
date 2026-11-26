package de.nachrichten.desktop;

import de.nachrichten.crypto.Event;
import de.nachrichten.crypto.Nip44;
import de.nachrichten.crypto.Proto;
import de.nachrichten.crypto.Secp;
import de.nachrichten.net.Ws;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Netz und Protokoll der Windows-Version – dieselben Nachrichtenformate wie die Android-App (Klasse Net),
 * damit PC und Handy miteinander schreiben können. Alle Arbeit läuft auf einem eigenen Netz-Thread.
 */
final class Client {

    /** Öffentlicher Schlüssel des Admins (identisch mit der Android-App). */
    static final String ADMIN = "dabc875f8567880d86aeaf1415c1ec3e851e88f680228aad58a7a6a6670bdbf7";
    static final String[] DEFAULT_RELAYS = {
            "wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom"};

    interface Listener {
        void onChanged(String conv);
        void onTyping(String peer);
        void onPresence(String peer);
        void onStatus(int connected, int total);
        void onNotify(String conv, String title, String text);
        void onLockChanged();
    }

    final Store db;
    final Keys keys;
    final String device;
    private final List<Listener> listeners = new ArrayList<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "netz");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Conn> conns = new ConcurrentHashMap<>();
    private final Map<String, byte[]> convKeys = new HashMap<>();
    private final Map<String, Long> presence = new ConcurrentHashMap<>();
    private final Set<String> seen = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 30000; }
    });

    /** Gerade geöffnete Unterhaltung, solange das Fenster im Vordergrund ist (sonst null). */
    volatile String openChat;
    /** Fenster ist sichtbar und aktiv (für Online-Status). */
    volatile boolean uiActive;

    private long startedAt, lastSeenAtStart;

    Client(Store db, Keys keys) {
        this.db = db;
        this.keys = keys;
        this.device = Keys.deviceHash(db);
    }

    void addListener(Listener l) { listeners.add(l); }

    private void changed(String conv) { for (Listener l : listeners) try { l.onChanged(conv); } catch (Exception ignored) { } }

    // ================================================================== Start / Verbindungen

    void start() {
        startedAt = System.currentTimeMillis() / 1000;
        lastSeenAtStart = db.prefLong("lastSeen", 0);
        for (String url : relays()) {
            Conn c = new Conn(url);
            conns.put(url, c);
            c.start();
        }
        worker.scheduleWithFixedDelay(this::watchdog, 20, 20, TimeUnit.SECONDS);
        worker.scheduleWithFixedDelay(this::maybePresence, 5, 50, TimeUnit.SECONDS);
        worker.schedule(this::maybeSendHello, 8, TimeUnit.SECONDS);
        status();
    }

    void stop() {
        for (Conn c : conns.values()) c.stop();
        conns.clear();
    }

    List<String> relays() {
        List<String> out = new ArrayList<>();
        JSONArray a = db.prefs.optJSONArray("relays");
        if (a != null) for (int i = 0; i < a.length(); i++) { String u = a.optString(i); if (u.startsWith("ws")) out.add(u); }
        if (out.isEmpty()) Collections.addAll(out, DEFAULT_RELAYS);
        return out;
    }

    int connectedCount() { int n = 0; for (Conn c : conns.values()) if (c.open) n++; return n; }

    private void status() {
        int n = connectedCount(), t = conns.size();
        for (Listener l : listeners) try { l.onStatus(n, t); } catch (Exception ignored) { }
    }

    private void watchdog() {
        for (Conn c : conns.values()) c.pingIfIdle();
    }

    private final class Conn implements Ws.Listener {
        final String url;
        volatile Ws ws;
        volatile boolean open, stopped;
        int failures;
        long lastPing;
        final ExecutorService sender;

        Conn(String url) {
            this.url = url;
            this.sender = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "senden " + url);
                t.setDaemon(true);
                return t;
            });
        }

        void enqueue(String msg) {
            sender.execute(() -> {
                Ws w = ws;
                if (!open || w == null) return;
                w.send(msg);
                try { Thread.sleep(150); } catch (InterruptedException ignored) { }
            });
        }

        void start() {
            Thread t = new Thread(() -> {
                while (!stopped) {
                    Ws w = new Ws(url, this);
                    ws = w;
                    w.run();
                    if (stopped) break;
                    failures++;
                    long wait = Math.min(60000, 2000L * (1L << Math.min(5, failures - 1)));
                    try { Thread.sleep(wait); } catch (InterruptedException e) { break; }
                }
            }, "relay " + url);
            t.setDaemon(true);
            t.start();
        }

        void stop() {
            stopped = true;
            sender.shutdownNow();
            Ws w = ws;
            if (w != null) new Thread(w::close).start();
        }

        @Override public void onOpen(Ws w) {
            open = true;
            failures = 0;
            subscribe(w);
            for (String raw : db.pendingRaw()) enqueue("[\"EVENT\"," + raw + "]");
            worker.schedule(Client.this::maybePresence, 3, TimeUnit.SECONDS);
            status();
        }

        @Override public void onText(Ws w, String text) { worker.execute(() -> handle(text)); }

        @Override public void onClosed(Ws w, String reason) { open = false; status(); }

        void pingIfIdle() {
            Ws w = ws;
            if (!open || w == null) return;
            long now = System.currentTimeMillis();
            if (now - lastPing > 25000) { lastPing = now; new Thread(w::ping).start(); }
        }
    }

    void resubscribeAll() {
        worker.execute(() -> { for (Conn c : conns.values()) { Ws w = c.ws; if (c.open && w != null) subscribe(w); } });
    }

    private void subscribe(Ws w) {
        String me = keys.pubHex();
        long since = lastSeenAtStart > 0 ? lastSeenAtStart - 2 * 3600 : 0;
        String sinceJson = since > 0 ? ",\"since\":" + since : "";
        String admin = ",{\"kinds\":[" + Proto.KIND_ADMIN + "],\"authors\":[\"" + ADMIN + "\"],\"limit\":3000}";
        String groups = "";
        List<String> gids = db.gids();
        if (!gids.isEmpty()) {
            StringBuilder gb = new StringBuilder(",{\"kinds\":[" + Proto.KIND_GROUP + "],\"#g\":[");
            for (int i = 0; i < gids.size(); i++) { if (i > 0) gb.append(','); gb.append('"').append(gids.get(i)).append('"'); }
            gb.append("]").append(sinceJson).append(",\"limit\":1000}");
            groups = gb.toString();
        }
        String req = "[\"REQ\",\"dm\","
                + "{\"kinds\":[4," + Proto.KIND_RECEIPT + "],\"#p\":[\"" + me + "\"]" + sinceJson + ",\"limit\":1000},"
                + "{\"kinds\":[4],\"authors\":[\"" + me + "\"]" + sinceJson + ",\"limit\":1000},"
                + "{\"kinds\":[" + Proto.KIND_TYPING + "," + Proto.KIND_PRESENCE + "],\"#p\":[\"" + me + "\"],\"since\":"
                + (System.currentTimeMillis() / 1000 - 120) + "}"
                + groups + admin + "]";
        w.send(req);
    }

    /** Nur für Tests: bekommt jedes ausgehende Ereignis zusätzlich. */
    volatile java.util.function.Consumer<String> testSink;

    private void broadcast(String raw) {
        java.util.function.Consumer<String> ts = testSink;
        if (ts != null) ts.accept(raw);
        String msg = "[\"EVENT\"," + raw + "]";
        for (Conn c : conns.values()) if (c.open) c.enqueue(msg);
    }

    // ================================================================== Eingang

    void handle(String text) {
        try {
            JSONArray a = new JSONArray(text);
            String type = a.optString(0);
            if (type.equals("EVENT") && a.length() >= 3) {
                handleEvent(a.getJSONObject(2));
            } else if (type.equals("OK") && a.length() >= 3) {
                if (a.optBoolean(2)) {
                    String conv = db.markSent(a.getString(1));
                    if (conv != null) changed(conv);
                }
            }
        } catch (Exception ignored) { }
    }

    private byte[] convKey(String peerHex) {
        byte[] k = convKeys.get(peerHex);
        if (k == null) {
            k = Nip44.conversationKey(keys.priv(), Event.unhex(peerHex));
            convKeys.put(peerHex, k);
        }
        return k;
    }

    void handleEvent(JSONObject o) throws Exception {
        int kind = o.optInt("kind");
        if (kind != 4 && kind != Proto.KIND_RECEIPT && kind != Proto.KIND_TYPING && kind != Proto.KIND_PRESENCE
                && kind != Proto.KIND_GROUP && kind != Proto.KIND_ADMIN) return;
        String id = o.getString("id");
        synchronized (seen) { if (seen.contains(id)) return; seen.add(id); }
        if ((kind == 4 || kind == Proto.KIND_GROUP) && db.hasMessage(id)) return;

        String me = keys.pubHex();
        String author = o.getString("pubkey");
        if (kind == Proto.KIND_GROUP) { handleGroupMessage(o, id, author, me); return; }
        if (kind == Proto.KIND_ADMIN) { handleAdmin(o, author); return; }

        String p = tag(o, "p");
        if (p == null || !p.matches("[0-9a-f]{64}") || !author.matches("[0-9a-f]{64}")) return;
        boolean outgoing = author.equals(me);
        if (!outgoing && db.isBanned(author)) return;
        if (!outgoing && !p.equals(me)) return;
        String peer = outgoing ? p : author;
        if (peer.equals(me) || !Secp.validPublicKey(Event.unhex(peer))) return;

        // Die Entschlüsselung prüft per HMAC, dass die Nachricht wirklich von diesem Absender (oder mir) stammt.
        String plain = Nip44.decrypt(o.optString("content"), convKey(peer));
        if (plain == null) return;
        long createdAt = o.optLong("created_at");

        if (kind == Proto.KIND_TYPING) {
            long now = System.currentTimeMillis() / 1000;
            if (!outgoing && Math.abs(now - createdAt) < 30 && db.contact(peer) != null)
                for (Listener l : listeners) l.onTyping(peer);
            return;
        }
        if (kind == Proto.KIND_PRESENCE) {
            long now = System.currentTimeMillis() / 1000;
            if (!outgoing && Math.abs(now - createdAt) < 120 && db.contact(peer) != null) {
                presence.put(peer, System.currentTimeMillis());
                for (Listener l : listeners) l.onPresence(peer);
            }
            return;
        }
        if (kind == Proto.KIND_RECEIPT) {
            if (outgoing) return;
            try {
                JSONObject env = new JSONObject(plain);
                int s = env.optInt("s");
                JSONArray ids = env.optJSONArray("ids");
                if ((s != Store.DELIVERED && s != Store.READ) || ids == null) return;
                List<String> list = new ArrayList<>();
                for (int i = 0; i < ids.length() && i < 500; i++) { String x = ids.optString(i); if (x.matches("[0-9a-f]{32,64}")) list.add(x); }
                if (db.applyReceipt(peer, list, s)) changed(peer);
            } catch (Exception ignored) { }
            return;
        }
        if (plain.startsWith("{\"nx\"")) {
            try {
                JSONObject env0 = new JSONObject(plain);
                if ("group".equals(env0.optString("t"))) { if (!outgoing) handleGroupControl(author, env0); return; }
            } catch (Exception ignored) { }
        }

        if (db.contact(peer) == null) db.addContact(peer, "");
        updateLastSeen(createdAt);

        String body = plain, replyTo = null;
        if (plain.startsWith("{\"nx\"")) {
            JSONObject env;
            try { env = new JSONObject(plain); } catch (Exception e) { env = null; }
            if (env != null) {
                String t = env.optString("t");
                if (!outgoing) learnName(peer, env.optString("sn", null));
                if (t.equals("react")) {
                    if (!outgoing) { db.setReaction(cleanId(env.optString("m", "")), author, env.optString("e", ""), createdAt * 1000L); changed(peer); }
                    return;
                }
                if (t.equals("file")) { handleChunk(peer, outgoing, createdAt, env); return; }
                if (t.equals("profile")) { if (!outgoing) handleProfile(peer, env); return; }
                if (t.equals("del")) {
                    if (!outgoing) {
                        String did = env.optString("id", null);
                        if (did != null) { String path = db.deleteIncoming(did); if (path != null) new File(path).delete(); changed(peer); }
                    }
                    return;
                }
                if (t.equals("text")) { body = env.optString("b"); replyTo = cleanId(env.optString("r", null)); }
                else body = "[Diese Nachricht kann die PC-Version noch nicht anzeigen]";
            }
        }

        Store.Msg m = new Store.Msg();
        m.id = id;
        m.peer = peer;
        m.outgoing = outgoing;
        m.body = body;
        m.replyTo = replyTo;
        m.ts = createdAt * 1000L;
        m.status = Store.SENT;
        m.receipt = fresh(createdAt) ? 0 : Store.READ; // alte Nachrichten bei der Wiederherstellung nicht quittieren
        if (!db.insert(m)) return;
        if (!outgoing && !peer.equals(openChat) && fresh(createdAt)) db.addUnread(peer);
        changed(peer);
        notifyIfNew(peer, outgoing, createdAt, nameOf(peer), body);
        if (!outgoing) receiptSoon(peer);
    }

    static String tag(JSONObject o, String name) {
        JSONArray tags = o.optJSONArray("tags");
        if (tags != null) for (int i = 0; i < tags.length(); i++) {
            JSONArray t = tags.optJSONArray(i);
            if (t != null && t.length() >= 2 && name.equals(t.optString(0))) return t.optString(1);
        }
        return null;
    }

    private boolean fresh(long createdAt) { return lastSeenAtStart > 0 || createdAt > startedAt - 120; }

    private static String cleanId(String s) { return s != null && s.matches("[0-9a-f]{32,64}") ? s : null; }

    private void updateLastSeen(long createdAt) {
        long lastSeen = db.prefLong("lastSeen", 0);
        if (createdAt > lastSeen && createdAt < System.currentTimeMillis() / 1000 + 600) db.setPref("lastSeen", createdAt);
    }

    private void learnName(String peer, String sn) {
        if (sn == null) return;
        sn = sn.trim();
        if (sn.isEmpty() || peer.equals(ADMIN)) return;
        if (sn.length() > 40) sn = sn.substring(0, 40);
        db.setProfileName(peer, sn);
    }

    private void notifyIfNew(String conv, boolean outgoing, long createdAt, String title, String text) {
        if (outgoing || !fresh(createdAt) || conv.equals(openChat)) return;
        for (Listener l : listeners) try { l.onNotify(conv, title, text); } catch (Exception ignored) { }
    }

    private void handleProfile(String peer, JSONObject env) {
        long ts = env.optLong("ts");
        Store.Contact c = db.contact(peer);
        if (c == null) { db.addContact(peer, ""); c = db.contact(peer); }
        if (c == null || ts <= c.avatarTs) return;
        String img = env.optString("img");
        String path = null;
        if (!img.isEmpty()) {
            try {
                byte[] jpg = java.util.Base64.getMimeDecoder().decode(img);
                if (jpg.length > 60000) return;
                File f = new File(db.mediaDir, "avatar_" + peer.substring(0, 16) + "_" + ts + ".jpg");
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(jpg); }
                path = f.getAbsolutePath();
            } catch (Exception e) { return; }
        }
        if (c.avatar != null) new File(c.avatar).delete();
        db.setProfile(peer, path, ts);
        String name = env.optString("name").trim();
        if (name.length() > 40) name = name.substring(0, 40);
        if (!name.isEmpty()) db.setProfileName(peer, name);
        changed(null);
    }

    private void handleChunk(String peer, boolean outgoing, long createdAt, JSONObject env) {
        String fid = env.optString("f");
        if (!fid.matches("[0-9a-f]{32}")) return;
        int i = env.optInt("i", -1), n = env.optInt("n", 0);
        if (n < 1 || n > Proto.chunkCount(Proto.MAX_FILE) || i < 0 || i >= n) return;
        if (db.isDeleted(fid)) return;
        Store.Msg m = db.message(fid);
        if (m != null && (!m.peer.equals(peer) || m.complete())) return;
        byte[] data;
        try { data = java.util.Base64.getMimeDecoder().decode(env.optString("d")); } catch (Exception e) { return; }
        if (data.length > Proto.CHUNK) return;
        boolean isNew = false;
        if (m == null) {
            m = new Store.Msg();
            m.id = fid; m.peer = peer; m.outgoing = outgoing; m.body = ""; m.kind = Store.FILE;
            m.ts = createdAt * 1000L; m.status = Store.SENT; m.total = n;
            db.insert(m);
            isNew = true;
        }
        if (i == 0) {
            JSONObject meta = env.optJSONObject("m");
            if (meta == null) return;
            if (!outgoing) learnName(peer, meta.optString("sn", null));
            if (!applyMeta(fid, n, env, meta)) return;
        }
        finishIfComplete(fid, peer, outgoing, createdAt, isNew, n, data, i, false);
    }

    private boolean applyMeta(String fid, int n, JSONObject env, JSONObject meta) {
        long size = meta.optLong("size", -1);
        String sha = meta.optString("sha");
        if (size < 0 || size > Proto.MAX_FILE || !sha.matches("[0-9a-f]{64}")) return false;
        String mime = meta.optString("mime", "application/octet-stream");
        if (!mime.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) mime = "application/octet-stream";
        boolean img = meta.optBoolean("img") && mime.startsWith("image/");
        db.setFileMeta(fid, img ? Store.IMAGE : Store.FILE, Proto.safeName(meta.optString("name")), mime, size, n,
                cleanId(env.optString("r", null)), sha);
        return true;
    }

    private void finishIfComplete(String fid, String conv, boolean outgoing, long createdAt, boolean isNew, int n, byte[] data, int i, boolean group) {
        int have = db.addChunk(fid, i, data, n);
        Store.Msg m = db.message(fid);
        if (m != null && have >= n && m.fileName != null && m.body != null && !m.body.isEmpty()) {
            byte[] all = Proto.assemble(db.chunks(fid), m.fileSize, m.body);
            if (all != null) {
                File f = new File(db.mediaDir, fid + "_" + m.fileName);
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(all);
                    db.fileDone(fid, f.getAbsolutePath());
                    if (!outgoing && !conv.equals(openChat) && fresh(createdAt)) db.addUnread(conv);
                    if (!group) {
                        if (!outgoing && fresh(createdAt)) receiptSoon(conv);
                        else if (!outgoing) db.markReceipt(Collections.singletonList(fid), Store.READ);
                    }
                    String title = group ? groupTitle(conv, m.sender) : nameOf(conv);
                    notifyIfNew(conv, outgoing, createdAt, title, previewText(m.kind, m.body, m.fileName));
                } catch (Exception ignored) { }
            }
        }
        changed(conv);
    }

    private String groupTitle(String gid, String sender) {
        Store.Group g = db.group(gid);
        return (g == null ? "Gruppe" : g.name) + (sender != null ? " – " + nameOf(sender) : "");
    }

    // ================================================================== Gruppen (Eingang)

    private void handleGroupMessage(JSONObject o, String id, String author, String me) {
        try {
            String gid = tag(o, "g");
            if (gid == null || !author.matches("[0-9a-f]{64}")) return;
            if (db.isBanned(author)) return;
            Store.Group g = db.group(gid);
            if (g == null || !db.isMember(gid, author)) return;
            byte[] gk = Event.unhex(g.gkey);
            if (gk == null) return;
            String plain = Nip44.decrypt(o.optString("content"), gk);
            if (plain == null) return;
            boolean outgoing = author.equals(me);
            long createdAt = o.optLong("created_at");
            String body = plain, replyTo = null;
            if (plain.startsWith("{\"nx\"")) {
                try {
                    JSONObject env = new JSONObject(plain);
                    String t = env.optString("t");
                    if (!outgoing) learnName(author, env.optString("sn", null));
                    if (t.equals("react")) {
                        if (!outgoing) { db.setReaction(cleanId(env.optString("m", "")), author, env.optString("e", ""), createdAt * 1000L); changed(gid); }
                        return;
                    }
                    if (t.equals("file")) { handleGroupChunk(gid, author, outgoing, createdAt, env); return; }
                    if (t.equals("text")) { body = env.optString("b"); replyTo = cleanId(env.optString("r", null)); }
                    else body = "[Diese Nachricht kann die PC-Version noch nicht anzeigen]";
                } catch (Exception ignored) { }
            }
            Store.Msg m = new Store.Msg();
            m.id = id; m.peer = gid; m.sender = author; m.outgoing = outgoing;
            m.body = body; m.replyTo = replyTo; m.ts = createdAt * 1000L; m.status = Store.SENT;
            if (!db.insert(m)) return;
            if (!outgoing && !gid.equals(openChat) && fresh(createdAt)) db.addUnread(gid);
            changed(gid);
            notifyIfNew(gid, outgoing, createdAt, groupTitle(gid, author), body);
        } catch (Exception ignored) { }
    }

    private void handleGroupChunk(String gid, String author, boolean outgoing, long createdAt, JSONObject env) {
        String fid = env.optString("f");
        if (!fid.matches("[0-9a-f]{32}")) return;
        int i = env.optInt("i", -1), n = env.optInt("n", 0);
        if (n < 1 || n > Proto.chunkCount(Proto.MAX_FILE) || i < 0 || i >= n) return;
        if (db.isDeleted(fid)) return;
        Store.Msg m = db.message(fid);
        if (m != null && (!m.peer.equals(gid) || m.complete())) return;
        byte[] data;
        try { data = java.util.Base64.getMimeDecoder().decode(env.optString("d")); } catch (Exception e) { return; }
        if (data.length > Proto.CHUNK) return;
        boolean isNew = false;
        if (m == null) {
            m = new Store.Msg();
            m.id = fid; m.peer = gid; m.sender = author; m.outgoing = outgoing;
            m.body = ""; m.kind = Store.FILE; m.ts = createdAt * 1000L; m.status = Store.SENT; m.total = n;
            db.insert(m);
            isNew = true;
        }
        if (i == 0) {
            JSONObject meta = env.optJSONObject("m");
            if (meta == null) return;
            if (!applyMeta(fid, n, env, meta)) return;
        }
        finishIfComplete(fid, gid, outgoing, createdAt, isNew, n, data, i, true);
    }

    private void handleGroupControl(String author, JSONObject env) {
        try {
            if (!"sync".equals(env.optString("op"))) return;
            String gid = env.optString("gid", "");
            String gkey = env.optString("gkey", "");
            if (!gid.matches("[0-9a-f]{64}") || !gkey.matches("[0-9a-f]{64}")) return;
            String name = env.optString("name", "");
            String owner = env.optString("owner", "");
            if (!owner.matches("[0-9a-f]{64}")) owner = author;
            JSONArray mem = env.optJSONArray("members");
            if (mem == null) return;
            List<String> members = new ArrayList<>();
            for (int i = 0; i < mem.length(); i++) { String x = mem.optString(i); if (x.matches("[0-9a-f]{64}")) members.add(x); }
            Set<String> admins = new HashSet<>();
            JSONArray adm = env.optJSONArray("admins");
            if (adm != null) for (int i = 0; i < adm.length(); i++) { String x = adm.optString(i); if (x.matches("[0-9a-f]{64}")) admins.add(x); }
            String me = keys.pubHex();
            Store.Group existing = db.group(gid);
            if (!members.contains(me)) {
                if (existing != null) { db.deleteGroup(gid); resubscribeAll(); changed(null); }
                return;
            }
            // Vertrauen: neue Gruppe nur per Einladung eines Kontakts; Änderungen nur von einem bisherigen Admin.
            if (existing == null) { if (!author.equals(me) && db.contact(author) == null) return; }
            else { if (!author.equals(me) && !db.isAdmin(gid, author)) return; }
            admins.add(owner);
            db.upsertGroup(gid, name, gkey, owner, existing == null ? System.currentTimeMillis() : existing.created);
            db.setMembers(gid, members, admins);
            String img = env.optString("img", "");
            if (!img.isEmpty()) {
                try {
                    byte[] jpg = java.util.Base64.getMimeDecoder().decode(img);
                    if (jpg.length > 0 && jpg.length < 400 * 1024) {
                        Store.Group g = db.group(gid);
                        String old = g != null ? g.avatar : null;
                        File f = new File(db.mediaDir, "groupav_" + gid + "_" + System.currentTimeMillis() + ".jpg");
                        try (FileOutputStream out = new FileOutputStream(f)) { out.write(jpg); }
                        db.setGroupAvatar(gid, f.getAbsolutePath());
                        if (old != null && !old.equals(f.getAbsolutePath())) new File(old).delete();
                    }
                } catch (Exception ignored) { }
            }
            resubscribeAll();
            changed(null);
        } catch (Exception ignored) { }
    }

    // ================================================================== Moderation (nur lesen)

    private boolean verifyPublic(JSONObject o) {
        try {
            Event e = new Event();
            e.id = o.getString("id");
            e.pubkey = o.getString("pubkey");
            e.createdAt = o.getLong("created_at");
            e.kind = o.getInt("kind");
            e.content = o.optString("content");
            e.sig = o.getString("sig");
            JSONArray tags = o.optJSONArray("tags");
            List<String[]> tl = new ArrayList<>();
            if (tags != null) for (int i = 0; i < tags.length(); i++) {
                JSONArray t = tags.getJSONArray(i);
                String[] arr = new String[t.length()];
                for (int j = 0; j < t.length(); j++) arr[j] = t.getString(j);
                tl.add(arr);
            }
            e.tags = tl.toArray(new String[0][]);
            return e.verify();
        } catch (Exception ex) { return false; }
    }

    private void handleAdmin(JSONObject o, String author) {
        try {
            if (!ADMIN.equals(author)) return;
            if (!verifyPublic(o)) return; // ohne den privaten Admin-Schlüssel nicht fälschbar
            JSONObject j = new JSONObject(o.optString("content"));
            String op = j.optString("op", "");
            String target = j.optString("target", "");
            if (!target.matches("[0-9a-f]{64}") || ADMIN.equals(target)) return;
            long until = j.optLong("until", 0);
            String value = j.optString("value", "");
            long ts = o.optLong("created_at") * 1000L;
            boolean wasLocked = amBanned();
            switch (op) {
                case "ban":      db.setBan(target, until, ts); break;
                case "unban":    db.setBan(target, 0, ts); break;
                case "bandev":   db.setBan("d:" + target, until, ts); break;
                case "unbandev": db.setBan("d:" + target, 0, ts); break;
                case "name":     db.setNameOverride(target, value, until, ts); break;
                default: return;
            }
            changed(null);
            if (wasLocked != amBanned()) for (Listener l : listeners) l.onLockChanged();
        } catch (Exception ignored) { }
    }

    boolean amBanned() {
        if (!keys.has()) return false;
        return db.isBanned(keys.pubHex()) || db.isBanned("d:" + device);
    }

    boolean deviceBanned() { return db.isBanned("d:" + device); }

    long myBanUntil() {
        long a = db.isBanned(keys.pubHex()) ? db.banUntil(keys.pubHex()) : 0;
        long b = deviceBanned() ? db.banUntil("d:" + device) : 0;
        if (a == -1 || b == -1) return -1;
        return Math.max(a, b);
    }

    // ================================================================== Namen

    String myName() { return db.pref("name", ""); }

    static String fallbackName(String pub) {
        long n;
        try { n = Long.parseLong(pub.substring(0, 8), 16) % 100000L; } catch (Exception e) { n = 0; }
        return "User" + n;
    }

    String nameOf(String pub) {
        if (pub == null) return "";
        if (ADMIN.equals(pub)) return "Admin";
        if (pub.equals(keys.pubHex())) return myName().isEmpty() ? "Ich" : myName();
        String ov = db.nameOverride(pub);
        if (ov != null) return ov.trim().isEmpty() ? fallbackName(pub) : ov;
        Store.Contact c = db.contact(pub);
        if (c != null && c.name != null && !c.name.trim().isEmpty()) return c.name;
        String self = db.profileName(pub);
        if (self != null && !self.trim().isEmpty()) return self;
        return fallbackName(pub);
    }

    /** Selbst gewählter Name, falls er sich vom angezeigten unterscheidet (kleiner Zusatz). */
    String subName(String pub) {
        if (ADMIN.equals(pub) || db.nameOverride(pub) != null) return null;
        String self = db.profileName(pub);
        if (self == null || self.trim().isEmpty()) return null;
        return self.equals(nameOf(pub)) ? null : self;
    }

    boolean isOnline(String pub) {
        Long t = presence.get(pub);
        return t != null && System.currentTimeMillis() - t < 75000;
    }

    static boolean isVoiceName(String name) {
        return name != null && (name.endsWith("voice.aac") || name.endsWith("voice.m4a")
                || name.equals("Sprachnachricht.m4a") || (name.startsWith("v") && name.contains("~voice")));
    }

    static long durFromName(String name) {
        if (name == null || !name.startsWith("v")) return 0;
        int w1 = name.indexOf('~');
        if (w1 < 1) return 0;
        try { return Long.parseLong(name.substring(1, w1)); } catch (Exception e) { return 0; }
    }

    static float[] waveFromName(String name) {
        if (name == null || !name.startsWith("v")) return null;
        int w1 = name.indexOf('~');
        if (w1 < 0) return null;
        int w2 = name.indexOf('~', w1 + 1);
        if (w2 < 0) return null;
        try {
            byte[] b = java.util.Base64.getUrlDecoder().decode(name.substring(w1 + 1, w2));
            float[] a = new float[b.length];
            for (int i = 0; i < b.length; i++) a[i] = (b[i] & 0xFF) / 255f;
            return a;
        } catch (Exception e) { return null; }
    }

    static String fmtShort(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s / 60 + ":" + String.format(java.util.Locale.US, "%02d", s % 60);
    }

    static String previewText(int kind, String body, String fileName) {
        if (kind == Store.IMAGE) return "Foto";
        if (kind == Store.FILE) {
            if (isVoiceName(fileName)) { long d = durFromName(fileName); return "Sprachnachricht" + (d > 0 ? " (" + fmtShort(d) + ")" : ""); }
            return "Datei: " + (fileName == null ? "" : fileName);
        }
        return body == null ? "" : body.replace('\n', ' ');
    }

    // ================================================================== Quittungen, Tippen, Online

    private final Set<String> receiptPeers = new HashSet<>();

    private void receiptSoon(String peer) {
        if (receiptPeers.isEmpty()) worker.schedule(this::flushReceipts, 1500, TimeUnit.MILLISECONDS);
        receiptPeers.add(peer);
    }

    private void flushReceipts() {
        for (String peer : new ArrayList<>(receiptPeers)) {
            boolean read = peer.equals(openChat) && db.prefBool("readReceipts", true);
            sendReceipts(peer, read ? Store.READ : Store.DELIVERED);
        }
        receiptPeers.clear();
    }

    private void sendReceipts(String peer, int level) {
        List<String> ids = db.needReceipt(peer, level);
        if (ids.isEmpty()) return;
        try {
            Event e = event(peer, Proto.receipt(level, ids), System.currentTimeMillis(), Proto.KIND_RECEIPT);
            synchronized (seen) { seen.add(e.id); }
            String raw = e.toJson();
            db.addOutbox(e.id, "", raw);
            db.markReceipt(ids, level);
            broadcast(raw);
        } catch (Exception ignored) { }
    }

    /** Chat ist offen: als gelesen melden (oder nur zugestellt, wenn ausgeschaltet). */
    void chatSeen(String conv) {
        if (db.group(conv) != null) return;
        worker.execute(() -> sendReceipts(conv, db.prefBool("readReceipts", true) ? Store.READ : Store.DELIVERED));
    }

    private long lastTypingSent;

    void typing(String peer) {
        long now = System.currentTimeMillis();
        if (now - lastTypingSent < 3000 || !db.prefBool("typing", true) || db.group(peer) != null) return;
        lastTypingSent = now;
        worker.execute(() -> {
            try {
                Event e = event(peer, Proto.typing(), System.currentTimeMillis(), Proto.KIND_TYPING);
                synchronized (seen) { seen.add(e.id); }
                broadcast(e.toJson());
            } catch (Exception ignored) { }
        });
    }

    private long lastPresenceSent;

    private void maybePresence() {
        if (!uiActive || !db.prefBool("showOnline", true)) return;
        long now = System.currentTimeMillis();
        if (now - lastPresenceSent < 50000) return;
        lastPresenceSent = now;
        for (String c : db.contactKeys()) {
            try {
                Event e = event(c, Proto.presence(), now, Proto.KIND_PRESENCE);
                synchronized (seen) { seen.add(e.id); }
                broadcast(e.toJson());
            } catch (Exception ignored) { }
        }
    }

    // ================================================================== Senden

    private Event event(String peer, String plain, long now) { return event(peer, plain, now, 4); }

    private Event event(String peer, String plain, long now, int kind) {
        Event e = new Event();
        e.kind = kind;
        e.createdAt = now / 1000;
        e.tags = new String[][]{{"p", peer}};
        e.content = Nip44.encrypt(plain, convKey(peer));
        e.sign(keys.priv());
        return e;
    }

    void send(String peer, String text, String replyTo) {
        worker.execute(() -> {
            try {
                sendProfileIfNeeded(peer);
                long now = System.currentTimeMillis();
                Event e = event(peer, Proto.text(text, replyTo, myName()), now);
                String raw = e.toJson();
                Store.Msg m = new Store.Msg();
                m.id = e.id; m.peer = peer; m.outgoing = true; m.body = text; m.replyTo = replyTo;
                m.ts = now; m.status = Store.PENDING; m.raw = raw;
                synchronized (seen) { seen.add(e.id); }
                db.insert(m);
                changed(peer);
                broadcast(raw);
            } catch (Exception ignored) { }
        });
    }

    void sendGroup(String gid, String text, String replyTo) {
        worker.execute(() -> {
            try {
                Store.Group g = db.group(gid);
                if (g == null) return;
                byte[] gk = Event.unhex(g.gkey);
                if (gk == null) return;
                long now = System.currentTimeMillis();
                Event e = new Event();
                e.kind = Proto.KIND_GROUP;
                e.createdAt = now / 1000;
                e.tags = new String[][]{{"g", gid}};
                e.content = Nip44.encrypt(Proto.text(text, replyTo, myName()), gk);
                e.sign(keys.priv());
                Store.Msg m = new Store.Msg();
                m.id = e.id; m.peer = gid; m.sender = keys.pubHex(); m.outgoing = true;
                m.body = text; m.replyTo = replyTo; m.ts = now; m.status = Store.PENDING; m.raw = e.toJson();
                synchronized (seen) { seen.add(e.id); }
                db.insert(m);
                changed(gid);
                broadcast(m.raw);
            } catch (Exception ignored) { }
        });
    }

    /**
     * Anhang verschicken (Chat oder Gruppe). Die Datei wurde vorher mit {@link #prepareAttachment} vorbereitet.
     * data = fertige Bytes (≤ 1 MB), name = Dateiname, image = als Bild anzeigen.
     */
    void sendAttachment(String conv, byte[] data, String name, String mime, boolean image, String replyTo) {
        worker.execute(() -> {
            try {
                if (data.length == 0 || data.length > Proto.MAX_FILE) return;
                boolean group = db.group(conv) != null;
                byte[] gk = null;
                if (group) { gk = Event.unhex(db.group(conv).gkey); if (gk == null) return; }
                else sendProfileIfNeeded(conv);
                String fid = Proto.newFileId();
                String safe = Proto.safeName(name);
                File f = new File(db.mediaDir, fid + "_" + safe);
                Files.write(f.toPath(), data);
                String sha = Proto.sha(data);
                int n = Proto.chunkCount(data.length);
                long now = System.currentTimeMillis();
                Store.Msg m = new Store.Msg();
                m.id = fid; m.peer = conv; m.sender = group ? keys.pubHex() : null; m.outgoing = true;
                m.body = sha; m.kind = image ? Store.IMAGE : Store.FILE; m.replyTo = replyTo;
                m.ts = now; m.status = Store.PENDING; m.fileName = safe; m.fileMime = mime;
                m.fileSize = data.length; m.filePath = f.getAbsolutePath(); m.total = n;
                List<String> raws = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    String plain = Proto.chunk(fid, i, data, safe, mime, image, sha, replyTo, myName());
                    Event e;
                    if (group) {
                        e = new Event();
                        e.kind = Proto.KIND_GROUP;
                        e.createdAt = now / 1000;
                        e.tags = new String[][]{{"g", conv}};
                        e.content = Nip44.encrypt(plain, gk);
                        e.sign(keys.priv());
                    } else {
                        e = event(conv, plain, now);
                    }
                    synchronized (seen) { seen.add(e.id); }
                    String raw = e.toJson();
                    db.addOutbox(e.id, fid, raw);
                    raws.add(raw);
                }
                db.insert(m);
                changed(conv);
                for (String raw : raws) broadcast(raw);
            } catch (Exception ignored) { }
        });
    }

    void sendReaction(String conv, String msgId, String emoji) {
        String e0 = emoji == null ? "" : emoji;
        db.setReaction(msgId, keys.pubHex(), e0, System.currentTimeMillis());
        changed(conv);
        worker.execute(() -> {
            try {
                Store.Group g = db.group(conv);
                Event e;
                if (g != null) {
                    byte[] gk = Event.unhex(g.gkey);
                    if (gk == null) return;
                    e = new Event();
                    e.kind = Proto.KIND_GROUP;
                    e.createdAt = System.currentTimeMillis() / 1000;
                    e.tags = new String[][]{{"g", conv}};
                    e.content = Nip44.encrypt(Proto.react(msgId, e0), gk);
                    e.sign(keys.priv());
                } else {
                    e = event(conv, Proto.react(msgId, e0), System.currentTimeMillis());
                }
                synchronized (seen) { seen.add(e.id); }
                broadcast(e.toJson());
            } catch (Exception ignored) { }
        });
    }

    void deleteForEveryone(String peer, String msgId) {
        String path = db.deleteMessage(msgId);
        if (path != null) new File(path).delete();
        changed(peer);
        worker.execute(() -> {
            try {
                Event e = event(peer, "{\"nx\":1,\"t\":\"del\",\"id\":\"" + msgId + "\"}", System.currentTimeMillis());
                synchronized (seen) { seen.add(e.id); }
                broadcast(e.toJson());
            } catch (Exception ignored) { }
        });
    }

    void deleteForMe(String conv, String msgId) {
        String path = db.deleteMessage(msgId);
        if (path != null) new File(path).delete();
        changed(conv);
    }

    // ================================================================== Profil

    /** Eigenes Profil wurde am PC geändert: an alle Kontakte schicken. */
    void profileChanged() {
        db.setPref("profileTs", System.currentTimeMillis());
        worker.execute(() -> { for (String p : db.contactKeys()) try { sendProfileIfNeeded(p); } catch (Exception ignored) { } });
    }

    private void sendProfileIfNeeded(String peer) {
        long ts = db.prefLong("profileTs", 0);
        if (ts == 0) return; // am PC nie geändert: nichts senden (sonst würde ein Handy-Profil überschrieben)
        Store.Contact c = db.contact(peer);
        if (c == null || c.profileSent >= ts) return;
        byte[] jpg = null;
        String av = db.pref("avatar", null);
        if (av != null) try { jpg = Files.readAllBytes(new File(av).toPath()); } catch (Exception ignored) { }
        String plain = Proto.profile(ts, myName(), jpg, db.pref("bio", ""), db.pref("pronouns", ""), -1, -1, -1, "");
        Event e = event(peer, plain, System.currentTimeMillis());
        synchronized (seen) { seen.add(e.id); }
        String raw = e.toJson();
        db.addOutbox(e.id, "", raw);
        db.setProfileSent(peer, ts);
        broadcast(raw);
    }

    // ================================================================== Gruppen (Ausgang)

    String createGroup(String name, List<String> memberPubs) {
        String me = keys.pubHex();
        String gid = Event.hex(Secp.newPrivateKey());
        String gkey = Event.hex(Secp.newPrivateKey());
        List<String> members = new ArrayList<>();
        members.add(me);
        for (String p : memberPubs) if (!members.contains(p)) members.add(p);
        Set<String> admins = new HashSet<>();
        admins.add(me);
        db.upsertGroup(gid, name, gkey, me, System.currentTimeMillis());
        db.setMembers(gid, members, admins);
        resubscribeAll();
        worker.execute(() -> syncGroup(gid, members));
        changed(null);
        return gid;
    }

    void addGroupMembers(String gid, List<String> pubs) {
        worker.execute(() -> {
            String me = keys.pubHex();
            if (!db.isAdmin(gid, me)) return;
            List<String> members = db.memberPubs(gid);
            for (String p : pubs) if (!members.contains(p)) members.add(p);
            db.setMembers(gid, members, db.adminPubs(gid));
            syncGroup(gid, members);
            changed(gid);
        });
    }

    void renameGroup(String gid, String name) {
        Store.Group g = db.group(gid);
        if (g == null || !db.isAdmin(gid, keys.pubHex())) return;
        db.upsertGroup(gid, name, g.gkey, g.owner, g.created);
        worker.execute(() -> syncGroup(gid, db.memberPubs(gid)));
        changed(gid);
    }

    void leaveGroup(String gid) {
        db.deleteGroup(gid);
        resubscribeAll();
        changed(null);
    }

    private void syncGroup(String gid, List<String> recipients) {
        Store.Group g = db.group(gid);
        if (g == null) return;
        List<String> members = db.memberPubs(gid);
        Set<String> admins = db.adminPubs(gid);
        String avatarB64 = null;
        if (g.avatar != null) {
            try {
                byte[] jpg = Files.readAllBytes(new File(g.avatar).toPath());
                if (jpg.length > 0 && jpg.length < 400 * 1024) avatarB64 = java.util.Base64.getEncoder().encodeToString(jpg);
            } catch (Exception ignored) { }
        }
        String envelope = Proto.groupSync(gid, g.name, g.gkey, g.owner, members, admins, avatarB64);
        String me = keys.pubHex();
        for (String r : recipients) {
            if (r.equals(me)) continue;
            try {
                Event e = event(r, envelope, System.currentTimeMillis());
                synchronized (seen) { seen.add(e.id); }
                broadcast(e.toJson());
            } catch (Exception ignored) { }
        }
    }

    // ================================================================== Nutzerzählung („Ich bin ein Nutzer“)

    static final int DESKTOP_VERSION = Version.CODE;

    /** Wie am Handy: alle 3 Tage verschlüsselt beim Admin melden (mit Rechenaufwand, NIP-13). */
    void maybeSendHello() {
        if (!keys.has()) return;
        if (System.currentTimeMillis() - db.prefLong("helloTs", 0) < 3L * 24 * 3600 * 1000) return;
        Thread t = new Thread(() -> {
            try {
                byte[] priv = keys.priv();
                // Versionsnummern ab 100000 kennzeichnen die Windows-Version
                String payload = "{\"d\":\"" + device + "\",\"v\":" + (100000 + DESKTOP_VERSION) + "}";
                Event e = new Event();
                e.kind = Proto.KIND_HELLO;
                e.createdAt = System.currentTimeMillis() / 1000;
                e.content = Nip44.encrypt(payload, Nip44.conversationKey(priv, Event.unhex(ADMIN)));
                e.pubkey = Event.hex(Secp.publicKey(priv));
                long nonce = new java.security.SecureRandom().nextInt(1 << 30);
                String target = String.valueOf(Proto.HELLO_POW);
                while (true) {
                    e.tags = new String[][]{{"p", ADMIN}, {"nonce", String.valueOf(nonce), target}};
                    if (leadingZeroBits(e.computeId()) >= Proto.HELLO_POW) break;
                    nonce++;
                }
                e.sign(priv);
                broadcast(e.toJson());
                db.setPref("helloTs", System.currentTimeMillis());
            } catch (Throwable ignored) { }
        }, "hello-pow");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        t.start();
    }

    static int leadingZeroBits(byte[] h) {
        if (h == null) return 0;
        int n = 0;
        for (byte b : h) {
            if (b == 0) { n += 8; continue; }
            n += Integer.numberOfLeadingZeros(b & 0xff) - 24;
            break;
        }
        return n;
    }
}
