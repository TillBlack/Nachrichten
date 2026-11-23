package de.nachrichten.app;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Profilbild-Accessoires, als Vektor gezeichnet und mit dem Radius skaliert.
 * Kategorien: 0 = hinter dem Bild, 1 = vor dem Bild, 2 = Rahmen.
 * Reihenfolge = Freischalt-Reihenfolge.
 */
final class Accessories {

    static final int BEHIND = 0, FRONT = 1, FRAME = 2;
    static final int COUNT = 17;

    private static final String[] DE = {
            "Glitzer", "Einhorn-Horn", "Autoreifen", "Herzen", "Feuer", "Stern", "Licht",
            "Kopfhörer (Hals)", "Kopfhörer (Kopf)", "Muskelarme", "Glühbirne an", "Glühbirne aus",
            "Katzenpfoten", "Katzenohren", "Engelsflügel", "Teufelsflügel", "Kristallrahmen"};
    private static final String[] EN = {
            "Glitter", "Unicorn horn", "Car tire", "Hearts", "Fire", "Star", "Glow",
            "Headphones (neck)", "Headphones (head)", "Muscle arms", "Lightbulb on", "Lightbulb off",
            "Cat paws", "Cat ears", "Angel wings", "Devil wings", "Crystal frame"};
    private static final int[] CAT = {
            FRONT, FRONT, FRAME, FRONT, FRONT, FRONT, BEHIND,
            FRONT, FRONT, FRONT, FRONT, FRONT, FRONT, FRONT, BEHIND, BEHIND, FRAME};
    /** Nachrichten, die zusätzlich für dieses Teil nötig sind (steigend von 10 bis 70). */
    private static final int[] COST = {
            10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 70, 70, 70, 70};

    static String name(int i) { return (T.lang == 1 ? DE : EN)[i]; }
    static int category(int i) { return CAT[i]; }

    /** Gesamtzahl gesendeter Nachrichten, ab der Teil i freigeschaltet ist. */
    static int unlockAt(int i) {
        int sum = 0;
        for (int k = 0; k <= i; k++) sum += COST[k];
        return sum;
    }

    static boolean unlocked(int i, int sent) { return sent >= unlockAt(i); }

    /** Wie weit (als Vielfaches des Bildradius) ein Teil nach außen reicht. Bestimmt, wie stark das Bild schrumpft. */
    private static final float[] EXTENT = {
            1.3f, 1.5f, 1.25f, 1.3f, 1.15f, 1.15f, 1.0f, 1.25f, 1.35f, 1.45f, 1.35f, 1.35f, 1.25f, 1.35f, 1.45f, 1.5f, 1.26f};
    static float extent(int i) { return (i < 0 || i >= COUNT) ? 1f : EXTENT[i]; }

    /** Nächstes noch gesperrtes Teil (oder -1). */
    static int nextLocked(int sent) {
        for (int i = 0; i < COUNT; i++) if (!unlocked(i, sent)) return i;
        return -1;
    }

    // ------------------------------------------------------------------ Zeichnen

    static void drawBehind(Canvas c, float cx, float cy, float r, int id, Paint p) {
        if (id < 0) return;
        switch (id) {
            case 6: glow(c, cx, cy, r, p); break;
            case 14: angelWings(c, cx, cy, r, p); break;
            case 15: devilWings(c, cx, cy, r, p); break;
        }
    }

    static void drawFrame(Canvas c, float cx, float cy, float r, int id, Paint p) {
        if (id < 0) return;
        switch (id) {
            case 2: tire(c, cx, cy, r, p); break;
            case 16: crystal(c, cx, cy, r, p); break;
        }
    }

    static void drawFront(Canvas c, float cx, float cy, float r, int id, Paint p) {
        if (id < 0) return;
        switch (id) {
            case 0: glitter(c, cx, cy, r, p); break;
            case 1: horn(c, cx, cy, r, p); break;
            case 3: hearts(c, cx, cy, r, p); break;
            case 4: fire(c, cx, cy, r, p); break;
            case 5: star(c, cx, cy, r, p); break;
            case 7: headphonesNeck(c, cx, cy, r, p); break;
            case 8: headphonesHead(c, cx, cy, r, p); break;
            case 9: muscles(c, cx, cy, r, p); break;
            case 10: bulb(c, cx, cy, r, p, true); break;
            case 11: bulb(c, cx, cy, r, p, false); break;
            case 12: catPaws(c, cx, cy, r, p); break;
            case 13: catEars(c, cx, cy, r, p); break;
        }
    }

