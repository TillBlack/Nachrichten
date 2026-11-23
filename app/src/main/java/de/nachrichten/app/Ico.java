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
            RADIO = 8, TOGGLE = 9, LOCK = 10, ATTACH = 11, CLOSE = 12, FILE = 13, CHECK2 = 14;

    final int kind;
    boolean on;
    int color;
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
            case LOCK: {
                p.setStrokeWidth(1.4f * d);
                r.set(cx - 4.5f * d, cy - 1f * d, cx + 4.5f * d, cy + 6f * d);
                c.drawRoundRect(r, 1.5f * d, 1.5f * d, p);
                r.set(cx - 3f * d, cy - 6f * d, cx + 3f * d, cy + 2f * d);
                c.drawArc(r, 180, 180, false, p);
                break;
            }
        }
    }
}
