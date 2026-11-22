package de.nachrichten.app;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import de.nachrichten.crypto.Bech32;
import de.nachrichten.crypto.Event;
import de.nachrichten.crypto.Secp;

/** Der eigene private Schlüssel, verschlüsselt mit einem Schlüssel aus dem Android-Keystore. */
final class Keys {

    private static final String ALIAS = "nachrichten_wrap";
    private final SharedPreferences prefs;
    private byte[] priv, pub;

    Keys(SharedPreferences prefs) {
        this.prefs = prefs;
        String stored = prefs.getString("key", null);
        if (stored != null) {
            byte[] k = unwrap(stored);
            if (Secp.validPrivateKey(k)) setLoaded(k);
        }
    }

    boolean has() { return priv != null; }
    byte[] priv() { return priv; }
    byte[] pub() { return pub; }
    String pubHex() { return Event.hex(pub); }
    String npub() { return Bech32.encode("npub", pub); }
    String nsec() { return Bech32.encode("nsec", priv); }

    private void setLoaded(byte[] k) {
        priv = k;
        pub = Secp.publicKey(k);
    }

    void create() { store(Secp.newPrivateKey()); }

    /** Wiederherstellen aus nsec1… oder 64 Hex-Zeichen. */
    boolean restore(String code) {
        code = code.trim();
        byte[] k = Bech32.decode("nsec", code);
        if (k == null && code.matches("[0-9a-fA-F]{64}")) k = Event.unhex(code);
        if (!Secp.validPrivateKey(k)) return false;
        store(k);
        return true;
    }

    private void store(byte[] k) {
        setLoaded(k);
        prefs.edit().putString("key", wrap(k)).commit();
    }

    // ------------------------------------------------------------------ Keystore

    private static SecretKey wrapKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    private static String wrap(byte[] k) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, wrapKey());
            byte[] iv = c.getIV(), ct = c.doFinal(k);
            return "ks:" + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ct);
        } catch (Exception e) {
            // Keystore nicht nutzbar: nur im privaten App-Speicher ablegen
            return "raw:" + Event.hex(k);
        }
    }

    private static byte[] unwrap(String s) {
        try {
            if (s.startsWith("raw:")) return Event.unhex(s.substring(4));
            String[] p = s.split(":");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, wrapKey(), new GCMParameterSpec(128, Base64.getDecoder().decode(p[1])));
            return c.doFinal(Base64.getDecoder().decode(p[2]));
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Kontakt-Codes

    /** Liest einen Kontakt-Code: npub1…, nostr:npub1…?name=…, oder 64 Hex-Zeichen. Gibt {pubHex, name} oder null. */
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
        byte[] pub = Bech32.decode("npub", s);
        if (pub == null && s.matches("[0-9a-fA-F]{64}")) pub = Event.unhex(s);
        if (!Secp.validPublicKey(pub)) return null;
        if (name.length() > 40) name = name.substring(0, 40);
        return new String[]{Event.hex(pub), name.trim()};
    }

    /** Inhalt des eigenen QR-Codes. */
    String qrContent(String myName) {
        String s = "nostr:" + npub();
        if (myName != null && !myName.trim().isEmpty()) {
            try { s += "?name=" + java.net.URLEncoder.encode(myName.trim(), "UTF-8").replace("+", "%20"); } catch (Exception ignored) { }
        }
        return s;
    }
}
