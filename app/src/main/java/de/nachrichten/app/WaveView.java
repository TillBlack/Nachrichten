package de.nachrichten.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * Lautstärke-Wellenform: abgerundete Balken, deren Höhe die Lautstärke darstellt.
 * Leise Stellen werden zu kleinen Punkten – wie bei WhatsApp.
 */
final class WaveView extends View {

    private float[] amps = new float[0]; // Werte 0..1
    private float progress = -1f;        // 0..1 während der Wiedergabe, sonst < 0
    int barColor = 0xFF9AA0A6;
    int playedColor = Pal.accent;
    boolean growFromLeft = false;        // bei der Aufnahme: neueste Werte rechts, links auffüllen
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    WaveView(Context c) { super(c); p.setStyle(Paint.Style.FILL); }

    void setAmps(float[] a) { amps = a == null ? new float[0] : a; invalidate(); }
    void setProgress(float f) { progress = f; invalidate(); }

    /** Tippposition in einen Anteil 0..1 umrechnen. */
    float fractionAt(float x) {
        int w = getWidth();
        return w <= 0 ? 0 : Math.max(0f, Math.min(1f, x / w));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || amps.length == 0) return;
        float d = getResources().getDisplayMetrics().density;
        float bw = 2.6f * d, gap = 2.0f * d, step = bw + gap;
        int slots = Math.max(1, (int) (w / step));
        float cy = h / 2f, maxH = h - 2f * d;
        float startX = (w - slots * step + gap) / 2f;
        for (int i = 0; i < slots; i++) {
            float frac = slots <= 1 ? 0f : (float) i / (slots - 1);
            int idx;
            if (growFromLeft) {
                // neueste Werte rechtsbündig
                int off = amps.length - slots;
                idx = off + i;
                if (idx < 0) { // links leer lassen (kleiner Punkt)
                    drawBar(c, startX + i * step, cy, 1.4f * d, bw, barColor);
                    continue;
                }
            } else {
                idx = Math.round(frac * (amps.length - 1));
            }
            float a = Math.max(0f, Math.min(1f, amps[Math.max(0, Math.min(amps.length - 1, idx))]));
            float bh = Math.max(1.4f * d, a * maxH);
            int col = (progress >= 0 && frac <= progress) ? playedColor : barColor;
            drawBar(c, startX + i * step, cy, bh, bw, col);
        }
    }

    private void drawBar(Canvas c, float x, float cy, float bh, float bw, int col) {
        p.setColor(col);
        c.drawRoundRect(x, cy - bh / 2f, x + bw, cy + bh / 2f, bw / 2f, bw / 2f, p);
    }
}
