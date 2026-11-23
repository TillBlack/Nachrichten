package de.nachrichten.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Profilbild-Accessoires. Rahmen und Zubehör sind eigene PNG-Bilder (vom Nutzer erstellt),
 * die passend um das Profilbild gelegt werden. Der Glitzer wird weiter als Code gezeichnet.
 * Kategorien: 0 = hinter dem Bild, 1 = vor dem Bild, 2 = Rahmen.
 * Reihenfolge = Freischalt-Reihenfolge, gleichmäßig steigend von 10 bis 70 Nachrichten.
 */
final class Accessories {

    static final int BEHIND = 0, FRONT = 1, FRAME = 2;
    static final int COUNT = 20;

    // Code-Glitzer hat keine Bild-Ressource.
    private static final int GLITTER = 0;

    private static final String[] DE = {
            "Glitzer", "Katzenohren", "Katzenpfoten", "Herzen-Rahmen", "Blüten-Rahmen",
            "Krone", "Gold-Rahmen", "Engelsflügel", "Wolken-Rahmen", "Regenbogen-Rahmen",
            "Rosen-Rahmen", "Kopfhörer", "Koi-Rahmen", "Eis-Rahmen", "Teufelsflügel",
            "Kristall-Rahmen", "Magie-Rahmen", "Feuer-Rahmen", "Flammen-Rahmen", "Hai"};
    private static final String[] EN = {
            "Glitter", "Cat ears", "Cat paws", "Hearts frame", "Blossom frame",
            "Crown", "Gold frame", "Angel wings", "Cloud frame", "Rainbow frame",
            "Rose frame", "Headphones", "Koi frame", "Ice frame", "Devil wings",
            "Crystal frame", "Magic frame", "Fire frame", "Flame frame", "Shark"};
    private static final int[] CAT = {
            FRONT, FRONT, FRONT, FRAME, FRAME,
            FRONT, FRAME, BEHIND, FRAME, FRAME,
            FRAME, FRONT, FRAME, FRAME, BEHIND,
            FRAME, FRAME, FRAME, FRAME, BEHIND};
    /** Drawable-Name des Bildes (leer = Code-Glitzer). */
    private static final String[] RES = {
            "", "acc_ears", "acc_paws", "frame_hearts", "frame_flowers",
            "acc_crown", "frame_gold", "acc_wings_angel", "frame_clouds", "frame_rainbow",
            "frame_roses", "acc_headphones", "frame_koi", "frame_ice", "acc_wings_devil",
            "frame_crystal", "frame_magic", "frame_fire", "frame_flame", "acc_shark"};
    /** Bildbreite als Vielfaches des vollen Radius. */
    private static final float[] WF = {
            0f, 1.6f, 1.55f, 2.0f, 2.0f,
            1.05f, 2.0f, 2.15f, 2.0f, 2.0f,
            2.0f, 1.55f, 2.0f, 2.0f, 2.1f,
            2.0f, 2.0f, 2.0f, 2.0f, 1.95f};
    /** Vertikale Verschiebung als Vielfaches des vollen Radius. */
    private static final float[] OFFY = {
            0f, -0.66f, 0.74f, 0f, 0f,
            -0.76f, 0f, -0.02f, 0f, 0f,
            0f, 0.52f, 0f, 0f, 0.0f,
            0f, 0f, 0f, 0f, 0.08f};

    private static final Bitmap[] CACHE = new Bitmap[COUNT];
    private static final int[] RESID = new int[COUNT];
    private static boolean resLoaded = false;

    static String name(int i) { return (T.lang == 1 ? DE : EN)[i]; }
    static int category(int i) { return CAT[i]; }

    /** Gesamtzahl gesendeter Nachrichten, ab der Teil i freigeschaltet ist (10 … 70). */
    static int unlockAt(int i) {
        return Math.round(10f + 60f * i / (COUNT - 1));
    }

    static boolean unlocked(int i, int sent) { return sent >= unlockAt(i); }

