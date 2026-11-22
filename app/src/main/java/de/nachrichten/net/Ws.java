package de.nachrichten.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Kleiner WebSocket-Client (RFC 6455) ohne Bibliotheken. Läuft blockierend im Aufrufer-Thread. */
public final class Ws {

    public interface Listener {
        void onOpen(Ws ws);
        void onText(Ws ws, String text);
        void onClosed(Ws ws, String reason);
    }

    private static final SecureRandom RNG = new SecureRandom();
    private static final int MAX_MESSAGE = 4 * 1024 * 1024;

    private final String url;
    private final Listener listener;
    private Socket socket;
    private OutputStream out;
    private volatile boolean closed;
    private volatile long lastReceive;

    public Ws(String url, Listener listener) {
        this.url = url;
        this.listener = listener;
    }

    /** Verbindet und liest, bis die Verbindung endet. Ruft am Ende immer onClosed auf. */
    public void run() {
        String reason = "closed";
        try {
            connect();
            listener.onOpen(this);
            readLoop();
        } catch (Exception e) {
            reason = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        } finally {
            closeQuietly();
            listener.onClosed(this, reason);
        }
    }

    private void connect() throws IOException {
        URI u = URI.create(url);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        boolean tls = scheme.equals("wss");
        if (!tls && !scheme.equals("ws")) throw new IOException("bad scheme");
        String host = u.getHost();
        if (host == null) throw new IOException("bad host");
        int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
        String path = (u.getRawPath() == null || u.getRawPath().isEmpty()) ? "/" : u.getRawPath();
        if (u.getRawQuery() != null) path += "?" + u.getRawQuery();

        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 15000);
        if (tls) {
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(s, host, port, true);
            ssl.setSoTimeout(15000);
            ssl.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.getSession())) {
                ssl.close();
                throw new IOException("certificate does not match " + host);
            }
            s = ssl;
        }
        socket = s;
        s.setSoTimeout(15000);
        s.setTcpNoDelay(true);
        out = s.getOutputStream();
        InputStream in = s.getInputStream();

        byte[] keyBytes = new byte[16];
        RNG.nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String hostHeader = host + ((tls && port == 443) || (!tls && port == 80) ? "" : ":" + port);
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "User-Agent: Nachrichten\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String status = readLine(in);
        if (status == null || !status.matches("HTTP/1\\.[01] 101.*")) throw new IOException("handshake: " + status);
        String accept = null;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Sec-WebSocket-Accept")) accept = line.substring(c + 1).trim();
        }
        String expect;
        try {
            expect = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) { throw new IOException(e); }
        if (!expect.equals(accept)) throw new IOException("bad accept");
        s.setSoTimeout(75000); // Pings kommen alle 25 s; ohne Antwort gilt die Verbindung als tot
        lastReceive = System.currentTimeMillis();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("header too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    private static void readFully(InputStream in, byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) throw new IOException("eof");
            off += n;
        }
    }

    private void readLoop() throws IOException {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        int msgOp = -1;
        byte[] head = new byte[8];
        while (!closed) {
            readFully(in, head, 2);
            lastReceive = System.currentTimeMillis();
            boolean fin = (head[0] & 0x80) != 0;
            int op = head[0] & 0x0f;
            boolean masked = (head[1] & 0x80) != 0;
            long len = head[1] & 0x7f;
            if (len == 126) { readFully(in, head, 2); len = ((head[0] & 0xff) << 8) | (head[1] & 0xff); }
            else if (len == 127) {
                readFully(in, head, 8);
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | (head[i] & 0xff);
            }
            if (len < 0 || len > MAX_MESSAGE) throw new IOException("frame too large");
            byte[] mask = new byte[4];
            if (masked) readFully(in, mask, 4);
            byte[] payload = new byte[(int) len];
            readFully(in, payload, (int) len);
            if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

            switch (op) {
                case 0x8: // Close
                    sendFrame(0x8, new byte[0]);
                    return;
                case 0x9: // Ping
                    sendFrame(0xA, payload);
                    break;
                case 0xA: // Pong
                    break;
                case 0x0: case 0x1: case 0x2:
                    if (op != 0) { msg.reset(); msgOp = op; }
                    if (msg.size() + payload.length > MAX_MESSAGE) throw new IOException("message too large");
                    msg.write(payload, 0, payload.length);
                    if (fin) {
                        if (msgOp == 0x1) listener.onText(this, new String(msg.toByteArray(), StandardCharsets.UTF_8));
                        msg.reset();
                        msgOp = -1;
                    }
                    break;
                default:
                    throw new IOException("bad opcode " + op);
            }
        }
    }

    private synchronized void sendFrame(int op, byte[] payload) throws IOException {
        if (out == null) throw new IOException("not connected");
        int len = payload.length;
        ByteArrayOutputStream f = new ByteArrayOutputStream(len + 14);
        f.write(0x80 | op);
        if (len < 126) f.write(0x80 | len);
        else if (len < 65536) { f.write(0x80 | 126); f.write(len >> 8); f.write(len); }
        else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write((int) (((long) len >> (8 * i)) & 0xff));
        }
        byte[] mask = new byte[4];
        RNG.nextBytes(mask);
        f.write(mask, 0, 4);
        for (int i = 0; i < len; i++) f.write(payload[i] ^ mask[i & 3]);
        out.write(f.toByteArray());
        out.flush();
    }

    /** Sendet Text. Gibt false zurück, wenn die Verbindung weg ist. */
    public boolean send(String text) {
        try {
            sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            closeQuietly();
            return false;
        }
    }

    public boolean ping() {
        try {
            sendFrame(0x9, new byte[0]);
            return true;
        } catch (IOException e) {
            closeQuietly();
            return false;
        }
    }

    public long lastReceive() { return lastReceive; }

    public void close() {
        if (closed) return;
        try { sendFrame(0x8, new byte[]{0x03, (byte) 0xe8}); } catch (Exception ignored) { }
        closeQuietly();
    }

    private void closeQuietly() {
        closed = true;
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
    }

    public String url() { return url; }
}
