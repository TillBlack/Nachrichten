package de.nachrichten.app;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.nachrichten.crypto.Event;
import de.nachrichten.crypto.Nip44;
import de.nachrichten.crypto.Proto;
import de.nachrichten.crypto.Secp;
import de.nachrichten.net.Ws;

/**
 * Verbindung zu den Nostr-Relays: abonniert Nachrichten an mich (kind 4, Inhalt NIP-44-verschlüsselt),
 * veröffentlicht eigene Nachrichten, verbindet bei Abbruch neu.
 */
final class Net {

    interface Listener {
        /** peer == null: Liste allgemein neu laden. */
        void onChanged(String peer);
        void onStatus();
        /** Kontakt tippt gerade. */
        default void onTyping(String peer) { }
    }

    static final String[] DEFAULT_RELAYS = {
            "wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom"};

    private final App app;
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Conn> conns = new ConcurrentHashMap<>();
    private final Set<String> holders = new HashSet<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, byte[]> convKeys = new HashMap<>();
    private final Set<String> seen = new HashSet<>();
    private long lastSeenAtStart, startedAt;
    /** Gerade geöffneter Chat: dafür keine Benachrichtigung. */
    volatile String openChat;

    Net(App app) {
        this.app = app;
        HandlerThread t = new HandlerThread("net");
        t.start();
        worker = new Handler(t.getLooper());
        worker.postDelayed(this::watchdog, 25000);
    }