    /** Nächstes noch gesperrtes Teil (oder -1). */
    static int nextLocked(int sent) {
        for (int i = 0; i < COUNT; i++) if (!unlocked(i, sent)) return i;
        return -1;
    }

    // ------------------------------------------------------------------ Bilder laden

    private static Bitmap bitmap(Context ctx, int i) {
        if (i < 0 || i >= COUNT || RES[i].isEmpty()) return null;
        if (CACHE[i] != null) return CACHE[i];
        if (!resLoaded) {
            for (int k = 0; k < COUNT; k++)
                RESID[k] = RES[k].isEmpty() ? 0
                        : ctx.getResources().getIdentifier(RES[k], "drawable", ctx.getPackageName());
            resLoaded = true;
        }
        if (RESID[i] == 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        CACHE[i] = BitmapFactory.decodeResource(ctx.getResources(), RESID[i], o);
        return CACHE[i];
    }

    private static void drawImage(Context ctx, Canvas c, int i, float cx, float cy, float rFull, Paint p) {
        Bitmap b = bitmap(ctx, i);
        if (b == null) return;
        float w = WF[i] * rFull;
        float h = w * b.getHeight() / (float) b.getWidth();
        float oy = OFFY[i] * rFull;
        RectF dst = new RectF(cx - w / 2f, cy - h / 2f + oy, cx + w / 2f, cy + h / 2f + oy);
        boolean prev = p.isFilterBitmap();
        p.setFilterBitmap(true);
        c.drawBitmap(b, null, dst, p);
        p.setFilterBitmap(prev);
    }

    // ------------------------------------------------------------------ Zeichnen
    // rFull = voller Radius der Ansicht, rAv = Radius des Profilbildes.

    static void drawBehind(Context ctx, Canvas c, float cx, float cy, float rFull, float rAv, int id, Paint p) {
        if (id < 0 || CAT[id] != BEHIND) return;
        drawImage(ctx, c, id, cx, cy, rFull, p);
    }

    static void drawFrame(Context ctx, Canvas c, float cx, float cy, float rFull, float rAv, int id, Paint p) {
        if (id < 0 || CAT[id] != FRAME) return;
        drawImage(ctx, c, id, cx, cy, rFull, p);
    }

    static void drawFront(Context ctx, Canvas c, float cx, float cy, float rFull, float rAv, int id, Paint p) {
        if (id < 0 || CAT[id] != FRONT) return;
        if (id == GLITTER) { glitter(c, cx, cy, rAv, p); return; }
        drawImage(ctx, c, id, cx, cy, rFull, p);
    }

    // ------------------------------------------------------------------ Code-Glitzer

    private static void sparkle(Canvas c, float x, float y, float s, int col, Paint p) {
        p.setColor(col);
        Path path = new Path();
        path.moveTo(x, y - s);
        path.quadTo(x + s * 0.18f, y - s * 0.18f, x + s, y);
        path.quadTo(x + s * 0.18f, y + s * 0.18f, x, y + s);
        path.quadTo(x - s * 0.18f, y + s * 0.18f, x - s, y);
        path.quadTo(x - s * 0.18f, y - s * 0.18f, x, y - s);
        path.close();
        c.drawPath(path, p);
    }

    private static void glitter(Canvas c, float cx, float cy, float r, Paint p) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        sparkle(c, cx + r * 0.7f, cy - r * 0.7f, r * 0.28f, 0xFFFFF2A0, p);
        sparkle(c, cx - r * 0.78f, cy - r * 0.2f, r * 0.2f, 0xFFFFFFFF, p);
        sparkle(c, cx + r * 0.85f, cy + r * 0.35f, r * 0.17f, 0xFFFFFFFF, p);
        sparkle(c, cx - r * 0.5f, cy + r * 0.8f, r * 0.15f, 0xFFFFF2A0, p);
        sparkle(c, cx + r * 0.1f, cy - r * 1.02f, r * 0.14f, 0xFFFFFFFF, p);
    }

    private Accessories() { }
}
