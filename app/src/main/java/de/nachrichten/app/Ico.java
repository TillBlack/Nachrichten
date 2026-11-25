package de.nachrichten.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Selbst gezeichnete Symbole. */
final class Ico extends View {

    static final int PLUS = 0, SETTINGS = 1, BACK = 2, SEND = 3, QR = 4, MORE = 5, CLOCK = 6, CHECK = 7,
            RADIO = 8, TOGGLE = 9, LOCK = 10, ATTACH = 11, CLOSE = 12, FILE = 13, CHECK2 = 14, SHARE = 15, BAG = 16,
            MIC = 17, PLAY = 18, PAUSE = 19, TRASH = 20;

    int kind;
    boolean on;
    int color;

    void setKind(int k) { kind = k; invalidate(); }
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    Ico(Context c, int kind) {
        super(c);
        this.kind = kind;
        color = Pal.text;
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
        float d = getResources().getDisplayMetrics().density;
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f * d);
        switch (kind) {
            case PLUS: {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Pal.accent);
                float rad = Math.min(w, h) / 2f;
                c.drawCircle(cx, cy, rad, p);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(Pal.onAccent);
                p.setStrokeWidth(3f * d);
                float a = rad * 0.36f;
                c.drawLine(cx - a, cy, cx + a, cy, p);
                c.drawLine(cx, cy - a, cx, cy + a, p);
                break;
            }
            case SETTINGS: {
                float half = 9f * d;
                float[] ys = {cy - 6f * d, cy, cy + 6f * d};
                float[] kx = {cx + 4f * d, cx - 4f * d, cx + 1f * d};
                for (int i = 0; i < 3; i++) c.drawLine(cx - half, ys[i], cx + half, ys[i], p);
                for (int i = 0; i < 3; i++) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Pal.card);
                    c.drawCircle(kx[i], ys[i], 3.2f * d, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setColor(color);
                    c.drawCircle(kx[i], ys[i], 2.6f * d, p);
                }
                break;
            }
            case BACK: {
                p.setStrokeWidth(2.4f * d);
                path.reset();
                path.moveTo(cx + 3f * d, cy - 8f * d);
                path.lineTo(cx - 5f * d, cy);
                path.lineTo(cx + 3f * d, cy + 8f * d);
                c.drawPath(path, p);
                break;
            }
            case SEND: {
                p.setStyle(Paint.Style.FILL);
                p.setColor(on ? Pal.accent : Pal.field);
                float rad = Math.min(w, h) / 2f;
                c.drawCircle(cx, cy, rad, p);
                p.setColor(on ? Pal.onAccent : Pal.dim);
                path.reset();
                path.moveTo(cx - 7f * d, cy - 7.5f * d);
                path.lineTo(cx + 9f * d, cy);
                path.lineTo(cx - 7f * d, cy + 7.5f * d);
                path.lineTo(cx - 4.5f * d, cy);
                path.close();
                c.drawPath(path, p);
                break;
            }
            case QR: {
                float s = 7f * d, g = 2f * d;
                float[][] corners = {{cx - g - s, cy - g - s}, {cx + g, cy - g - s}, {cx - g - s, cy + g}};
                for (float[] k : corners) {
                    r.set(k[0], k[1], k[0] + s, k[1] + s);
                    c.drawRect(r, p);
                }
                p.setStyle(Paint.Style.FILL);
                c.drawRect(cx + g, cy + g, cx + g + 3f * d, cy + g + 3f * d, p);
                c.drawRect(cx + g + 4f * d, cy + g + 4f * d, cx + g + s, cy + g + s, p);
                break;
            }
            case MORE: {
                p.setStyle(Paint.Style.FILL);
                for (int i = -1; i <= 1; i++) c.drawCircle(cx, cy + i * 6f * d, 2f * d, p);
                break;
            }
            case CLOCK: {
                p.setStrokeWidth(1.3f * d);
                c.drawCircle(cx, cy, 5f * d, p);
                c.drawLine(cx, cy, cx, cy - 3f * d, p);
                c.drawLine(cx, cy, cx + 2.2f * d, cy + 1f * d, p);
                break;
            }
            case CHECK: {
                p.setStrokeWidth(1.9f * d);
                path.reset();
                path.moveTo(cx - 5.5f * d, cy + 0.5f * d);
                path.lineTo(cx - 1.8f * d, cy + 4f * d);
                path.lineTo(cx + 5.5f * d, cy - 4f * d);
                c.drawPath(path, p);
                break;
            }
            case CHECK2: {
                p.setStrokeWidth(1.9f * d);
                float o = 3.2f * d;
                path.reset();
                path.moveTo(cx - 7.5f * d, cy + 0.5f * d);
                path.lineTo(cx - 3.8f * d, cy + 4f * d);
                path.lineTo(cx + 3.5f * d, cy - 4f * d);
                c.drawPath(path, p);
                path.reset();
                path.moveTo(cx - 3.8f * d + o, cy + 4f * d);
                path.lineTo(cx + 3.5f * d + o, cy - 4f * d);
                c.drawPath(path, p);
                break;
            }
            case RADIO: {
                p.setColor(on ? Pal.accent : Pal.dim);
                c.drawCircle(cx, cy, 9f * d, p);
                if (on) {
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx, cy, 5f * d, p);
                }
                break;
            }
            case TOGGLE: {
                float tw = 40f * d, th = 22f * d;
                r.set(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2);
                p.setStyle(Paint.Style.FILL);
                p.setColor(on ? Pal.accent : Pal.line);
                c.drawRoundRect(r, th / 2, th / 2, p);
                p.setColor(0xFFFFFFFF);
                c.drawCircle(on ? r.right - th / 2 : r.left + th / 2, cy, th / 2 - 3f * d, p);
                break;
            }
            case ATTACH: {
                p.setStrokeWidth(2.4f * d);
                c.drawLine(cx - 8f * d, cy, cx + 8f * d, cy, p);
                c.drawLine(cx, cy - 8f * d, cx, cy + 8f * d, p);
                break;
            }
            case CLOSE: {
                c.drawLine(cx - 5f * d, cy - 5f * d, cx + 5f * d, cy + 5f * d, p);
                c.drawLine(cx + 5f * d, cy - 5f * d, cx - 5f * d, cy + 5f * d, p);
                break;
            }
            case FILE: {
                p.setStrokeWidth(1.8f * d);
                float l = cx - 7f * d, t = cy - 9f * d, rr = cx + 7f * d, b = cy + 9f * d, f = 5f * d;
                path.reset();
                path.moveTo(l, t);
                path.lineTo(rr - f, t);
                path.lineTo(rr, t + f);
                path.lineTo(rr, b);
                path.lineTo(l, b);
                path.close();
                c.drawPath(path, p);
                path.reset();
                path.moveTo(rr - f, t);
                path.lineTo(rr - f, t + f);
                path.lineTo(rr, t + f);
                c.drawPath(path, p);
                c.drawLine(l + 3.5f * d, cy + 1f * d, rr - 3.5f * d, cy + 1f * d, p);
                c.drawLine(l + 3.5f * d, cy + 4.5f * d, rr - 3.5f * d, cy + 4.5f * d, p);
                break;
            }
            case BAG: {
                // Laden mit Markise (Bögen), Fenster links, Bogentür rechts.
                p.setStrokeWidth(1.6f * d);
                float topY = -8.2f, baseY = -4.0f, topL = -6.2f, topR = 6.2f, baseL = -8.6f, baseR = 8.6f;
                int nSc = 5;
                float sw = (baseR - baseL) / nSc, rad = sw / 2f, drop = rad * 0.95f;
                // Markisen-Trapez (Oberkante + schräge Seiten)
                c.drawLine(cx + topL * d, cy + topY * d, cx + topR * d, cy + topY * d, p);
                c.drawLine(cx + topL * d, cy + topY * d, cx + baseL * d, cy + baseY * d, p);
                c.drawLine(cx + topR * d, cy + topY * d, cx + baseR * d, cy + baseY * d, p);
                // Nahtlinien im Trapez
                for (int i = 0; i <= nSc; i++) {
                    float xb = baseL + i * sw;
                    float t = (xb - baseL) / (baseR - baseL);
                    float xt = topL + (topR - topL) * t;
                    c.drawLine(cx + xt * d, cy + topY * d, cx + xb * d, cy + baseY * d, p);
                }
                // Bögen (nach unten gewölbt)
                for (int i = 0; i < nSc; i++) {
                    float x0 = baseL + i * sw, x1 = x0 + sw;
                    r.set(cx + x0 * d, cy + (baseY - drop) * d, cx + x1 * d, cy + (baseY + drop) * d);
                    c.drawArc(r, 0, 180, false, p);
                }
                // Wände + Boden
                float wallL = -6.8f, wallR = 6.8f, botY = 8.8f, wallTop = baseY + drop;
                c.drawLine(cx + wallL * d, cy + wallTop * d, cx + wallL * d, cy + botY * d, p);
                c.drawLine(cx + wallR * d, cy + wallTop * d, cx + wallR * d, cy + botY * d, p);
                c.drawLine(cx + wallL * d, cy + botY * d, cx + wallR * d, cy + botY * d, p);
                // Fenster (links, quadratisch)
                r.set(cx - 5.0f * d, cy + 0.6f * d, cx - 0.6f * d, cy + 6.0f * d);
                c.drawRect(r, p);
                // Bogentür (rechts)
                float dxl = 1.6f * d, dxr = 5.2f * d, archTop = 0.4f, archR = 1.8f;
                c.drawLine(cx + dxl, cy + botY * d, cx + dxl, cy + (archTop + archR) * d, p);
                c.drawLine(cx + dxr, cy + botY * d, cx + dxr, cy + (archTop + archR) * d, p);
                r.set(cx + dxl, cy + archTop * d, cx + dxr, cy + (archTop + 2 * archR) * d);
                c.drawArc(r, 180, 180, false, p);
                break;
            }
            case SHARE: {
                // Geschwungener Weiterleiten-Pfeil, 1:1 aus der Vorlage getract (hohler Umriss).
                path.reset();
                path.setFillType(Path.FillType.EVEN_ODD);
                // Außenkontur
                path.moveTo(cx + 8.11f * d, cy - 0.20f * d);
                path.lineTo(cx - 1.01f * d, cy - 7.50f * d);
                path.lineTo(cx - 1.62f * d, cy - 3.65f * d);
                path.lineTo(cx - 4.86f * d, cy - 2.64f * d);
                path.lineTo(cx - 6.89f * d, cy - 0.61f * d);
                path.lineTo(cx - 8.31f * d, cy + 6.69f * d);
                path.lineTo(cx - 5.07f * d, cy + 3.85f * d);
                path.lineTo(cx - 1.82f * d, cy + 3.24f * d);
                path.lineTo(cx - 1.62f * d, cy + 6.69f * d);
                path.lineTo(cx - 0.61f * d, cy + 7.30f * d);
                path.close();
                // Innenkontur (Loch)
                path.moveTo(cx + 5.88f * d, cy - 0.20f * d);
                path.lineTo(cx + 0.20f * d, cy + 4.86f * d);
                path.lineTo(cx - 0.20f * d, cy + 1.82f * d);
                path.lineTo(cx - 6.49f * d, cy + 2.84f * d);
                path.lineTo(cx - 4.46f * d, cy - 1.22f * d);
                path.lineTo(cx - 0.20f * d, cy - 2.23f * d);
                path.lineTo(cx + 0.00f * d, cy - 5.07f * d);
                path.close();
                p.setStyle(Paint.Style.FILL);
                p.setPathEffect(new android.graphics.CornerPathEffect(0.6f * d));
                c.drawPath(path, p);
                p.setPathEffect(null);
                path.setFillType(Path.FillType.WINDING);
                break;
            }
            case LOCK: {
                p.setStrokeWidth(1.4f * d);
                r.set(cx - 4.5f * d, cy - 1f * d, cx + 4.5f * d, cy + 6f * d);
                c.drawRoundRect(r, 1.5f * d, 1.5f * d, p);
                r.set(cx - 3f * d, cy - 6f * d, cx + 3f * d, cy + 2f * d);
                c.drawArc(r, 180, 180, false, p);
                break;
            }
            case MIC: {
                p.setStrokeWidth(2f * d);
                r.set(cx - 3f * d, cy - 8f * d, cx + 3f * d, cy + 1f * d);
                c.drawRoundRect(r, 3f * d, 3f * d, p);
                r.set(cx - 5.5f * d, cy - 4f * d, cx + 5.5f * d, cy + 4f * d);
                c.drawArc(r, 20, 140, false, p);
                c.drawLine(cx, cy + 4f * d, cx, cy + 8f * d, p);
                c.drawLine(cx - 3.5f * d, cy + 8f * d, cx + 3.5f * d, cy + 8f * d, p);
                break;
            }
            case PLAY: {
                p.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(cx - 3.5f * d, cy - 5.5f * d);
                path.lineTo(cx + 5.5f * d, cy);
                path.lineTo(cx - 3.5f * d, cy + 5.5f * d);
                path.close();
                c.drawPath(path, p);
                break;
            }
            case PAUSE: {
                p.setStyle(Paint.Style.FILL);
                r.set(cx - 4.2f * d, cy - 5.5f * d, cx - 1.2f * d, cy + 5.5f * d);
                c.drawRect(r, p);
                r.set(cx + 1.2f * d, cy - 5.5f * d, cx + 4.2f * d, cy + 5.5f * d);
                c.drawRect(r, p);
                break;
            }
            case TRASH: {
                p.setStrokeWidth(1.8f * d);
                c.drawLine(cx - 7f * d, cy - 5f * d, cx + 7f * d, cy - 5f * d, p); // Deckel
                r.set(cx - 2.5f * d, cy - 8f * d, cx + 2.5f * d, cy - 5f * d);     // Griff
                c.drawArc(r, 180, 180, false, p);
                path.reset();                                                     // Korpus
                path.moveTo(cx - 5.2f * d, cy - 5f * d);
                path.lineTo(cx - 4.3f * d, cy + 7.5f * d);
                path.lineTo(cx + 4.3f * d, cy + 7.5f * d);
                path.lineTo(cx + 5.2f * d, cy - 5f * d);
                c.drawPath(path, p);
                for (int i = -1; i <= 1; i++) c.drawLine(cx + i * 3f * d, cy - 2.5f * d, cx + i * 3f * d, cy + 5f * d, p);
                break;
            }
        }
    }
}
