package de.nachrichten.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** NIP-44 Version 2: ChaCha20 + HMAC-SHA256, Schlüssel per HKDF aus ECDH. */
public final class Nip44 {

    private static final SecureRandom RNG = new SecureRandom();

    public static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] p : parts) m.update(p);
            return m.doFinal();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static byte[] conversationKey(byte[] priv, byte[] pub) {
        return hmac("nip44-v2".getBytes(StandardCharsets.UTF_8), Secp.sharedX(priv, pub));
    }

    static byte[] hkdfExpand(byte[] prk, byte[] info, int len) {
        byte[] out = new byte[len], t = new byte[0];
        int pos = 0;
        for (int i = 1; pos < len; i++) {
            t = hmac(prk, t, info, new byte[]{(byte) i});
            int n = Math.min(t.length, len - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
        }
        return out;
    }

    static int paddedLen(int len) {
        if (len <= 32) return 32;
        int nextPower = 1 << (32 - Integer.numberOfLeadingZeros(len - 1));
        int chunk = nextPower <= 256 ? 32 : nextPower / 8;
        return chunk * ((len - 1) / chunk + 1);
    }

    public static String encrypt(String plaintext, byte[] convKey) {
        byte[] nonce = new byte[32];
        RNG.nextBytes(nonce);
        return encrypt(plaintext, convKey, nonce);
    }

    public static String encrypt(String plaintext, byte[] convKey, byte[] nonce) {
        byte[] pt = plaintext.getBytes(StandardCharsets.UTF_8);
        if (pt.length < 1 || pt.length > 65535) throw new IllegalArgumentException("message length");
        byte[] keys = hkdfExpand(convKey, nonce, 76);
        byte[] ck = slice(keys, 0, 32), cn = slice(keys, 32, 12), hk = slice(keys, 44, 32);
        byte[] padded = new byte[2 + paddedLen(pt.length)];
        padded[0] = (byte) (pt.length >> 8);
        padded[1] = (byte) pt.length;
        System.arraycopy(pt, 0, padded, 2, pt.length);
        byte[] ct = chacha20(ck, cn, padded);
        byte[] mac = hmac(hk, nonce, ct);
        byte[] out = new byte[1 + 32 + ct.length + 32];
        out[0] = 2;
        System.arraycopy(nonce, 0, out, 1, 32);
        System.arraycopy(ct, 0, out, 33, ct.length);
        System.arraycopy(mac, 0, out, 33 + ct.length, 32);
        return Base64.getEncoder().encodeToString(out);
    }

    /** Gibt null zurück, wenn die Nachricht nicht passt oder verändert wurde. */
    public static String decrypt(String payload, byte[] convKey) {
        try {
            if (payload.isEmpty() || payload.charAt(0) == '#') return null;
            byte[] d = Base64.getDecoder().decode(payload);
            if (d.length < 99 || d[0] != 2) return null;
            byte[] nonce = slice(d, 1, 32), ct = slice(d, 33, d.length - 65), mac = slice(d, d.length - 32, 32);
            byte[] keys = hkdfExpand(convKey, nonce, 76);
            byte[] ck = slice(keys, 0, 32), cn = slice(keys, 32, 12), hk = slice(keys, 44, 32);
            if (!MessageDigest.isEqual(mac, hmac(hk, nonce, ct))) return null;
            byte[] padded = chacha20(ck, cn, ct);
            int len = ((padded[0] & 0xff) << 8) | (padded[1] & 0xff);
            if (len < 1 || 2 + len > padded.length || padded.length != 2 + paddedLen(len)) return null;
            return new String(padded, 2, len, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static byte[] slice(byte[] a, int off, int len) {
        byte[] o = new byte[len];
        System.arraycopy(a, off, o, 0, len);
        return o;
    }

    // ------------------------------------------------------------------ ChaCha20 (RFC 8439), Zähler ab 0

    private static int rotl(int v, int c) { return (v << c) | (v >>> (32 - c)); }

    private static int le(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    static byte[] chacha20(byte[] key, byte[] nonce, byte[] in) {
        int[] st = new int[16];
        st[0] = 0x61707865; st[1] = 0x3320646e; st[2] = 0x79622d32; st[3] = 0x6b206574;
        for (int i = 0; i < 8; i++) st[4 + i] = le(key, i * 4);
        st[12] = 0;
        for (int i = 0; i < 3; i++) st[13 + i] = le(nonce, i * 4);
        byte[] out = new byte[in.length];
        int[] x = new int[16];
        byte[] block = new byte[64];
        for (int pos = 0; pos < in.length; pos += 64) {
            System.arraycopy(st, 0, x, 0, 16);
            for (int r = 0; r < 10; r++) {
                qr(x, 0, 4, 8, 12); qr(x, 1, 5, 9, 13); qr(x, 2, 6, 10, 14); qr(x, 3, 7, 11, 15);
                qr(x, 0, 5, 10, 15); qr(x, 1, 6, 11, 12); qr(x, 2, 7, 8, 13); qr(x, 3, 4, 9, 14);
            }
            for (int i = 0; i < 16; i++) {
                int v = x[i] + st[i];
                block[i * 4] = (byte) v; block[i * 4 + 1] = (byte) (v >>> 8);
                block[i * 4 + 2] = (byte) (v >>> 16); block[i * 4 + 3] = (byte) (v >>> 24);
            }
            int n = Math.min(64, in.length - pos);
            for (int i = 0; i < n; i++) out[pos + i] = (byte) (in[pos + i] ^ block[i]);
            st[12]++;
        }
        return out;
    }

    private static void qr(int[] x, int a, int b, int c, int d) {
        x[a] += x[b]; x[d] = rotl(x[d] ^ x[a], 16);
        x[c] += x[d]; x[b] = rotl(x[b] ^ x[c], 12);
        x[a] += x[b]; x[d] = rotl(x[d] ^ x[a], 8);
        x[c] += x[d]; x[b] = rotl(x[b] ^ x[c], 7);
    }
}