    void addListener(Listener l) { listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    private void changed(String peer) {
        main.post(() -> { for (Listener l : listeners) l.onChanged(peer); });
    }

    private void status() {
        main.post(() -> { for (Listener l : listeners) l.onStatus(); });
    }

    // ------------------------------------------------------------------ An/Aus

    /** Mit 'tag' (z. B. "ui" oder "service") wird die Verbindung gebraucht. */
    void acquire(String tag) {
        worker.post(() -> {
            holders.add(tag);
            syncConnections();
        });
    }

    void release(String tag) {
        worker.post(() -> {
            holders.remove(tag);
            // kurz warten, damit z. B. ein Bildschirmwechsel nicht neu verbindet
            worker.postDelayed(this::syncConnections, 8000);
        });
    }

    /** Relay-Liste hat sich geändert. */
    void relaysChanged() { worker.post(this::syncConnections); }

    private void syncConnections() {
        boolean want = !holders.isEmpty() && app.keys.has();
        List<String> urls = want ? app.relays() : new ArrayList<>();
        for (String u : new ArrayList<>(conns.keySet())) {
            if (!urls.contains(u)) {
                Conn c = conns.remove(u);
                c.stop();
            }
        }
        if (want && conns.isEmpty()) {
            lastSeenAtStart = app.prefs.getLong("lastSeen", 0);
            startedAt = System.currentTimeMillis() / 1000;
        }
        for (String u : urls) if (!conns.containsKey(u)) {
            Conn c = new Conn(u);
            conns.put(u, c);
            c.start();
        }
        status();
    }

    int connectedCount() {
        int n = 0;
        for (Conn c : conns.values()) if (c.open) n++;
        return n;
    }

    /** Status je Relay für die Einstellungen. */
    boolean isConnected(String url) {
        Conn c = conns.get(url);
        return c != null && c.open;
    }

    // ------------------------------------------------------------------ Verbindung je Relay

    private final class Conn implements Ws.Listener {
        final String url;
        volatile Ws ws;
        volatile boolean open, stopped;
        int failures;
        long lastPing;
        /** Sendet nacheinander mit kleiner Pause, damit Relays nicht drosseln. */
        final ExecutorService sender = Executors.newSingleThreadExecutor();

        Conn(String url) { this.url = url; }

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
                    w.run(); // blockiert bis zum Ende der Verbindung
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

        @Override
        public void onOpen(Ws w) {
            open = true;
            failures = 0;
            // läuft im Thread dieses Relays, bevor gelesen wird
            subscribe(w);
            for (String raw : app.db.pendingRaw()) enqueue("[\"EVENT\"," + raw + "]");
            status();
        }

        @Override
        public void onText(Ws w, String text) {
            worker.post(() -> handle(this, text));
        }

        @Override
        public void onClosed(Ws w, String reason) {
            open = false;
            status();
        }

        void pingIfIdle() {
            Ws w = ws;
            if (!open || w == null) return;
            long now = System.currentTimeMillis();
            if (now - lastPing > 25000) {
                lastPing = now;
                new Thread(w::ping).start();
            }
        }
    }

    private void watchdog() {
        for (Conn c : new ArrayList<>(conns.values())) c.pingIfIdle();
        worker.postDelayed(this::watchdog, 25000);
    }

    private void subscribe(Ws w) {
        String me = app.keys.pubHex();
        long since = lastSeenAtStart > 0 ? lastSeenAtStart - 2 * 3600 : 0;
        String sinceJson = since > 0 ? ",\"since\":" + since : "";
        String req = "[\"REQ\",\"dm\","
                + "{\"kinds\":[4," + Proto.KIND_RECEIPT + "],\"#p\":[\"" + me + "\"]" + sinceJson + ",\"limit\":1000},"
                + "{\"kinds\":[4],\"authors\":[\"" + me + "\"]" + sinceJson + ",\"limit\":1000},"
                + "{\"kinds\":[" + Proto.KIND_TYPING + "],\"#p\":[\"" + me + "\"],\"since\":" + (System.currentTimeMillis() / 1000 - 30) + "}]";
        w.send(req);
    }

    // ------------------------------------------------------------------ Eingang

    private void handle(Conn from, String text) {
        try {
            JSONArray a = new JSONArray(text);
            String type = a.optString(0);
            if (type.equals("EVENT") && a.length() >= 3) {
                handleEvent(a.getJSONObject(2));
            } else if (type.equals("OK") && a.length() >= 3) {
                if (a.optBoolean(2)) {
                    String peer = app.db.markSent(a.getString(1));
                    if (peer != null) changed(peer);
                }
            }
        } catch (Exception ignored) { }
    }

    private byte[] convKey(String peerHex) {
        byte[] k = convKeys.get(peerHex);
        if (k == null) {
            k = Nip44.conversationKey(app.keys.priv(), Event.unhex(peerHex));
            convKeys.put(peerHex, k);
        }
        return k;
    }

    private void handleEvent(JSONObject o) throws Exception {
        int kind = o.optInt("kind");
        if (kind != 4 && kind != Proto.KIND_RECEIPT && kind != Proto.KIND_TYPING) return;
        String id = o.getString("id");
        if (seen.contains(id)) return;
        seen.add(id);
        if (kind == 4 && app.db.hasMessage(id)) return;

        String me = app.keys.pubHex();
        String author = o.getString("pubkey");
        String p = null;
        JSONArray tags = o.optJSONArray("tags");
        if (tags != null) for (int i = 0; i < tags.length(); i++) {
            JSONArray t = tags.optJSONArray(i);
            if (t != null && t.length() >= 2 && "p".equals(t.optString(0))) { p = t.optString(1); break; }
        }
        if (p == null || !p.matches("[0-9a-f]{64}") || !author.matches("[0-9a-f]{64}")) return;
        boolean outgoing = author.equals(me);
        if (!outgoing && !p.equals(me)) return;
        String peer = outgoing ? p : author;
        if (peer.equals(me) || !Secp.validPublicKey(Event.unhex(peer))) return;

        // Die Entschlüsselung prüft per HMAC mit dem gemeinsamen Schlüssel, dass die Nachricht
        // wirklich von diesem Absender (oder von mir) stammt und nicht verändert wurde.
        String plain = Nip44.decrypt(o.optString("content"), convKey(peer));
        if (plain == null) return;

        long createdAt = o.optLong("created_at");
        if (kind == Proto.KIND_TYPING) {
            long now = System.currentTimeMillis() / 1000;
            if (!outgoing && Math.abs(now - createdAt) < 30 && app.db.contact(peer) != null) {
                main.post(() -> { for (Listener l : listeners) l.onTyping(peer); });
            }
            return;
        }
        if (kind == Proto.KIND_RECEIPT) {
            if (outgoing) return;
            try {
                JSONObject env = new JSONObject(plain);
                int s = env.optInt("s");
                JSONArray ids = env.optJSONArray("ids");
                if ((s != Db.DELIVERED && s != Db.READ) || ids == null) return;
                List<String> list = new ArrayList<>();
                for (int i = 0; i < ids.length() && i < 500; i++) {
                    String x = ids.optString(i);
                    if (x.matches("[0-9a-f]{32,64}")) list.add(x);
                }
                if (app.db.applyReceipt(peer, list, s)) changed(peer);
            } catch (Exception ignored) { }
            return;
        }
        if (app.db.contact(peer) == null) app.db.addContact(peer, "");
        updateLastSeen(createdAt);

        String body = plain, replyTo = null;
        if (plain.startsWith("{\"nx\"")) {
            JSONObject env;
            try { env = new JSONObject(plain); } catch (Exception e) { env = null; }
            if (env != null) {
                String t = env.optString("t");
                if (t.equals("file")) { handleChunk(peer, outgoing, createdAt, env); return; }
                if (t.equals("profile")) { if (!outgoing) handleProfile(peer, env); return; }
                if (t.equals("text")) {
                    body = env.optString("b");
                    replyTo = cleanId(env.optString("r", null));
                } else {
                    body = "[" + T.unsupported() + "]";
                }
            }
        }

        Db.Msg m = new Db.Msg();
        m.id = id;
        m.peer = peer;
        m.outgoing = outgoing;
        m.body = body;
        m.replyTo = replyTo;
        m.ts = createdAt * 1000L;
        m.status = Db.SENT;
        // bei der ersten Wiederherstellung keine Quittungen für alte Nachrichten verschicken
        m.receipt = fresh(createdAt) ? 0 : Db.READ;
        boolean isNew = app.db.insert(m);
        if (!isNew) return;
        changed(peer);
        notifyIfNew(peer, outgoing, createdAt, body);
        if (!outgoing) receiptSoon(peer);
    }

    private boolean fresh(long createdAt) {
        return lastSeenAtStart > 0 || createdAt > startedAt - 120;
    }

    // ------------------------------------------------------------------ Quittungen und Tippen

    private final Set<String> receiptPeers = new HashSet<>();

    /** Zugestellt-Quittungen sammeln und kurz danach gebündelt verschicken. */
    private void receiptSoon(String peer) {
        if (receiptPeers.isEmpty()) worker.postDelayed(this::flushReceipts, 1500);
        receiptPeers.add(peer);
    }

    private void flushReceipts() {
        for (String peer : new ArrayList<>(receiptPeers)) {
            boolean read = peer.equals(openChat) && app.prefs.getBoolean("readReceipts", true);
            sendReceipts(peer, read ? Db.READ : Db.DELIVERED);
        }
        receiptPeers.clear();
    }

    private void sendReceipts(String peer, int level) {
        List<String> ids = app.db.needReceipt(peer, level);
        if (ids.isEmpty()) return;
        try {
            Event e = event(peer, Proto.receipt(level, ids), System.currentTimeMillis(), Proto.KIND_RECEIPT);
            seen.add(e.id);
            String raw = e.toJson();
            app.db.addOutbox(e.id, "rcpt:" + peer, raw, 0);
            app.db.markReceipt(ids, level);
            broadcast(raw);
        } catch (Exception ignored) { }
    }

    /** Chat ist offen: Nachrichten als gelesen melden (oder nur zugestellt, wenn ausgeschaltet). */
    void chatSeen(String peer) {
        worker.post(() -> sendReceipts(peer, app.prefs.getBoolean("readReceipts", true) ? Db.READ : Db.DELIVERED));
    }

    private long lastTypingSent;

    /** Ich tippe gerade (höchstens alle 3 Sekunden, flüchtig). */
    void typing(String peer) {
        // (wird erst ab 2 Zeichen aufgerufen)
        long now = System.currentTimeMillis();
        if (now - lastTypingSent < 3000 || !app.prefs.getBoolean("typing", true)) return;
        lastTypingSent = now;
        worker.post(() -> {
            try {
                Event e = event(peer, Proto.typing(), System.currentTimeMillis(), Proto.KIND_TYPING);
                seen.add(e.id);
                broadcast(e.toJson());
            } catch (Exception ignored) { }
        });
    }

    private static String cleanId(String s) {
        return s != null && s.matches("[0-9a-f]{32,64}") ? s : null;
    }

    private void updateLastSeen(long createdAt) {
        long lastSeen = app.prefs.getLong("lastSeen", 0);
        if (createdAt > lastSeen && createdAt < System.currentTimeMillis() / 1000 + 600) {
            app.prefs.edit().putLong("lastSeen", createdAt).apply();
        }
    }

    private void notifyIfNew(String peer, boolean outgoing, long createdAt, String text) {
        // beim ersten Abruf (Wiederherstellung) nur wirklich neue Nachrichten melden
        boolean fresh = lastSeenAtStart > 0 || createdAt > startedAt - 120;
        if (!outgoing && fresh && !peer.equals(openChat)) {
            main.post(() -> Notifs.message(app, peer, text));
        }
    }

    /** Name und Bild eines Kontakts sind angekommen. */
    private void handleProfile(String peer, JSONObject env) {
        long ts = env.optLong("ts");
        Db.Contact c = app.db.contact(peer);
        if (c == null || ts <= c.avatarTs) return;
        String img = env.optString("img");
        String path = null;
        if (!img.isEmpty()) {
            try {
                byte[] jpg = java.util.Base64.getDecoder().decode(img);
                if (jpg.length > 60000) return;
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
                if (o.outWidth <= 0 || o.outWidth > 1024 || o.outHeight > 1024) return;
                java.io.File f = new java.io.File(app.mediaDir(), "avatar_" + peer.substring(0, 16) + "_" + ts + ".jpg");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) { out.write(jpg); }
                path = f.getAbsolutePath();
            } catch (Exception e) { return; }
        }
        if (c.avatar != null) new java.io.File(c.avatar).delete();
        String bio = env.optString("bio").trim(), pron = env.optString("pron").trim();
        if (bio.length() > 500) bio = bio.substring(0, 500);
        if (pron.length() > 40) pron = pron.substring(0, 40);
        app.db.setProfile(peer, path, ts, bio.isEmpty() ? null : bio, pron.isEmpty() ? null : pron);
        String name = env.optString("name").trim();
        if (name.length() > 40) name = name.substring(0, 40);
        if ((c.name == null || c.name.trim().isEmpty()) && !name.isEmpty()) app.db.rename(peer, name);
        changed(null);
    }

