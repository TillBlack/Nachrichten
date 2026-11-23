package de.nachrichten.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Drei Punkte, die nacheinander hoch und runter hüpfen. */
final class TypingDots extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long start = System.currentTimeMillis();

    TypingDots(Context c) { super(c); }

    @Override
    protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float r = 3.6f * d, gap = 11f * d, cy = getHeight() / 2f + 2f * d;
        float x0 = getWidth() / 2f - gap;
        double t = (System.currentTimeMillis() - start) / 1000.0;
        for (int i = 0; i < 3; i++) {
            // jede Kugel hüpft kurz nach der vorherigen
            double phase = (t * 1.6 - i * 0.18) % 1.0;
            if (phase < 0) phase += 1;
            float lift = phase < 0.4 ? (float) Math.sin(phase / 0.4 * Math.PI) : 0f;
            p.setColor(Pal.dim);
            p.setAlpha((int) (140 + 115 * lift));
            c.drawCircle(x0 + i * gap, cy - lift * 5f * d, r, p);
        }
        if (isShown()) postInvalidateOnAnimation();
    }
}
