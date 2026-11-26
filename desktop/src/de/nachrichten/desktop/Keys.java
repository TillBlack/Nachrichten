package de.nachrichten.desktop;

import de.nachrichten.crypto.Bech32;
import de.nachrichten.crypto.Event;
import de.nachrichten.crypto.Secp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Eigener Schlüssel der Windows-Version.
 * Unter Windows wird der geheime Schlüssel mit DPAPI verschlüsselt abgelegt (an das Windows-Benutzerkonto gebunden –
 * ein anderer Benutzer oder ein anderer PC kann die Datei nicht entschlüsseln). Das Geheimnis wird dabei
 * über die Standardeingabe an PowerShell übergeben, nie in der Befehlszeile.
 */
final class Keys {

    private final File file;
    private byte[] priv, pub;

    Keys(File dir) {
        this.file = new File(dir, "schluessel.dat");
        load();
    }

    boolean has() { return priv != null; }
    byte[] priv() { return priv; }
    String pubHex() { return pub == null ? null : Event.hex(pub); }
    String npub() { return Bech32.encode("npub", pub); }
    String nsec() { return Bech32.encode("nsec", priv); }

    void create() { store(Secp.newPrivateKey()); }

    /** Bestehendes Konto übernehmen: nsec1… oder 64 Hex-Zeichen. */
    boolean restore(String code) {
        if (code == null) return false;
        code = code.trim();
        byte[] k = Bech32.decode("nsec", code);
        if (k == null && code.matches("[0-9a-fA-F]{64}")) k = Event.unhex(code);
        if (k == null || !Secp.validPrivateKey(k)) return false;
        store(k);
        return true;
    }

    private void store(byte[] k) {
        priv = k;
        pub = Secp.publicKey(k);
        try {
            String hex = Event.hex(k);
            String content;
            byte[] prot = isWindows() ? dpapi(true, hex.getBytes(StandardCharsets.UTF_8)) : null;
            content = prot != null ? "dpapi:" + Base64.getEncoder().encodeToString(prot) : "plain:" + hex;
            Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    private void load() {
        if (!file.exists()) return;
        try {
            String s = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            String hex = null;
            if (s.startsWith("dpapi:")) {
                byte[] plain = dpapi(false, Base64.getDecoder().decode(s.substring(6)));
                if (plain != null) hex = new String(plain, StandardCharsets.UTF_8).trim();
            } else if (s.startsWith("plain:")) {
                hex = s.substring(6);
                // nachträglich schützen, sobald es geht
                if (isWindows()) { byte[] k = Event.unhex(hex); if (k != null && Secp.validPrivateKey(k)) { priv = k; pub = Secp.publicKey(k); store(k); return; } }
            }
            byte[] k = hex == null ? null : Event.unhex(hex);
            if (k != null && Secp.validPrivateKey(k)) { priv = k; pub = Secp.publicKey(k); }
        } catch (Exception ignored) { }
    }

    /** Konto auf diesem PC entfernen (Schlüsseldatei löschen). */
    void forget() {
        priv = null;
        pub = null;
        file.delete();
    }

    static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }

    /** Windows-DPAPI über PowerShell (CurrentUser). null bei Fehler. */
    private static byte[] dpapi(boolean protect, byte[] data) {
        try {
            String b64 = Base64.getEncoder().encodeToString(data);
            String script = "Add-Type -AssemblyName System.Security\n"
                    + "$b=[Convert]::FromBase64String([Console]::In.ReadLine())\n"
                    + "$r=[Security.Cryptography.ProtectedData]::" + (protect ? "Protect" : "Unprotect")
                    + "($b,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)\n"
                    + "[Console]::Out.Write([Convert]::ToBase64String($r))\n";
            String enc = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", enc)
                    .redirectErrorStream(false).start();
            try (OutputStream o = p.getOutputStream()) { o.write((b64 + "\n").getBytes(StandardCharsets.US_ASCII)); }
            byte[] out = readAll(p.getInputStream());
            if (!p.waitFor(20, TimeUnit.SECONDS) || p.exitValue() != 0) return null;
            String r = new String(out, StandardCharsets.US_ASCII).trim();
            return r.isEmpty() ? null : Base64.getDecoder().decode(r);
        } catch (Exception e) {
            return null;
        }
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    /** Kontakt-Code lesen: npub1…, nostr:npub1…?name=…, oder 64 Hex-Zeichen. {pubHex, name} oder null. */
    static String[] parseContact(String code) {
        if (code == null) return null;
        String s = code.trim();
        String name = "";
        if (s.toLowerCase().startsWith("nostr:")) s = s.substring(6);
        int q = s.indexOf('?');
        if (q >= 0) {
            String query = s.substring(q + 1);
            s = s.substring(0, q);
            for (String part : query.split("&")) {
                if (part.startsWith("name=")) {
                    try { name = java.net.URLDecoder.decode(part.substring(5), "UTF-8"); } catch (Exception ignored) { }
                }
            }
        }
        s = s.trim();
        byte[] p = Bech32.decode("npub", s);
        if (p == null && s.matches("[0-9a-fA-F]{64}")) p = Event.unhex(s);
        if (p == null || !Secp.validPublicKey(p)) return null;
        if (name.length() > 40) name = name.substring(0, 40);
        return new String[]{Event.hex(p), name.trim()};
    }

    /** Eigener Kontakt-Code (wie im QR-Code auf dem Handy). */
    String contactCode(String myName) {
        String s = "nostr:" + npub();
        if (myName != null && !myName.trim().isEmpty()) {
            try { s += "?name=" + java.net.URLEncoder.encode(myName.trim(), "UTF-8").replace("+", "%20"); } catch (Exception ignored) { }
        }
        return s;
    }

    /**
     * Geräte-Kennung für Sperren und Nutzerzählung: Hash der Windows-MachineGuid (bleibt bei Neuinstallation
     * und neuem Konto gleich, ändert sich bei Neuinstallation von Windows). Nur der Hash verlässt den PC.
     */
    static String deviceHash(Store store) {
        String id = null;
        if (isWindows()) {
            try {
                Process p = new ProcessBuilder("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid").start();
                String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
                p.waitFor(10, TimeUnit.SECONDS);
                for (String line : out.split("\\R")) {
                    if (line.contains("MachineGuid")) {
                        String[] parts = line.trim().split("\\s+");
                        id = parts[parts.length - 1];
                    }
                }
            } catch (Exception ignored) { }
        }
        if (id == null || id.isEmpty()) {
            id = store.pref("devFallback", null);
            if (id == null) {
                id = Event.hex(Secp.newPrivateKey()).substring(0, 32);
                store.setPref("devFallback", id);
            }
        }
        return Event.hex(Secp.sha256(("nachrichten-device-win-v1:" + id).getBytes(StandardCharsets.UTF_8)));
    }
}