    /** Schickt mein Profil an einen Kontakt, wenn er die aktuelle Fassung noch nicht hat. */
    private void sendProfileIfNeeded(String peer) {
        long ts = app.prefs.getLong("profileTs", 0);
        if (ts == 0) return;
        Db.Contact c = app.db.contact(peer);
        if (c == null || c.profileSent >= ts) return;
        String plain;
        if (app.profileVisibleTo(peer)) {
            byte[] jpg = null;
            java.io.File f = app.myAvatar();
            if (f != null) try { jpg = java.nio.file.Files.readAllBytes(f.toPath()); } catch (Exception ignored) { }
            plain = Proto.profile(ts, app.myName(), jpg, app.prefs.getString("bio", ""), app.prefs.getString("pronouns", ""));
        } else {
            // privat und nicht ausgewählt: leeres Profil (löscht ein früher geschicktes)
            plain = Proto.profile(ts, "", null, "", "");
        }
        Event e = event(peer, plain, System.currentTimeMillis());
        seen.add(e.id);
        String raw = e.toJson();
        app.db.addOutbox(e.id, "profile:" + peer, raw, 0);
        app.db.setProfileSent(peer, ts);
        broadcast(raw);
    }

    /** Mein Profil hat sich geändert: an alle Kontakte schicken. */
    void profileChanged() {
        worker.post(() -> {
            for (String p : app.db.contactKeys()) {
                try { sendProfileIfNeeded(p); } catch (Exception ignored) { }
            }
        });
    }