    private static void reset(Paint p) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

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
        reset(p);
        sparkle(c, cx + r * 0.7f, cy - r * 0.7f, r * 0.28f, 0xFFFFF2A0, p);
        sparkle(c, cx - r * 0.78f, cy - r * 0.2f, r * 0.2f, 0xFFFFFFFF, p);
        sparkle(c, cx + r * 0.85f, cy + r * 0.35f, r * 0.17f, 0xFFFFFFFF, p);
        sparkle(c, cx - r * 0.5f, cy + r * 0.8f, r * 0.15f, 0xFFFFF2A0, p);
        sparkle(c, cx + r * 0.1f, cy - r * 1.02f, r * 0.14f, 0xFFFFFFFF, p);
    }

    private static void horn(Canvas c, float cx, float cy, float r, Paint p) {
        reset(p);
        float baseY = cy - r * 0.86f, tipY = cy - r * 1.5f, hw = r * 0.2f;
        Path path = new Path();
        path.moveTo(cx - hw, baseY);
        path.lineTo(cx + hw, baseY);
        path.lineTo(cx, tipY);
        path.close();
        p.setColor(0xFFF6E27A);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.06f);
        p.setColor(0xFFB89838);
        for (int i = 1; i <= 3; i++) {
            float t = i / 4f;
            float y = baseY + (tipY - baseY) * t;
            float w = hw * (1 - t);
            c.drawLine(cx - w, y + r * 0.04f, cx + w, y - r * 0.04f, p);
        }
    }

    private static void star(Canvas c, float cx, float cy, float r, Paint p) {
        reset(p);
        p.setColor(0xFFFFD34E);
        drawStar(c, cx + r * 0.72f, cy - r * 0.72f, r * 0.42f, p);
    }

    private static void drawStar(Canvas c, float x, float y, float s, Paint p) {
        Path path = new Path();
        for (int i = 0; i < 10; i++) {
            double ang = Math.PI / 2 + i * Math.PI / 5;
            float rad = (i % 2 == 0) ? s : s * 0.42f;
            float px = x + (float) Math.cos(ang) * rad, py = y - (float) Math.sin(ang) * rad;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
        c.drawPath(path, p);
    }

    private static void heart(Canvas c, float x, float y, float s, int col, Paint p) {
        p.setColor(col);
        Path path = new Path();
        path.moveTo(x, y + s * 0.65f);
        path.cubicTo(x - s * 1.2f, y - s * 0.4f, x - s * 0.3f, y - s * 0.9f, x, y - s * 0.25f);
        path.cubicTo(x + s * 0.3f, y - s * 0.9f, x + s * 1.2f, y - s * 0.4f, x, y + s * 0.65f);
        path.close();
        c.drawPath(path, p);
    }

    private static void hearts(Canvas c, float cx, float cy, float r, Paint p) {
        reset(p);
        heart(c, cx + r * 0.75f, cy - r * 0.7f, r * 0.3f, 0xFFE8556B, p);
        heart(c, cx - r * 0.7f, cy - r * 0.55f, r * 0.22f, 0xFFF08193, p);
        heart(c, cx + r * 0.2f, cy - r * 1.0f, r * 0.18f, 0xFFE8556B, p);
    }

    private static void fire(Canvas c, float cx, float cy, float r, Paint p) {
        reset(p);
        float[] xs = {-0.6f, -0.1f, 0.45f, 0.8f};
        float[] sc = {0.6f, 0.9f, 0.75f, 0.5f};
        for (int i = 0; i < xs.length; i++) flame(c, cx + r * xs[i], cy + r * 0.95f, r * sc[i], p);
    }

    private static void flame(Canvas c, float x, float baseY, float s, Paint p) {
        Path outer = new Path();
        outer.moveTo(x, baseY);
        outer.cubicTo(x - s * 0.7f, baseY - s * 0.3f, x - s * 0.45f, baseY - s * 1.1f, x, baseY - s * 1.5f);
        outer.cubicTo(x + s * 0.45f, baseY - s * 1.1f, x + s * 0.7f, baseY - s * 0.3f, x, baseY);
        outer.close();
        p.setColor(0xFFF4731F);
        c.drawPath(outer, p);
        Path inner = new Path();
        inner.moveTo(x, baseY);
        inner.cubicTo(x - s * 0.35f, baseY - s * 0.3f, x - s * 0.22f, baseY - s * 0.75f, x, baseY - s * 1.0f);
        inner.cubicTo(x + s * 0.22f, baseY - s * 0.75f, x + s * 0.35f, baseY - s * 0.3f, x, baseY);
        inner.close();
        p.setColor(0xFFFFD03A);
        c.drawPath(inner, p);
    }

    private static void glow(Canvas c, float cx, float cy, float r, Paint p) {
        reset(p);
        p.setShader(new RadialGradient(cx, cy, r * 1.5f,
                new int[]{0x88FFE9A8, 0x44FFD36B, 0x00FFD36B}, new float[]{0.55f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 1.5f, p);
        p.setShader(null);
    }

    private static void headband(Canvas c, float cx, float cy, float r, Paint p, float topFactor) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.17f);
        p.setColor(0xFF2B2E35);
        RectF rect = new RectF(cx - r * 1.05f, cy - r * topFactor, cx + r * 1.05f, cy + r * 1.05f);
        c.drawArc(rect, 200, 140, false, p);
        p.setStyle(Paint.Style.FILL);
        for (int s = -1; s <= 1; s += 2) {
            float ex = cx + s * r * 1.02f;
            RectF cup = new RectF(ex - r * 0.26f, cy - r * 0.2f, ex + r * 0.26f, cy + r * 0.42f);
            p.setColor(0xFF3A3E46);
            c.drawRoundRect(cup, r * 0.12f, r * 0.12f, p);
            p.setColor(0xFF5B8DEF);
            c.drawCircle(ex, cy + r * 0.11f, r * 0.1f, p);
        }
    }

    private static void headphonesHead(Canvas c, float cx, float cy, float r, Paint p) {
        reset(p);
        headband(c, cx, cy, r, p, 1.25f);
    }

    private static void headphonesNeck(Canvas c, float cx, float cy, float r, Paint p) {
        reset(p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.16f);
        p.setColor(0xFF2B2E35);
        RectF rect = new RectF(cx - r * 1.02f, cy + r * 0.2f, cx + r * 1.02f, cy + r * 1.3f);
        c.drawArc(rect, 200, 140, false, p);
        p.setStyle(Paint.Style.FILL);
        for (int s = -1; s <= 1; s += 2) {
            float ex = cx + s * r * 0.98f;
            RectF cup = new RectF(ex - r * 0.24f, cy + r * 0.5f, ex + r * 0.24f, cy + r * 0.95f);
            p.setColor(0xFF3A3E46);
            c.drawRoundRect(cup, r * 0.1f, r * 0.1f, p);
        }
    }

    private static void muscles(Canvas c, float cx, float cy, float r, Paint p) {
        reset(p);
        p.setColor(0xFFE8B48A);
        for (int s = -1; s <= 1; s += 2) {
            float bx = cx + s * r * 0.9f;
            Path arm = new Path();
            arm.moveTo(bx, cy + r * 0.5f);
            arm.cubicTo(bx + s * r * 0.5f, cy + r * 0.45f, bx + s * r * 0.5f, cy + r * 1.1f, bx + s * r * 0.15f, cy + r * 1.15f);
            arm.cubicTo(bx - s * r * 0.05f, cy + r * 0.95f, bx + s * r * 0.1f, cy + r * 0.72f, bx, cy + r * 0.5f);
            arm.close();
            c.drawPath(arm, p);
            p.setColor(0xFFCF9468);
            c.drawCircle(bx + s * r * 0.26f, cy + r * 0.78f, r * 0.12f, p);
            p.setColor(0xFFE8B48A);
        }
    }

    private static void bulb(Canvas c, float cx, float cy, float r, Paint p, boolean on) {
        reset(p);
        float bx = cx + r * 0.78f, by = cy - r * 0.82f, s = r * 0.3f;
        if (on) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.05f);
            p.setColor(0xFFFFE07A);
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                c.drawLine(bx + (float) Math.cos(a) * s * 1.3f, by + (float) Math.sin(a) * s * 1.3f,
                        bx + (float) Math.cos(a) * s * 1.7f, by + (float) Math.sin(a) * s * 1.7f, p);
            }
            p.setStyle(Paint.Style.FILL);
        }
        p.setColor(on ? 0xFFFFE23A : 0xFFBFC3C8);
        c.drawCircle(bx, by, s, p);
        p.setColor(0xFF6A6F79);
        RectF base = new RectF(bx - s * 0.45f, by + s * 0.75f, bx + s * 0.45f, by + s * 1.2f);
        c.drawRoundRect(base, s * 0.12f, s * 0.12f, p);
    }

    private static void catEars(Canvas c, float cx, float cy, float r, Paint p) {
        reset(p);
        for (int s = -1; s <= 1; s += 2) {
            float ox = cx + s * r * 0.55f;
            Path ear = new Path();
            ear.moveTo(ox - r * 0.28f, cy - r * 0.78f);
            ear.lineTo(ox + r * 0.28f, cy - r * 0.78f);
            ear.lineTo(ox + s * r * 0.08f, cy - r * 1.3f);
            ear.close();
            p.setColor(0xFF3A3340);
            c.drawPath(ear, p);
            Path inner = new Path();
            inner.moveTo(ox - r * 0.13f, cy - r * 0.82f);
            inner.lineTo(ox + r * 0.13f, cy - r * 0.82f);
            inner.lineTo(ox + s * r * 0.05f, cy - r * 1.12f);
            inner.close();
            p.setColor(0xFFE59BB0);
            c.drawPath(inner, p);
        }
    }

    private static void catPaws(Canvas c, float cx, float cy, float r, Paint p) {
        reset(p);
        for (int s = -1; s <= 1; s += 2) {
            float ox = cx + s * r * 0.5f, oy = cy + r * 0.95f;
            p.setColor(0xFF4A4350);
            c.drawCircle(ox, oy, r * 0.26f, p);
            p.setColor(0xFFE59BB0);
            c.drawCircle(ox, oy + r * 0.05f, r * 0.12f, p);
            for (int i = -1; i <= 1; i++) c.drawCircle(ox + i * r * 0.13f, oy - r * 0.14f, r * 0.05f, p);
        }
    }

    private static void angelWings(Canvas c, float cx, float cy, float r, Paint p) {
        reset(p);
        p.setColor(0xFFFFFFFF);
        for (int s = -1; s <= 1; s += 2) wing(c, cx, cy, r, s, p, false);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.03f);
        p.setColor(0x33000000);
        for (int s = -1; s <= 1; s += 2) wing(c, cx, cy, r, s, p, true);
        p.setStyle(Paint.Style.FILL);
    }

    private static void wing(Canvas c, float cx, float cy, float r, int s, Paint p, boolean stroke) {
        Path w = new Path();
        float bx = cx + s * r * 0.55f;
        w.moveTo(bx, cy - r * 0.35f);
        w.cubicTo(bx + s * r * 0.95f, cy - r * 0.85f, bx + s * r * 1.05f, cy + r * 0.2f, bx + s * r * 0.7f, cy + r * 0.52f);
        w.cubicTo(bx + s * r * 0.48f, cy + r * 0.15f, bx + s * r * 0.33f, cy + r * 0.1f, bx, cy + r * 0.35f);
        w.close();
        c.drawPath(w, p);
    }

    private static void devilWings(Canvas c, float cx, float cy, float r, Paint p) {
        reset(p);
        for (int s = -1; s <= 1; s += 2) {
            Path w = new Path();
            float bx = cx + s * r * 0.55f;
            w.moveTo(bx, cy - r * 0.3f);
            w.lineTo(bx + s * r * 1.0f, cy - r * 0.65f);
            w.lineTo(bx + s * r * 0.8f, cy - r * 0.15f);
            w.lineTo(bx + s * r * 1.05f, cy + r * 0.08f);
            w.lineTo(bx + s * r * 0.72f, cy + r * 0.08f);
            w.lineTo(bx + s * r * 0.85f, cy + r * 0.45f);
            w.lineTo(bx + s * r * 0.45f, cy + r * 0.2f);
            w.close();
            p.setColor(0xFF9B2D3A);
            c.drawPath(w, p);
        }
    }

    private static void tire(Canvas c, float cx, float cy, float r, Paint p) {
        reset(p);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(0xFF1C1E22);
        p.setStrokeWidth(r * 0.3f);
        c.drawCircle(cx, cy, r * 1.08f, p);
        p.setStrokeWidth(r * 0.1f);
        p.setColor(0xFF3A3E44);
        float rr = r * 1.08f;
        for (int i = 0; i < 20; i++) {
            double a = i * Math.PI / 10;
            c.drawLine(cx + (float) Math.cos(a) * (rr - r * 0.12f), cy + (float) Math.sin(a) * (rr - r * 0.12f),
                    cx + (float) Math.cos(a) * (rr + r * 0.12f), cy + (float) Math.sin(a) * (rr + r * 0.12f), p);
        }
    }

    private static void crystal(Canvas c, float cx, float cy, float r, Paint p) {
        reset(p);
        float rr = r * 1.04f;
        int n = 14;
        Path ring = new Path();
        for (int i = 0; i < n * 2; i++) {
            double a = i * Math.PI / n - Math.PI / 2;
            float rad = (i % 2 == 0) ? rr + r * 0.2f : rr + r * 0.02f;
            float px = cx + (float) Math.cos(a) * rad, py = cy + (float) Math.sin(a) * rad;
            if (i == 0) ring.moveTo(px, py); else ring.lineTo(px, py);
        }
        ring.close();
        p.setColor(0xFF7A3FD6);
        c.drawPath(ring, p);
        // Facetten
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.03f);
        p.setColor(0x66FFFFFF);
        for (int i = 0; i < n; i++) {
            double a = i * 2 * Math.PI / n - Math.PI / 2;
            c.drawLine(cx + (float) Math.cos(a) * rr, cy + (float) Math.sin(a) * rr,
                    cx + (float) Math.cos(a) * (rr + r * 0.2f), cy + (float) Math.sin(a) * (rr + r * 0.2f), p);
        }
        p.setStyle(Paint.Style.FILL);
        // dünner Innenring
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.05f);
        p.setColor(0xFFB98BF0);
        c.drawCircle(cx, cy, rr, p);
        p.setStyle(Paint.Style.FILL);
    }

    private Accessories() { }
}
