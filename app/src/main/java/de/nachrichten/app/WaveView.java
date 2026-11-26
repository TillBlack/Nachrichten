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

    interface Seek { void onSeek(float frac); }

    private float[] amps = new float[0]; // Werte 0..1
    private float progress = -1f;        // 0..1 während der Wiedergabe, sonst < 0
    int barColor = 0xFF9AA0A6;
    int playedColor = Pal.accent;
    boolean growFromLeft = false;        // bei der Aufnahme: neueste Werte rechts, links auffüllen
    boolean showHandle = false;          // sichtbarer Schieberegler-Punkt
    int handleColor = 0;                 // Farbe des Spul-Kreises (0 = playedColor)
    static final float HANDLE_R = 7f;    // Radius des Spul-Kreises in dp
    Seek seekListener;                   // gesetzt => per Finger spulbar
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    WaveView(Context c) { super(c); p.setStyle(Paint.Style.FILL); }

    void setAmps(float[] a) { amps = a == null ? new float[0] : a; invalidate(); }
    void setProgress(float f) { progress = f; invalidate(); }

    /** Tippposition in einen Anteil 0..1 umrechnen (Rand für den Reglerpunkt berücksichtigt). */
    float fractionAt(float x) {
        int w = getWidth();
        if (w <= 0) return 0;
        float inset = showHandle ? (HANDLE_R + 1.5f) * getResources().getDisplayMetrics().density : 0f;
        float usable = Math.max(1f, w - 2 * inset);
        return Math.max(0f, Math.min(1f, (x - inset) / usable));
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent ev) {
        if (seekListener == null) return super.onTouchEvent(ev);
        int a = ev.getAction();
        if (a == android.view.MotionEvent.ACTION_DOWN || a == android.view.MotionEvent.ACTION_MOVE || a == android.view.MotionEvent.ACTION_UP) {
            if (a == android.view.MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
            float f = fractionAt(ev.getX());
            setProgress(f);
            seekListener.onSeek(f);
            if (a == android.view.MotionEvent.ACTION_UP) getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        return super.onTouchEvent(ev);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || amps.length == 0) return;
        float d = getResources().getDisplayMetrics().density;
        float hr = HANDLE_R * d;              // Radius des Schiebereglers
        float bw = 2.2f * d, gap = 2.2f * d, step = bw + gap;
        float inset = showHandle ? hr + 1.5f * d : 0f;  // Platz lassen, damit der Punkt an den Rändern nicht abgeschnitten wird
        float usable = w - 2 * inset;
        int slots = Math.max(1, (int) (usable / step));
        float cy = h / 2f, maxH = h - 2f * d; // Balken symmetrisch um die Mitte
        float startX = inset + (usable - slots * step + gap) / 2f;
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
        // Schieberegler-Punkt an der Wiedergabeposition (innerhalb der Ränder)
        if (showHandle && progress >= 0) {
            float hx = inset + Math.max(0f, Math.min(1f, progress)) * usable;
            // weißer Rand + farbiger Kreis: auf jeder Blasenfarbe gut sichtbar
            p.setColor(0xFFFFFFFF);
            c.drawCircle(hx, cy, hr + 1.5f * d, p);
            p.setColor(handleColor != 0 ? handleColor : playedColor);
            c.drawCircle(hx, cy, hr, p);
        }
    }

    private void drawBar(Canvas c, float x, float cy, float bh, float bw, int col) {
        p.setColor(col);
        c.drawRoundRect(x, cy - bh / 2f, x + bw, cy + bh / 2f, bw / 2f, bw / 2f, p);
    }
}
