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
        /** Kontakt ist gerade online geworden. */
        default void onPresence(String peer) { }
        /** Ein neuer anonymer Vorschlag ist eingegangen (nur beim Entwickler). */
        default void onSuggestion() { }
    }

    static final String[] DEFAULT_RELAYS = {
            "wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom"};

    private final App app;
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Conn> conns = new ConcurrentHashMap<>();
    private final Set<String> holders = new HashSet<>();
    private final Map<String, Long> presence = new java.util.concurrent.ConcurrentHashMap<>();
    private long lastPresenceSent;
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
            if ("ui".equals(tag)) lastPresenceSent = 0; // beim Öffnen gleich Präsenz senden
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
            worker.postDelayed(() -> publishDirectory(false), 4000);
            worker.postDelayed(() -> maybePresence(), 3000);
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
        maybePresence();
        worker.postDelayed(this::watchdog, 25000);
    }

    /** Alle ~50 s meinen Online-Status an meine Kontakte senden, solange die App offen ist. */
    private void maybePresence() {
        if (!holders.contains("ui") || !app.prefs.getBoolean("showOnline", true)) return;
        long now = System.currentTimeMillis();
        if (now - lastPresenceSent < 50000) return;
        lastPresenceSent = now;
        for (String c : app.db.contactKeys()) {
            try {
                Event e = event(c, Proto.presence(), now, Proto.KIND_PRESENCE);
                seen.add(e.id);
                broadcast(e.toJson());
            } catch (Exception ignored) { }
        }
    }

    /** Ist dieser Kontakt gerade online (in den letzten 90 s gesehen)? */
    boolean isOnline(String pub) {
        Long t = presence.get(pub);
        return t != null && System.currentTimeMillis() - t < 90000;
    }

    private void subscribe(Ws w) {
        String me = app.keys.pubHex();
        long since = lastSeenAtStart > 0 ? lastSeenAtStart - 2 * 3600 : 0;
        String sinceJson = since > 0 ? ",\"since\":" + since : "";
        String suggest = app.isDev()
                ? ",{\"kinds\":[" + Proto.KIND_SUGGEST + "],\"#p\":[\"" + App.SUGGEST_PUBKEY + "\"],\"limit\":1000}"
                : "";
        String polls = "";
        if (!App.SUGGEST_PUBKEY.isEmpty()) {
            polls = ",{\"kinds\":[" + Proto.KIND_POLL + "],\"authors\":[\"" + App.SUGGEST_PUBKEY + "\"],\"limit\":200}";
            java.util.List<String> pollIds = app.db.pollIds();
            if (!pollIds.isEmpty()) {
                StringBuilder pb = new StringBuilder(",{\"kinds\":[" + Proto.KIND_VOTE + "],\"#e\":[");
                for (int i = 0; i < pollIds.size(); i++) { if (i > 0) pb.append(','); pb.append('"').append(pollIds.get(i)).append('"'); }
                pb.append("],\"limit\":5000}");
                polls += pb.toString();
            }
        }
        String groups = "";
        java.util.List<String> gids = app.db.gids();
        if (!gids.isEmpty()) {
            StringBuilder gb = new StringBuilder(",{\"kinds\":[" + Proto.KIND_GROUP + "],\"#g\":[");
            for (int i = 0; i < gids.size(); i++) { if (i > 0) gb.append(','); gb.append('"').append(gids.get(i)).append('"'); }
            gb.append("]" + sinceJson + ",\"limit\":1000}");
            groups = gb.toString();
        }
        String req = "[\"REQ\",\"dm\","
                + "{\"kinds\":[4," + Proto.KIND_RECEIPT + "],\"#p\":[\"" + me + "\"]" + sinceJson + ",\"limit\":1000},"
                + "{\"kinds\":[4],\"authors\":[\"" + me + "\"]" + sinceJson + ",\"limit\":1000},"
                + "{\"kinds\":[" + Proto.KIND_TYPING + "," + Proto.KIND_PRESENCE + "],\"#p\":[\"" + me + "\"],\"since\":" + (System.currentTimeMillis() / 1000 - 120) + "}"
                + suggest + groups + polls + "]";
        w.send(req);
    }

    // ------------------------------------------------------------------ Eingang

    private void handle(Conn from, String text) {
        try {
            JSONArray a = new JSONArray(text);
            String type = a.optString(0);
            if (type.equals("EVENT") && a.length() >= 3 && "dir".equals(a.optString(1))) {
                handleDirectory(a.getJSONObject(2));
            } else if (type.equals("EOSE") && "dir".equals(a.optString(1))) {
                main.post(() -> { DirListener l = dirListener; if (l != null) l.done(); });
            } else if (type.equals("EVENT") && a.length() >= 3) {
                handleEvent(a.getJSONObject(2));
            } else if (type.equals("OK") && a.length() >= 3) {
                if (a.optBoolean(2)) {
                    String peer = app.db.markSent(a.getString(1));
                    if (peer != null) changed(peer);
                }
            }
        } catch (Exception ignored) { }
    }

    private static int clampAcc(int v) { return (v >= 0 && v < Accessories.COUNT) ? v : -1; }

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
        if (kind != 4 && kind != Proto.KIND_RECEIPT && kind != Proto.KIND_TYPING
                && kind != Proto.KIND_PRESENCE && kind != Proto.KIND_SUGGEST && kind != Proto.KIND_GROUP
                && kind != Proto.KIND_POLL && kind != Proto.KIND_VOTE) return;
        String id = o.getString("id");
        if (seen.contains(id)) return;
        seen.add(id);
        if ((kind == 4 || kind == Proto.KIND_GROUP) && app.db.hasMessage(id)) return;

        String me = app.keys.pubHex();
        String author = o.getString("pubkey");

        if (kind == Proto.KIND_GROUP) { handleGroupMessage(o, id, author, me); return; }
        if (kind == Proto.KIND_POLL) { handlePoll(o, author); return; }
        if (kind == Proto.KIND_VOTE) { handleVote(o, author); return; }
        String p = null;
        JSONArray tags = o.optJSONArray("tags");
        if (tags != null) for (int i = 0; i < tags.length(); i++) {
            JSONArray t = tags.optJSONArray(i);
            if (t != null && t.length() >= 2 && "p".equals(t.optString(0))) { p = t.optString(1); break; }
        }
        if (p == null || !p.matches("[0-9a-f]{64}") || !author.matches("[0-9a-f]{64}")) return;

        if (kind == Proto.KIND_SUGGEST) {
            // Anonymer Vorschlag ans getrennte Postfach – mit dessen privatem Schlüssel entschlüsseln.
            if (!app.isDev() || !App.SUGGEST_PUBKEY.equals(p)) return;
            byte[] sp = app.sugPriv();
            if (sp == null) return;
            String sPlain = Nip44.decrypt(o.optString("content"), Nip44.conversationKey(sp, Event.unhex(author)));
            if (sPlain == null) return;
            if (app.db.addSuggestion(id, sPlain, o.optLong("created_at"))) {
                main.post(() -> { for (Listener l : listeners) l.onSuggestion(); });
            }
            return;
        }

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
        if (kind == Proto.KIND_PRESENCE) {
            long now = System.currentTimeMillis() / 1000;
            if (!outgoing && Math.abs(now - createdAt) < 120 && app.db.contact(peer) != null) {
                presence.put(peer, System.currentTimeMillis());
                main.post(() -> { for (Listener l : listeners) l.onPresence(peer); });
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
        if (plain.startsWith("{\"nx\"")) {
            try {
                JSONObject env0 = new JSONObject(plain);
                if ("group".equals(env0.optString("t"))) { if (!outgoing) handleGroupControl(author, env0); return; }
            } catch (Exception ignored) { }
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
                if (t.equals("del")) {
                    if (!outgoing) {
                        String did = env.optString("id", null);
                        if (did != null) {
                            String path = app.db.deleteIncoming(did);
                            if (path != null) new java.io.File(path).delete();
                            changed(peer);
                        }
                    }
                    return;
                }
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
        String tags = env.optString("tags").trim();
        if (bio.length() > 500) bio = bio.substring(0, 500);
        if (pron.length() > 40) pron = pron.substring(0, 40);
        if (tags.length() > 120) tags = tags.substring(0, 120);
        int ab = clampAcc(env.optInt("ab", -1)), af = clampAcc(env.optInt("af", -1)), ar = clampAcc(env.optInt("ar", -1));
        app.db.setProfile(peer, path, ts, bio.isEmpty() ? null : bio, pron.isEmpty() ? null : pron, ab, af, ar, tags.isEmpty() ? null : tags);
        String name = env.optString("name").trim();
        if (name.length() > 40) name = name.substring(0, 40);
        if ((c.name == null || c.name.trim().isEmpty()) && !name.isEmpty()) app.db.rename(peer, name);
        changed(null);
    }

    /** Nachricht für alle löschen: lokal entfernen und dem Kontakt ein Lösch-Signal schicken. */
    void deleteForEveryone(String peer, String msgId) {
        String path = app.db.deleteMessage(msgId);
        if (path != null) new java.io.File(path).delete();
        changed(peer);
        worker.post(() -> {
            try {
                Event e = event(peer, "{\"nx\":1,\"t\":\"del\",\"id\":\"" + msgId + "\"}", System.currentTimeMillis());
                seen.add(e.id);
                broadcast(e.toJson());
            } catch (Exception ignored) { }
        });
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
            plain = Proto.profile(ts, app.myName(), jpg, app.prefs.getString("bio", ""), app.prefs.getString("pronouns", ""), app.accBehind(), app.accFront(), app.accFrame(), app.prefs.getString("tags", ""));
        } else {
            // privat und nicht ausgewählt: leeres Profil (löscht ein früher geschicktes)
            plain = Proto.profile(ts, "", null, "", "", -1, -1, -1, "");
        }
        Event e = event(peer, plain, System.currentTimeMillis());
        seen.add(e.id);
        String raw = e.toJson();
        app.db.addOutbox(e.id, "profile:" + peer, raw, 0);
        app.db.setProfileSent(peer, ts);
        broadcast(raw);
    }

    /** Mein Profil hat sich geändert: an alle Kontakte schicken. */
    // ------------------------------------------------------------------ öffentliche Nutzerliste

    static final int KIND_DIR = 30078;
    static final String DIR_D = "nachrichten-profile", DIR_T = "nachrichten-dir";

    static final class DirEntry {
        String pub, name, bio, pronouns, tags;
        byte[] thumb;
        long seen;
        boolean showStatus = true;
        /** listed = öffentlich in "Alle Nutzer"; linked = für gegenseitig verknüpfte Kontakte sichtbar. */
        boolean listed = true, linked = false;
        int accBehind = -1, accFront = -1, accFrame = -1;
    }

    interface DirListener {
        void entry(DirEntry e);
        void done();
    }

    private volatile DirListener dirListener;
    private long lastDirPublish;
    /** Zwischenspeicher der Nutzerliste; neue Abfrage höchstens alle 3 Minuten. */
    private final Map<String, DirEntry> dirCache = new HashMap<>();
    private long dirCacheAt;
    private volatile int lastDirCount = -1;

    /** Bekannte Nutzerzahl (-1 = noch nicht geladen). */
    int directoryCount() { return lastDirCount; }

    /** Zähler im Hintergrund frisch halten (für die Chatliste). */
    void warmDirectory(Runnable onDone) {
        loadDirectory(new DirListener() {
            public void entry(DirEntry e) { }
            public void done() { if (onDone != null) onDone.run(); }
        });
    }

    /** Liste abfragen (alle, die in den letzten 30 Tagen aktiv waren). Höchstens alle 3 Minuten eine neue Abfrage. */
    void loadDirectory(DirListener l) {
        dirListener = l;
        worker.post(() -> {
            boolean fresh = !dirCache.isEmpty() && System.currentTimeMillis() - dirCacheAt < 3 * 60 * 1000L;
            if (fresh) {
                for (DirEntry d : new ArrayList<>(dirCache.values())) {
                    DirEntry dd = d;
                    main.post(() -> { DirListener x = dirListener; if (x != null) x.entry(dd); });
                }
                main.post(() -> { DirListener x = dirListener; if (x != null) x.done(); });
                return;
            }
            dirCacheAt = System.currentTimeMillis();
            String req = "[\"REQ\",\"dir\",{\"kinds\":[" + KIND_DIR + "],\"#t\":[\"" + DIR_T + "\"],\"since\":"
                    + (System.currentTimeMillis() / 1000 - 30L * 86400) + ",\"limit\":500}]";
            for (Conn c : new ArrayList<>(conns.values())) if (c.open) c.enqueue(req);
        });
    }

    void closeDirectory() {
        dirListener = null;
        for (Conn c : new ArrayList<>(conns.values())) if (c.open) c.enqueue("[\"CLOSE\",\"dir\"]");
    }

    private void handleDirectory(JSONObject o) {
        try {
            if (o.optInt("kind") != KIND_DIR) return;
            Event e = new Event();
            e.id = o.getString("id");
            e.pubkey = o.getString("pubkey");
            e.createdAt = o.getLong("created_at");
            e.kind = KIND_DIR;
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
            if (!DIR_D.equals(e.tag("d")) || e.content.isEmpty()) return; // leer = zurückgezogen
            if (e.pubkey.equals(app.keys.pubHex())) return;
            if (!e.verify()) return; // öffentlich und unverschlüsselt: Signatur selbst prüfen
            JSONObject c = new JSONObject(e.content);
            DirEntry d = new DirEntry();
            d.pub = e.pubkey;
            d.seen = e.createdAt;
            d.name = trim(c.optString("name"), 40);
            d.bio = trim(c.optString("bio"), 500);
            d.pronouns = trim(c.optString("pron"), 40);
            d.tags = trim(c.optString("tags"), 120);
            d.showStatus = c.optInt("st", 1) == 1;
            d.listed = c.optInt("pub", 1) == 1;
            d.linked = c.optInt("con", 0) == 1;
            d.accBehind = clampAcc(c.optInt("ab", -1));
            d.accFront = clampAcc(c.optInt("af", -1));
            d.accFrame = clampAcc(c.optInt("ar", -1));
            String img = c.optString("img");
            if (!img.isEmpty() && img.length() < 20000) {
                try { d.thumb = java.util.Base64.getDecoder().decode(img); } catch (Exception ignored) { }
            }
            if (d.name.isEmpty()) return;
            DirEntry old = dirCache.get(d.pub);
            if (old == null || old.seen < d.seen) { dirCache.put(d.pub, d); lastDirCount = dirCache.size(); }
            main.post(() -> { DirListener l = dirListener; if (l != null) l.entry(d); });
        } catch (Exception ignored) { }
    }

    private static String trim(String s, int max) {
        s = s == null ? "" : s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Eigenen Eintrag veröffentlichen (öffentlich) oder zurückziehen (privat / nicht zugestimmt). */
    void publishDirectory(boolean force) {
        worker.post(() -> {
            long now = System.currentTimeMillis();
            if (!force && now - lastDirPublish < 5 * 60 * 1000L) return;
            boolean showOnline = app.prefs.getBoolean("showOnline", true);
            // ohne Online-Status nur bei Änderungen veröffentlichen (sonst verrät die Zeit die Aktivität)
            if (!force && !showOnline && app.prefs.getBoolean("dirPublished", false)) return;
            if (connectedCount() == 0) return;
            boolean pubList = app.prefs.getBoolean("dirConsent", false) && !app.prefs.getBoolean("profilePrivate", false);
            boolean visible = pubList && !app.myName().isEmpty();
            boolean wasPublished = app.prefs.getBoolean("dirPublished", false);
            if (!visible && !wasPublished) return;
            lastDirPublish = now;
            try {
                String content = "";
                if (visible) {
                    StringBuilder sb = new StringBuilder("{\"name\":");
                    Event.str(sb, app.myName());
                    sb.append(",\"pron\":");
                    Event.str(sb, app.prefs.getString("pronouns", ""));
                    sb.append(",\"bio\":");
                    Event.str(sb, app.prefs.getString("bio", ""));
                    sb.append(",\"tags\":");
                    Event.str(sb, app.prefs.getString("tags", ""));
                    sb.append(",\"img\":");
                    Event.str(sb, thumbBase64());
                    sb.append(",\"st\":").append(showOnline ? 1 : 0);
                    sb.append(",\"pub\":").append(pubList ? 1 : 0);
                    sb.append(",\"ab\":").append(app.accBehind()).append(",\"af\":").append(app.accFront()).append(",\"ar\":").append(app.accFrame());
                    content = sb.append('}').toString();
                }
                Event e = new Event();
                e.kind = KIND_DIR;
                e.createdAt = now / 1000;
                e.tags = new String[][]{{"d", DIR_D}, {"t", DIR_T}};
                e.content = content;
                e.sign(app.keys.priv());
                seen.add(e.id);
                broadcast(e.toJson());
                app.prefs.edit().putBoolean("dirPublished", visible).apply();
            } catch (Exception ignored) { }
        });
    }

    /** Kleines Vorschaubild (96 px) des eigenen Profilbilds als base64. */
    private String thumbBase64() {
        java.io.File f = app.myAvatar();
        if (f == null) return "";
        try {
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b == null) return "";
            android.graphics.Bitmap s = android.graphics.Bitmap.createScaledBitmap(b, 96, 96, true);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            s.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out);
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Throwable t) {
            return "";
        }
    }

    void profileChanged() {
        publishDirectory(true);
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

    // ------------------------------------------------------------------ Gruppen

    void resubscribeAll() {
        for (Conn c : new ArrayList<>(conns.values())) {
            Ws w = c.ws;
            if (c.open && w != null) subscribe(w);
        }
    }

    /** Eingehende Gruppennachricht (mit dem Gruppenschlüssel entschlüsseln). */
    private void handleGroupMessage(JSONObject o, String id, String author, String me) {
        try {
            String gid = null;
            JSONArray tags = o.optJSONArray("tags");
            if (tags != null) for (int i = 0; i < tags.length(); i++) {
                JSONArray t = tags.optJSONArray(i);
                if (t != null && t.length() >= 2 && "g".equals(t.optString(0))) { gid = t.optString(1); break; }
            }
            if (gid == null || !author.matches("[0-9a-f]{64}")) return;
            Db.Group g = app.db.group(gid);
            if (g == null || !app.db.isMember(gid, author)) return;
            byte[] gk = Event.unhex(g.gkey);
            if (gk == null) return;
            String plain = Nip44.decrypt(o.optString("content"), gk);
            if (plain == null) return;
            boolean outgoing = author.equals(me);
            String body = plain, replyTo = null;
            if (plain.startsWith("{\"nx\"")) {
                try {
                    JSONObject env = new JSONObject(plain);
                    String t = env.optString("t");
                    if (t.equals("text")) { body = env.optString("b"); replyTo = cleanId(env.optString("r", null)); }
                    else body = "[" + T.unsupported() + "]";
                } catch (Exception ignored) { }
            }
            long createdAt = o.optLong("created_at");
            Db.Msg m = new Db.Msg();
            m.id = id; m.peer = gid; m.sender = author; m.outgoing = outgoing;
            m.body = body; m.replyTo = replyTo; m.ts = createdAt * 1000L; m.status = Db.SENT;
            if (!app.db.insert(m)) return;
            changed(gid);
            if (!outgoing) notifyGroup(gid, g.name, body);
        } catch (Exception ignored) { }
    }

    /** Gruppen-Steuernachricht (Stand einer Gruppe) anwenden. */
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
            java.util.List<String> members = new ArrayList<>();
            for (int i = 0; i < mem.length(); i++) { String x = mem.optString(i); if (x.matches("[0-9a-f]{64}")) members.add(x); }
            java.util.Set<String> admins = new HashSet<>();
            JSONArray adm = env.optJSONArray("admins");
            if (adm != null) for (int i = 0; i < adm.length(); i++) { String x = adm.optString(i); if (x.matches("[0-9a-f]{64}")) admins.add(x); }
            String me = app.keys.pubHex();
            Db.Group existing = app.db.group(gid);
            if (!members.contains(me)) {
                if (existing != null) { app.db.deleteGroup(gid); resubscribeAll(); changed(gid); notifyListChanged(); }
                return;
            }
            // Vertrauen: neue Gruppe nur per Einladung eines Kontakts; Änderungen nur von einem bisherigen Admin.
            if (existing == null) {
                if (!author.equals(me) && app.db.contact(author) == null) return;
            } else {
                if (!author.equals(me) && !app.db.isAdmin(gid, author)) return;
            }
            admins.add(owner);
            app.db.upsertGroup(gid, name, gkey, owner, existing == null ? System.currentTimeMillis() : existing.created);
            app.db.setMembers(gid, members, admins);
            resubscribeAll();
            changed(gid);
            notifyListChanged();
        } catch (Exception ignored) { }
    }

    private void notifyListChanged() { main.post(() -> { for (Listener l : listeners) l.onChanged(null); }); }

    private void notifyGroup(String gid, String name, String body) {
        main.post(() -> { for (Listener l : listeners) l.onChanged(gid); });
    }

    /** Erstellt eine Gruppe (ich bin Ersteller und Admin) und verteilt den Stand an die Mitglieder. */
    String createGroup(String name, java.util.List<String> memberPubs) {
        String me = app.keys.pubHex();
        String gid = Event.hex(Secp.newPrivateKey());
        String gkey = Event.hex(Secp.newPrivateKey());
        java.util.List<String> members = new ArrayList<>();
        members.add(me);
        for (String p : memberPubs) if (!members.contains(p)) members.add(p);
        java.util.Set<String> admins = new HashSet<>();
        admins.add(me);
        app.db.upsertGroup(gid, name, gkey, me, System.currentTimeMillis());
        app.db.setMembers(gid, members, admins);
        resubscribeAll();
        syncGroup(gid, members);
        return gid;
    }

    /** Verteilt den aktuellen Stand einer Gruppe 1:1-verschlüsselt an die angegebenen Mitglieder. */
    private void syncGroup(String gid, java.util.List<String> recipients) {
        Db.Group g = app.db.group(gid);
        if (g == null) return;
        java.util.List<String> members = app.db.memberPubs(gid);
        java.util.Set<String> admins = new HashSet<>();
        for (Db.Member m : app.db.members(gid)) if (m.admin) admins.add(m.pub);
        String envelope = Proto.groupSync(gid, g.name, g.gkey, g.owner, members, admins);
        String me = app.keys.pubHex();
        for (String r : recipients) {
            if (r.equals(me)) continue;
            final String rr = r;
            worker.post(() -> {
                try {
                    Event e = event(rr, envelope, System.currentTimeMillis());
                    seen.add(e.id);
                    broadcast(e.toJson());
                } catch (Exception ignored) { }
            });
        }
    }

    /** Mitglieder hinzufügen (nur Admins). */
    void addGroupMembers(String gid, java.util.List<String> pubs) {
        String me = app.keys.pubHex();
        if (!app.db.isAdmin(gid, me)) return;
        java.util.List<String> members = app.db.memberPubs(gid);
        for (String p : pubs) if (!members.contains(p)) members.add(p);
        java.util.Set<String> admins = new HashSet<>();
        for (Db.Member m : app.db.members(gid)) if (m.admin) admins.add(m.pub);
        app.db.setMembers(gid, members, admins);
        syncGroup(gid, members);
        changed(gid);
    }

    /** Admin-Rolle setzen (nur der Ersteller/Eigentümer). */
    void setGroupAdmin(String gid, String pub, boolean admin) {
        Db.Group g = app.db.group(gid);
        String me = app.keys.pubHex();
        if (g == null || !g.owner.equals(me)) return;
        java.util.List<String> members = app.db.memberPubs(gid);
        java.util.Set<String> admins = new HashSet<>();
        for (Db.Member m : app.db.members(gid)) if (m.admin) admins.add(m.pub);
        if (admin) admins.add(pub); else admins.remove(pub);
        admins.add(g.owner);
        app.db.setMembers(gid, members, admins);
        syncGroup(gid, members);
        changed(gid);
    }

    /** Gruppe verlassen (nur lokal; andere behalten die Gruppe). */
    void leaveGroup(String gid) {
        app.db.deleteGroup(gid);
        resubscribeAll();
        changed(gid);
        notifyListChanged();
    }

    /** Verschlüsselt und verschickt eine Gruppennachricht. */
    void sendGroup(String gid, String text, String replyTo) {
        worker.post(() -> {
            try {
                Db.Group g = app.db.group(gid);
                if (g == null) return;
                byte[] gk = Event.unhex(g.gkey);
                if (gk == null) return;
                long now = System.currentTimeMillis();
                Event e = new Event();
                e.kind = Proto.KIND_GROUP;
                e.createdAt = now / 1000;
                e.tags = new String[][]{{"g", gid}};
                e.content = Nip44.encrypt(Proto.text(text, replyTo), gk);
                e.sign(app.keys.priv());
                Db.Msg m = new Db.Msg();
                m.id = e.id; m.peer = gid; m.sender = app.keys.pubHex(); m.outgoing = true;
                m.body = text; m.replyTo = replyTo; m.ts = now; m.status = Db.PENDING;
                seen.add(e.id);
                app.db.insert(m);
                app.countMessage();
                changed(gid);
                broadcast(e.toJson());
            } catch (Exception ignored) { }
        });
    }

    // ------------------------------------------------------------------ Umfragen

    private void handlePoll(JSONObject o, String author) {
        try {
            if (!App.SUGGEST_PUBKEY.equals(author)) return; // nur Admin-Umfragen zählen
            JSONObject j = new JSONObject(o.optString("content"));
            String id = j.optString("id", null);
            if (id == null || !id.matches("[0-9a-f]{64}")) return;
            String title = j.optString("title", "");
            boolean del = j.optInt("del", 0) == 1;
            JSONArray opts = j.optJSONArray("opts");
            StringBuilder arr = new StringBuilder("[");
            if (opts != null) for (int i = 0; i < opts.length(); i++) {
                if (i > 0) arr.append(',');
                Event.str(arr, opts.optString(i));
            }
            arr.append(']');
            app.db.upsertPoll(id, title, arr.toString(), o.optLong("created_at") * 1000L, del);
            resubscribeAll();
            main.post(() -> { for (Listener l : listeners) l.onChanged(null); });
        } catch (Exception ignored) { }
    }

    private void handleVote(JSONObject o, String author) {
        try {
            JSONObject j = new JSONObject(o.optString("content"));
            String pid = j.optString("poll", null);
            if (pid == null || app.db.poll(pid) == null) return;
            int opt = j.optInt("opt", -1);
            if (opt < 0) return;
            app.db.upsertVote(pid, author, opt, o.optLong("created_at") * 1000L);
            main.post(() -> { for (Listener l : listeners) l.onChanged(null); });
        } catch (Exception ignored) { }
    }

    /** Umfrage erstellen oder ändern (nur Admin). del=true löscht sie. */
    void savePoll(String id, String title, java.util.List<String> options, boolean del) {
        if (!app.isDev()) return;
        worker.post(() -> {
            try {
                long ts = System.currentTimeMillis() / 1000;
                StringBuilder arr = new StringBuilder("[");
                for (int i = 0; i < options.size(); i++) { if (i > 0) arr.append(','); Event.str(arr, options.get(i)); }
                arr.append(']');
                StringBuilder c = new StringBuilder("{\"id\":\"").append(id).append("\",\"title\":");
                Event.str(c, title);
                c.append(",\"opts\":").append(arr).append(",\"del\":").append(del ? 1 : 0).append("}");
                Event e = new Event();
                e.kind = Proto.KIND_POLL;
                e.createdAt = ts;
                e.tags = new String[][]{{"d", id}};
                e.content = c.toString();
                e.sign(app.sugPriv());
                seen.add(e.id);
                broadcast(e.toJson());
                app.db.upsertPoll(id, title, arr.toString(), ts * 1000L, del);
                resubscribeAll();
                main.post(() -> { for (Listener l : listeners) l.onChanged(null); });
            } catch (Exception ignored) { }
        });
    }

    /** Für eine Umfrage abstimmen (eine Stimme pro Umfrage, änderbar). */
    void vote(String pollId, int option) {
        worker.post(() -> {
            try {
                long ts = System.currentTimeMillis() / 1000;
                Event e = new Event();
                e.kind = Proto.KIND_VOTE;
                e.createdAt = ts;
                e.tags = new String[][]{{"e", pollId}};
                e.content = "{\"poll\":\"" + pollId + "\",\"opt\":" + option + ",\"ts\":" + ts + "}";
                e.sign(app.keys.priv());
                seen.add(e.id);
                broadcast(e.toJson());
                app.db.upsertVote(pollId, app.keys.pubHex(), option, ts * 1000L);
                main.post(() -> { for (Listener l : listeners) l.onChanged(null); });
            } catch (Exception ignored) { }
        });
    }

    /**
     * Schickt einen anonymen Verbesserungsvorschlag an den Entwickler.
     * Signiert mit einem frischen Wegwerf-Schlüssel, damit der Absender nicht erkennbar ist
     * und kein Kontakt entsteht. Verschlüsselt (NIP-44) an den Entwickler-Schlüssel.
     */
    void sendSuggestion(String text) {
        final String t = text == null ? "" : text.trim();
        if (t.isEmpty() || App.SUGGEST_PUBKEY.isEmpty()) return;
        worker.post(() -> {
            try {
                byte[] target = Event.unhex(App.SUGGEST_PUBKEY);
                byte[] eph = Secp.newPrivateKey();
                byte[] ck = Nip44.conversationKey(eph, target);
                Event e = new Event();
                e.kind = Proto.KIND_SUGGEST;
                e.createdAt = System.currentTimeMillis() / 1000;
                e.tags = new String[][]{{"p", App.SUGGEST_PUBKEY}};
                e.content = Nip44.encrypt(t, ck);
                e.sign(eph);
                broadcast(e.toJson());
            } catch (Exception ignored) { }
        });
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
                app.countMessage();
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
                app.countMessage();
                changed(peer);
                for (String raw : raws) broadcast(raw);
            } catch (Exception ignored) { }
        });
    }
}
