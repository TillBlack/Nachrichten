package de.nachrichten.crypto;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Inhalt der verschlüsselten Nachrichten.
 * Einfacher Text ohne Antwort bleibt reiner Text (kompatibel zu Version 1).
 * Sonst JSON mit "nx":1:
 *   Text:  {"nx":1,"t":"text","b":"…","r":"<id>"}
 *   Datei: {"nx":1,"t":"file","f":"<fid>","i":0,"n":5,"d":"<base64>", nur bei i=0: "m":{...},"r":"<id>"}
 */
public final class Proto {

    /** Rohdaten je Stück; ergibt verschlüsselt rund 38 KB pro Relay-Ereignis. */
    public static final int CHUNK = 20000;
    /** Höchstgröße eines Anhangs. */
    public static final int MAX_FILE = 1024 * 1024;

    private static final SecureRandom RNG = new SecureRandom();

    public static String newFileId() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        return Event.hex(b);
    }

    public static String text(String body, String replyTo) {
        if (replyTo == null && !body.startsWith("{\"nx\"")) return body;
        StringBuilder sb = new StringBuilder("{\"nx\":1,\"t\":\"text\",\"b\":");
        Event.str(sb, body);
        if (replyTo != null) { sb.append(",\"r\":"); Event.str(sb, replyTo); }
        return sb.append('}').toString();
    }

    /** Profil: Name und kleines Bild (JPEG, base64). img leer = Bild entfernt. */
    public static String profile(long ts, String name, byte[] jpeg, String bio, String pronouns, int accB, int accF, int accR, String tags) {
        StringBuilder sb = new StringBuilder("{\"nx\":1,\"t\":\"profile\",\"ts\":").append(ts).append(",\"name\":");
        Event.str(sb, name == null ? "" : name);
        sb.append(",\"bio\":");
        Event.str(sb, bio == null ? "" : bio);
        sb.append(",\"pron\":");
        Event.str(sb, pronouns == null ? "" : pronouns);
        sb.append(",\"tags\":");
        Event.str(sb, tags == null ? "" : tags);
        sb.append(",\"img\":");
        Event.str(sb, jpeg == null ? "" : Base64.getEncoder().encodeToString(jpeg));
        sb.append(",\"ab\":").append(accB).append(",\"af\":").append(accF).append(",\"ar\":").append(accR);
        return sb.append('}').toString();
    }

    /** Eigene Ereignis-Arten: Quittungen (werden gespeichert) und Tippen (flüchtig, Relays speichern es nicht). */
    public static final int KIND_RECEIPT = 4445, KIND_TYPING = 25044, KIND_PRESENCE = 20002;
    /** Anonymer Verbesserungsvorschlag an den Entwickler (von einem Wegwerf-Schlüssel signiert). */
    public static final int KIND_SUGGEST = 4446;
    /** Gruppennachricht (mit dem gemeinsamen Gruppenschlüssel verschlüsselt, Tag "g"). */
    public static final int KIND_GROUP = 4447;
    /** Umfrage (öffentlich, nur vom Admin-Schlüssel signiert). */
    public static final int KIND_POLL = 4449;
    /** Stimme zu einer Umfrage (öffentlich, vom Wähler signiert, Tag "e" = Umfrage-ID). */
    public static final int KIND_VOTE = 4450;
    /** Admin-Befehl (öffentlich, nur vom Admin-Schlüssel signiert): Bann, Namen/Bio überschreiben, Verwarnung. */
    public static final int KIND_ADMIN = 4451;

    /** Steuernachricht (über 1:1 verschickt): voller Stand einer Gruppe – Name, Schlüssel, Mitglieder, Admins. */
    public static String groupSync(String gid, String name, String gkey, String owner, java.util.List<String> members, java.util.Set<String> admins) {
        StringBuilder sb = new StringBuilder("{\"nx\":1,\"t\":\"group\",\"op\":\"sync\",\"gid\":");
        Event.str(sb, gid);
        sb.append(",\"name\":"); Event.str(sb, name);
        sb.append(",\"gkey\":"); Event.str(sb, gkey);
        sb.append(",\"owner\":"); Event.str(sb, owner);
        sb.append(",\"members\":[");
        int i = 0; for (String m : members) { if (i++ > 0) sb.append(','); Event.str(sb, m); }
        sb.append("],\"admins\":[");
        i = 0; for (String a : admins) { if (i++ > 0) sb.append(','); Event.str(sb, a); }
        sb.append("]}");
        return sb.toString();
    }

    public static String receipt(int status, java.util.List<String> ids) {
        StringBuilder sb = new StringBuilder("{\"nx\":1,\"t\":\"receipt\",\"s\":").append(status).append(",\"ids\":[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            Event.str(sb, ids.get(i));
        }
        return sb.append("]}").toString();
    }

    public static String typing() { return "{\"nx\":1,\"t\":\"typing\"}"; }

    public static String presence() { return "{\"nx\":1,\"t\":\"presence\"}"; }

    public static int chunkCount(int size) { return Math.max(1, (size + CHUNK - 1) / CHUNK); }

    /** Ein Stück eines Anhangs. meta nur bei i == 0. */
    public static String chunk(String fid, int i, byte[] data, String name, String mime, boolean image, String sha, String replyTo) {
        int n = chunkCount(data.length);
        int from = i * CHUNK, len = Math.min(CHUNK, data.length - from);
        byte[] part = new byte[Math.max(0, len)];
        if (len > 0) System.arraycopy(data, from, part, 0, len);
        StringBuilder sb = new StringBuilder("{\"nx\":1,\"t\":\"file\",\"f\":");
        Event.str(sb, fid);
        sb.append(",\"i\":").append(i).append(",\"n\":").append(n).append(",\"d\":");
        Event.str(sb, Base64.getEncoder().encodeToString(part));
        if (i == 0) {
            sb.append(",\"m\":{\"name\":");
            Event.str(sb, name);
            sb.append(",\"mime\":");
            Event.str(sb, mime);
            sb.append(",\"size\":").append(data.length);
            sb.append(",\"img\":").append(image);
            sb.append(",\"sha\":");
            Event.str(sb, sha);
            sb.append('}');
            if (replyTo != null) { sb.append(",\"r\":"); Event.str(sb, replyTo); }
        }
        return sb.append('}').toString();
    }

    public static String sha(byte[] data) { return Event.hex(Secp.sha256(data)); }

    /** Setzt die Stücke zusammen und prüft Größe und Prüfsumme. null bei Fehlern. */
    public static byte[] assemble(byte[][] parts, long size, String sha) {
        int total = 0;
        for (byte[] p : parts) {
            if (p == null) return null;
            total += p.length;
        }
        if (total != size || total > MAX_FILE) return null;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, pos, p.length); pos += p.length; }
        if (sha != null && !sha.equals(sha(out))) return null;
        return out;
    }

    /** Dateiname ohne Pfad und problematische Zeichen. */
    public static String safeName(String name) {
        if (name == null) name = "";
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.isEmpty()) name = "file";
        if (name.length() > 80) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
            name = name.substring(0, 80 - ext.length()) + ext;
        }
        return name;
    }
}
