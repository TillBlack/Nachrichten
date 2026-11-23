package de.nachrichten.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/** Zeile, die sich nach rechts ziehen lässt (zum Antworten). Klicks und langes Drücken gehen weiter an das Kind. */
final class SwipeRow extends FrameLayout {

    interface OnSwipe { void swiped(); }

    private final float slop, trigger, max;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private float downX, downY, dx;
    private boolean dragging, fired;
    OnSwipe onSwipe;

    SwipeRow(Context c) {
        super(c);
        float d = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        trigger = 64 * d;
        max = 88 * d;
        setWillNotDraw(false);
        setClipChildren(false);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                dragging = false; fired = false; dx = 0;
                return false;
            case MotionEvent.ACTION_MOVE:
                float mx = e.getX() - downX, my = e.getY() - downY;
                if (!dragging && mx > slop && mx > Math.abs(my) * 2) {
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                dx = Math.max(0, Math.min(max, (e.getX() - downX) * 0.7f));
                if (!fired && dx >= trigger) {
                    fired = true;
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                } else if (fired && dx < trigger) {
                    fired = false;
                }
                apply();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean go = fired && e.getActionMasked() == MotionEvent.ACTION_UP;
                dragging = false;
                animateBack();
                if (go && onSwipe != null) onSwipe.swiped();
                return true;
        }
        return true;
    }

    private void apply() {
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).setTranslationX(dx);
        invalidate();
    }

    private void animateBack() {
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).animate().translationX(0).setDuration(160).start();
        dx = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (dx <= 0) return;
        float d = getResources().getDisplayMetrics().density;
        float a = Math.min(1f, dx / trigger);
        float cx = Math.min(dx, trigger) / 2f + 4 * d, cy = getHeight() / 2f, r = 15 * d * (0.6f + 0.4f * a);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Pal.card);
        p.setAlpha((int) (255 * a));
        c.drawCircle(cx, cy, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(fired ? Pal.accent : Pal.dim);
        p.setAlpha((int) (255 * a));
        // Antwort-Pfeil
        arrow.reset();
        arrow.moveTo(cx - 1 * d, cy - 6 * d);
        arrow.lineTo(cx - 6 * d, cy - 1 * d);
        arrow.lineTo(cx - 1 * d, cy + 4 * d);
        c.drawPath(arrow, p);
        arrow.reset();
        arrow.moveTo(cx - 6 * d, cy - 1 * d);
        arrow.lineTo(cx + 2 * d, cy - 1 * d);
        arrow.quadTo(cx + 6 * d, cy - 1 * d, cx + 6 * d, cy + 5 * d);
        c.drawPath(arrow, p);
    }
}