    /** Ein Stück eines Anhangs ist angekommen. */
    private void handleChunk(String peer, boolean outgoing, long createdAt, JSONObject env) {
        String fid = env.optString("f");
        if (!fid.matches("[0-9a-f]{32}")) return;
        int i = env.optInt("i", -1), n = env.optInt("n", 0);
        if (n < 1 || n > Proto.chunkCount(Proto.MAX_FILE) || i < 0 || i >= n) return;
        if (app.db.isDeleted(fid)) return;
        Db.Msg m = app.db.message(fid);
        if (m != null && (!m.peer.equals(peer) || m.complete())) return; // eigener gesendeter Anhang oder fertig
        byte[] data;
        try { data = java.util.Base64.getDecoder().decode(env.optString("d")); } catch (Exception e) { return; }
        if (data.length > Proto.CHUNK) return;
        if (m == null) {
            m = new Db.Msg();
            m.id = fid;
            m.peer = peer;
            m.outgoing = outgoing;
            m.body = "";
            m.kind = Db.FILE;
            m.ts = createdAt * 1000L;
            m.status = Db.SENT;
            m.total = n;
            app.db.insert(m);
        }
        if (i == 0) {
            JSONObject meta = env.optJSONObject("m");
            if (meta == null) return;
            long size = meta.optLong("size", -1);
            String sha = meta.optString("sha");
            if (size < 0 || size > Proto.MAX_FILE || !sha.matches("[0-9a-f]{64}")) return;
            String mime = meta.optString("mime", "application/octet-stream");
            if (!mime.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) mime = "application/octet-stream";
            boolean img = meta.optBoolean("img") && mime.startsWith("image/");
            app.db.setFileMeta(fid, img ? Db.IMAGE : Db.FILE, Proto.safeName(meta.optString("name")), mime, size, n,
                    cleanId(env.optString("r", null)), sha);
        }
        int have = app.db.addChunk(fid, i, data);
        m = app.db.message(fid);
        if (m != null && have >= m.total && m.fileName != null && m.body != null && !m.body.isEmpty()) {
            byte[] all = Proto.assemble(app.db.chunks(fid, m.total), m.fileSize, m.body);
            if (all != null) {
                java.io.File f = new java.io.File(app.mediaDir(), fid + "_" + m.fileName);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                    out.write(all);
                    app.db.fileDone(fid, f.getAbsolutePath());
                    if (!outgoing && fresh(createdAt)) receiptSoon(peer);
                    else if (!outgoing) app.db.markReceipt(java.util.Collections.singletonList(fid), Db.READ);
                    notifyIfNew(peer, outgoing, createdAt, m.kind == Db.IMAGE ? T.photo() : T.fileLabel() + ": " + m.fileName);
                } catch (Exception ignored) { }
            }
        }
        changed(peer);
    }

    // ------------------------------------------------------------------ Senden

    private Event event(String peer, String plain, long now) { return event(peer, plain, now, 4); }

    private Event event(String peer, String plain, long now, int kind) {
        Event e = new Event();
        e.kind = kind;
        e.createdAt = now / 1000;
        e.tags = new String[][]{{"p", peer}};
        e.content = Nip44.encrypt(plain, convKey(peer));
        e.sign(app.keys.priv());
        return e;
    }

    private void broadcast(String raw) {
        String msg = "[\"EVENT\"," + raw + "]";
        for (Conn c : new ArrayList<>(conns.values())) if (c.open) c.enqueue(msg);
    }

    /** Verschlüsselt, signiert und verschickt eine Nachricht. */
    void send(String peer, String text, String replyTo) {
        worker.post(() -> {
            try {
                sendProfileIfNeeded(peer);
                long now = System.currentTimeMillis();
                Event e = event(peer, Proto.text(text, replyTo), now);
                String raw = e.toJson();
                Db.Msg m = new Db.Msg();
                m.id = e.id;
                m.peer = peer;
                m.outgoing = true;
                m.body = text;
                m.replyTo = replyTo;
                m.ts = now;
                m.status = Db.PENDING;
                m.raw = raw;
                seen.add(e.id);
                app.db.insert(m);
                changed(peer);
                broadcast(raw);
            } catch (Exception ignored) { }
        });
    }

    /** Verschickt einen Anhang in Stücken. Die Datei liegt schon unter path. */
    void sendFile(String peer, String path, String name, String mime, boolean image, String replyTo) {
        worker.post(() -> {
            try {
                java.io.File f = new java.io.File(path);
                byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
                if (data.length == 0 || data.length > Proto.MAX_FILE) return;
                sendProfileIfNeeded(peer);
                String fid = f.getName().substring(0, 32);
                String sha = Proto.sha(data);
                int n = Proto.chunkCount(data.length);
                long now = System.currentTimeMillis();
                Db.Msg m = new Db.Msg();
                m.id = fid;
                m.peer = peer;
                m.outgoing = true;
                m.body = sha;
                m.kind = image ? Db.IMAGE : Db.FILE;
                m.replyTo = replyTo;
                m.ts = now;
                m.status = Db.PENDING;
                m.fileName = name;
                m.fileMime = mime;
                m.fileSize = data.length;
                m.filePath = path;
                m.have = n;
                m.total = n;
                List<String> raws = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    Event e = event(peer, Proto.chunk(fid, i, data, name, mime, image, sha, replyTo), now);
                    seen.add(e.id);
                    String raw = e.toJson();
                    app.db.addOutbox(e.id, fid, raw, i);
                    raws.add(raw);
                }
                app.db.insert(m);
                changed(peer);
                for (String raw : raws) broadcast(raw);
            } catch (Exception ignored) { }
        });
    }
}
