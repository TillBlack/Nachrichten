package de.nachrichten.crypto;

import java.nio.charset.StandardCharsets;

/** Nostr-Ereignis (NIP-01): Aufbau, ID, Signatur, JSON. */
public final class Event {

    public String id, pubkey, content, sig;
    public long createdAt;
    public int kind;
    /** Tags als Liste von String-Arrays. */
    public String[][] tags = new String[0][];

    public static String hex(byte[] b) {
        char[] h = "0123456789abcdef".toCharArray();
        char[] o = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) { o[i * 2] = h[(b[i] >> 4) & 15]; o[i * 2 + 1] = h[b[i] & 15]; }
        return new String(o);
    }

    public static byte[] unhex(String s) {
        if (s == null || s.length() % 2 != 0) return null;
        byte[] o = new byte[s.length() / 2];
        for (int i = 0; i < o.length; i++) {
            int a = Character.digit(s.charAt(i * 2), 16), b = Character.digit(s.charAt(i * 2 + 1), 16);
            if (a < 0 || b < 0) return null;
            o[i] = (byte) ((a << 4) | b);
        }
        return o;
    }

    /** JSON-String nach NIP-01-Regeln (keine Escapes außer den nötigen). */
    public static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private void tagsJson(StringBuilder sb) {
        sb.append('[');
        for (int i = 0; i < tags.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('[');
            for (int j = 0; j < tags[i].length; j++) {
                if (j > 0) sb.append(',');
                str(sb, tags[i][j]);
            }
            sb.append(']');
        }
        sb.append(']');
    }

    public byte[] computeId() {
        StringBuilder sb = new StringBuilder("[0,");
        str(sb, pubkey);
        sb.append(',').append(createdAt).append(',').append(kind).append(',');
        tagsJson(sb);
        sb.append(',');
        str(sb, content);
        sb.append(']');
        return Secp.sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public void sign(byte[] priv) {
        pubkey = hex(Secp.publicKey(priv));
        byte[] idb = computeId();
        id = hex(idb);
        sig = hex(Secp.sign(idb, priv));
    }

    public boolean verify() {
        byte[] idb = computeId();
        if (!hex(idb).equals(id)) return false;
        byte[] pub = unhex(pubkey), s = unhex(sig);
        return pub != null && s != null && Secp.verify(idb, pub, s);
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"id\":");
        str(sb, id);
        sb.append(",\"pubkey\":");
        str(sb, pubkey);
        sb.append(",\"created_at\":").append(createdAt);
        sb.append(",\"kind\":").append(kind);
        sb.append(",\"tags\":");
        tagsJson(sb);
        sb.append(",\"content\":");
        str(sb, content);
        sb.append(",\"sig\":");
        str(sb, sig);
        sb.append('}');
        return sb.toString();
    }

    /** Erster Wert eines Tags, z. B. tag("p"). */
    public String tag(String name) {
        for (String[] t : tags) if (t.length >= 2 && t[0].equals(name)) return t[1];
        return null;
    }
}
