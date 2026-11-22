package de.nachrichten.crypto;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * secp256k1 mit BIP-340-Schnorr-Signaturen und ECDH (nur x-Koordinate).
 * Reines Java, ohne Bibliotheken. Rechnet in Jacobi-Koordinaten.
 */
public final class Secp {

    public static final BigInteger P = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
    public static final BigInteger N = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
    private static final BigInteger GX = new BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16);
    private static final BigInteger GY = new BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16);
    private static final BigInteger SEVEN = BigInteger.valueOf(7);
    private static final BigInteger SQRT_EXP = P.add(BigInteger.ONE).shiftRight(2);
    private static final SecureRandom RNG = new SecureRandom();

    /** Punkt in affinen Koordinaten; null = Unendlich. */
    public static final class Point {
        public final BigInteger x, y;
        Point(BigInteger x, BigInteger y) { this.x = x; this.y = y; }
        boolean evenY() { return !y.testBit(0); }
    }

    public static final Point G = new Point(GX, GY);

    // ------------------------------------------------------------------ Punktarithmetik (Jacobi)

    /** Jacobi-Punkt {X, Y, Z}; Z == 0 bedeutet Unendlich. */
    private static BigInteger[] jac(Point p) { return new BigInteger[]{p.x, p.y, BigInteger.ONE}; }

    private static BigInteger[] jDouble(BigInteger[] p) {
        if (p[2].signum() == 0 || p[1].signum() == 0) return new BigInteger[]{BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        BigInteger x = p[0], y = p[1], z = p[2];
        BigInteger ysq = y.multiply(y).mod(P);
        BigInteger s = x.multiply(ysq).shiftLeft(2).mod(P);
        BigInteger m = x.multiply(x).multiply(BigInteger.valueOf(3)).mod(P);
        BigInteger nx = m.multiply(m).subtract(s.shiftLeft(1)).mod(P);
        BigInteger ny = m.multiply(s.subtract(nx)).subtract(ysq.multiply(ysq).shiftLeft(3)).mod(P);
        BigInteger nz = y.multiply(z).shiftLeft(1).mod(P);
        return new BigInteger[]{nx, ny, nz};
    }

    private static BigInteger[] jAdd(BigInteger[] p, BigInteger[] q) {
        if (p[2].signum() == 0) return q;
        if (q[2].signum() == 0) return p;
        BigInteger z1s = p[2].multiply(p[2]).mod(P), z2s = q[2].multiply(q[2]).mod(P);
        BigInteger u1 = p[0].multiply(z2s).mod(P), u2 = q[0].multiply(z1s).mod(P);
        BigInteger s1 = p[1].multiply(z2s).multiply(q[2]).mod(P), s2 = q[1].multiply(z1s).multiply(p[2]).mod(P);
        if (u1.equals(u2)) {
            if (s1.equals(s2)) return jDouble(p);
            return new BigInteger[]{BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        }
        BigInteger h = u2.subtract(u1).mod(P), r = s2.subtract(s1).mod(P);
        BigInteger h2 = h.multiply(h).mod(P), h3 = h2.multiply(h).mod(P);
        BigInteger u1h2 = u1.multiply(h2).mod(P);
        BigInteger nx = r.multiply(r).subtract(h3).subtract(u1h2.shiftLeft(1)).mod(P);
        BigInteger ny = r.multiply(u1h2.subtract(nx)).subtract(s1.multiply(h3)).mod(P);
        BigInteger nz = h.multiply(p[2]).multiply(q[2]).mod(P);
        return new BigInteger[]{nx, ny, nz};
    }

    private static Point affine(BigInteger[] p) {
        if (p[2].signum() == 0) return null;
        BigInteger zi = p[2].modInverse(P), zi2 = zi.multiply(zi).mod(P);
        return new Point(p[0].multiply(zi2).mod(P), p[1].multiply(zi2).multiply(zi).mod(P));
    }

    // Tabelle für k*G: 2^i * G
    private static BigInteger[][] gTable;

    private static synchronized BigInteger[][] table() {
        if (gTable == null) {
            BigInteger[][] t = new BigInteger[256][];
            BigInteger[] cur = jac(G);
            for (int i = 0; i < 256; i++) {
                Point a = affine(cur);
                t[i] = jac(a);
                cur = jDouble(cur);
            }
            gTable = t;
        }
        return gTable;
    }

    public static Point mulG(BigInteger k) {
        BigInteger[][] t = table();
        BigInteger[] r = {BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        for (int i = 0; i < 256; i++) if (k.testBit(i)) r = jAdd(r, t[i]);
        return affine(r);
    }

    public static Point mul(Point p, BigInteger k) {
        BigInteger[] r = {BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        BigInteger[] a = jac(p);
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = jDouble(r);
            if (k.testBit(i)) r = jAdd(r, a);
        }
        return affine(r);
    }

    /** Punkt mit x-Koordinate x und gerader y-Koordinate (BIP-340 lift_x). */
    public static Point liftX(BigInteger x) {
        if (x.signum() < 0 || x.compareTo(P) >= 0) return null;
        BigInteger c = x.modPow(BigInteger.valueOf(3), P).add(SEVEN).mod(P);
        BigInteger y = c.modPow(SQRT_EXP, P);
        if (!y.multiply(y).mod(P).equals(c)) return null;
        return new Point(x, y.testBit(0) ? P.subtract(y) : y);
    }

    // ------------------------------------------------------------------ Hilfen

    public static byte[] bytes32(BigInteger v) {
        byte[] b = v.toByteArray(), out = new byte[32];
        if (b.length >= 32) System.arraycopy(b, b.length - 32, out, 0, 32);
        else System.arraycopy(b, 0, out, 32 - b.length, b.length);
        return out;
    }

    public static BigInteger num(byte[] b) { return new BigInteger(1, b); }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static byte[] taggedHash(String tag, byte[]... parts) {
        byte[] th = sha256(tag.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[][] all = new byte[parts.length + 2][];
        all[0] = th; all[1] = th;
        System.arraycopy(parts, 0, all, 2, parts.length);
        return sha256(all);
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] o = new byte[a.length];
        for (int i = 0; i < a.length; i++) o[i] = (byte) (a[i] ^ b[i]);
        return o;
    }

    // ------------------------------------------------------------------ Schlüssel

    public static byte[] newPrivateKey() {
        while (true) {
            byte[] k = new byte[32];
            RNG.nextBytes(k);
            BigInteger d = num(k);
            if (d.signum() > 0 && d.compareTo(N) < 0) return k;
        }
    }

    public static boolean validPrivateKey(byte[] k) {
        if (k == null || k.length != 32) return false;
        BigInteger d = num(k);
        return d.signum() > 0 && d.compareTo(N) < 0;
    }

    /** x-only öffentlicher Schlüssel (32 Byte). */
    public static byte[] publicKey(byte[] priv) {
        return bytes32(mulG(num(priv)).x);
    }

    public static boolean validPublicKey(byte[] pub) {
        return pub != null && pub.length == 32 && liftX(num(pub)) != null;
    }

    // ------------------------------------------------------------------ Schnorr (BIP-340)

    public static byte[] sign(byte[] msg32, byte[] priv) {
        byte[] aux = new byte[32];
        RNG.nextBytes(aux);
        return sign(msg32, priv, aux);
    }

    public static byte[] sign(byte[] msg32, byte[] priv, byte[] aux) {
        BigInteger d0 = num(priv);
        Point pk = mulG(d0);
        BigInteger d = pk.evenY() ? d0 : N.subtract(d0);
        byte[] t = xor(bytes32(d), taggedHash("BIP0340/aux", aux));
        byte[] px = bytes32(pk.x);
        BigInteger k0 = num(taggedHash("BIP0340/nonce", t, px, msg32)).mod(N);
        if (k0.signum() == 0) throw new IllegalStateException("k = 0");
        Point r = mulG(k0);
        BigInteger k = r.evenY() ? k0 : N.subtract(k0);
        byte[] rx = bytes32(r.x);
        BigInteger e = num(taggedHash("BIP0340/challenge", rx, px, msg32)).mod(N);
        byte[] sig = new byte[64];
        System.arraycopy(rx, 0, sig, 0, 32);
        System.arraycopy(bytes32(k.add(e.multiply(d)).mod(N)), 0, sig, 32, 32);
        return sig;
    }

    public static boolean verify(byte[] msg32, byte[] pub, byte[] sig) {
        if (sig.length != 64 || pub.length != 32) return false;
        Point p = liftX(num(pub));
        if (p == null) return false;
        byte[] rb = new byte[32], sb = new byte[32];
        System.arraycopy(sig, 0, rb, 0, 32);
        System.arraycopy(sig, 32, sb, 0, 32);
        BigInteger r = num(rb), s = num(sb);
        if (r.compareTo(P) >= 0 || s.compareTo(N) >= 0) return false;
        BigInteger e = num(taggedHash("BIP0340/challenge", rb, pub, msg32)).mod(N);
        BigInteger[] sum = jAdd(jac(mulG(s)), jac(mul(p, N.subtract(e))));
        Point rr = affine(sum);
        return rr != null && rr.evenY() && rr.x.equals(r);
    }

    // ------------------------------------------------------------------ ECDH

    /** Gemeinsames Geheimnis: x-Koordinate von priv * lift_x(pub). */
    public static byte[] sharedX(byte[] priv, byte[] pub) {
        Point p = liftX(num(pub));
        if (p == null) throw new IllegalArgumentException("bad public key");
        return bytes32(mul(p, num(priv)).x);
    }
}
