package de.nachrichten.crypto;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/** Bech32 für npub/nsec-Codes (NIP-19). */
public final class Bech32 {

    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int[] GEN = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};

    private static int polymod(byte[] values) {
        int chk = 1;
        for (byte v : values) {
            int b = chk >>> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ (v & 0xff);
            for (int i = 0; i < 5; i++) if (((b >>> i) & 1) != 0) chk ^= GEN[i];
        }
        return chk;
    }

    private static byte[] hrpExpand(String hrp) {
        byte[] r = new byte[hrp.length() * 2 + 1];
        for (int i = 0; i < hrp.length(); i++) {
            r[i] = (byte) (hrp.charAt(i) >> 5);
            r[i + hrp.length() + 1] = (byte) (hrp.charAt(i) & 31);
        }
        return r;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static byte[] convert(byte[] data, int from, int to, boolean pad) {
        int acc = 0, bits = 0, maxv = (1 << to) - 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte d : data) {
            int v = d & 0xff;
            if ((v >>> from) != 0) return null;
            acc = (acc << from) | v;
            bits += from;
            while (bits >= to) {
                bits -= to;
                out.write((acc >>> bits) & maxv);
            }
        }
        if (pad) {
            if (bits > 0) out.write((acc << (to - bits)) & maxv);
        } else if (bits >= from || ((acc << (to - bits)) & maxv) != 0) {
            return null;
        }
        return out.toByteArray();
    }

    public static String encode(String hrp, byte[] data) {
        byte[] d5 = convert(data, 8, 5, true);
        byte[] chkIn = concat(concat(hrpExpand(hrp), d5), new byte[6]);
        int pm = polymod(chkIn) ^ 1;
        StringBuilder sb = new StringBuilder(hrp).append('1');
        for (byte b : d5) sb.append(CHARSET.charAt(b));
        for (int i = 0; i < 6; i++) sb.append(CHARSET.charAt((pm >>> (5 * (5 - i))) & 31));
        return sb.toString();
    }

    /** Dekodiert; gibt null zurück, wenn hrp nicht passt oder die Prüfsumme falsch ist. */
    public static byte[] decode(String expectedHrp, String s) {
        if (s == null) return null;
        s = s.trim().toLowerCase(Locale.ROOT);
        int pos = s.lastIndexOf('1');
        if (pos < 1 || pos + 7 > s.length()) return null;
        String hrp = s.substring(0, pos);
        if (!hrp.equals(expectedHrp)) return null;
        byte[] data = new byte[s.length() - pos - 1];
        for (int i = 0; i < data.length; i++) {
            int v = CHARSET.indexOf(s.charAt(pos + 1 + i));
            if (v < 0) return null;
            data[i] = (byte) v;
        }
        if (polymod(concat(hrpExpand(hrp), data)) != 1) return null;
        byte[] d5 = new byte[data.length - 6];
        System.arraycopy(data, 0, d5, 0, d5.length);
        return convert(d5, 5, 8, false);
    }
}
