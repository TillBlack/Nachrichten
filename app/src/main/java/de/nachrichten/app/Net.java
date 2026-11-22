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

import de.nachrichten.crypto.Event;
import de.nachrichten.crypto.Nip44;
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
    private long lastSeenAtStart;
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
        if (want && conns.isEmpty()) lastSeenAtStart = app.prefs.getLong("lastSeen", 0);
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

        Conn(String url) { this.url = url; }

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
            Ws w = ws;
            if (w != null) new Thread(w::close).start();
        }

        @Override
        public void onOpen(Ws w) {
            open = true;
            failures = 0;
            // läuft im Thread dieses Relays, bevor gelesen wird
            subscribe(w);
            for (String raw : app.db.pendingRaw()) w.send("[\"EVENT\"," + raw + "]");
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
                + "{\"kinds\":[4],\"#p\":[\"" + me + "\"]" + sinceJson + ",\"limit\":1000},"
                + "{\"kinds\":[4],\"authors\":[\"" + me + "\"]" + sinceJson + ",\"limit\":1000}]";
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
        if (o.optInt("kind") != 4) return;
        String id = o.getString("id");
        if (seen.contains(id)) return;
        seen.add(id);
        if (app.db.hasMessage(id)) return;

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
        String body = Nip44.decrypt(o.optString("content"), convKey(peer));
        if (body == null) return;

        long createdAt = o.optLong("created_at");
        if (app.db.contact(peer) == null) app.db.addContact(peer, "");
        Db.Msg m = new Db.Msg();
        m.id = id;
        m.peer = peer;
        m.outgoing = outgoing;
        m.body = body;
        m.ts = createdAt * 1000L;
        m.status = Db.SENT;
        boolean isNew = app.db.insert(m);
        if (!isNew) return;

        long lastSeen = app.prefs.getLong("lastSeen", 0);
        if (createdAt > lastSeen && createdAt < System.currentTimeMillis() / 1000 + 600) {
            app.prefs.edit().putLong("lastSeen", createdAt).apply();
        }
        changed(peer);
        if (!outgoing && lastSeenAtStart > 0 && !peer.equals(openChat)) {
            main.post(() -> Notifs.message(app, peer, body));
        }
    }

    // ------------------------------------------------------------------ Senden

    /** Verschlüsselt, signiert und verschickt eine Nachricht. */
    void send(String peer, String text) {
        worker.post(() -> {
            try {
                long now = System.currentTimeMillis();
                Event e = new Event();
                e.kind = 4;
                e.createdAt = now / 1000;
                e.tags = new String[][]{{"p", peer}};
                e.content = Nip44.encrypt(text, convKey(peer));
                e.sign(app.keys.priv());
                String raw = e.toJson();
                Db.Msg m = new Db.Msg();
                m.id = e.id;
                m.peer = peer;
                m.outgoing = true;
                m.body = text;
                m.ts = now;
                m.status = Db.PENDING;
                m.raw = raw;
                seen.add(e.id);
                app.db.insert(m);
                changed(peer);
                for (Conn c : new ArrayList<>(conns.values())) {
                    Ws w = c.ws;
                    if (c.open && w != null) {
                        String msg = "[\"EVENT\"," + raw + "]";
                        new Thread(() -> w.send(msg)).start();
                    }
                }
            } catch (Exception ignored) { }
        });
    }
}
