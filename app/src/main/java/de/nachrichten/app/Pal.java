package de.nachrichten.app;

/** Farben für dunkles und helles Design. */
final class Pal {
    static boolean light;
    static int bg, card, field, line, text, dim, accent, onAccent, red, bubbleIn, bubbleOut, onOut;

    static void set(boolean isLight) {
        light = isLight;
        if (isLight) {
            bg = 0xFFF2F2F0; card = 0xFFFFFFFF; field = 0xFFEDEDEB; line = 0xFFDADBDE;
            text = 0xFF16171A; dim = 0xFF6A6F79; accent = 0xFF2F7D6B; onAccent = 0xFFFFFFFF; red = 0xFFC4453A;
            bubbleIn = 0xFFFFFFFF; bubbleOut = 0xFFD6EEE6; onOut = 0xFF102A23;
        } else {
            bg = 0xFF0F1112; card = 0xFF1A1D1F; field = 0xFF23272A; line = 0xFF2D3235;
            text = 0xFFEBEDEE; dim = 0xFF8B9296; accent = 0xFF3FA58C; onAccent = 0xFFFFFFFF; red = 0xFFE36B5F;
            bubbleIn = 0xFF1E2224; bubbleOut = 0xFF1F4A3F; onOut = 0xFFE9F5F1;
        }
    }

    /** Ruhige Farbe für den Anfangsbuchstaben eines Kontakts. */
    static int avatar(String pub) {
        int[] c = {0xFF4F7CAC, 0xFF3FA58C, 0xFFB07A3B, 0xFF8A5DA8, 0xFFA8565D, 0xFF5D8A4F, 0xFF4F9AA8, 0xFF9A7B4F};
        return c[Math.abs(pub.hashCode() % c.length)];
    }
}
