package de.nachrichten.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Einführung über die Hauptseite: dunkler Schleier mit Loch über dem Knopf, Erklärkarte und Weiter/Zurück. */
final class TutorialOverlay extends FrameLayout {

    static final class Step {
        final View target;
        final String text;
        Step(View target, String text) { this.target = target; this.text = text; }
    }

    private final Base a;
    private final List<Step> steps;
    private final Runnable onDone;
    private final Dim dim;
    private final LinearLayout card;
    private final TextView body, back, next;
    private int idx;

    TutorialOverlay(Base a, List<Step> steps, Runnable onDone) {
        super(a);
        this.a = a;
        this.steps = steps;
        this.onDone = onDone;
        setClickable(true);

        dim = new Dim(a);
        addView(dim, new LayoutParams(-1, -1));

        card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(a.round(Pal.card, Pal.accent, 14));
        card.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(10));
        card.setElevation(a.dp(10));
        body = a.text("", 15, Pal.text, false);
        body.setLineSpacing(0, 1.25f);
        card.addView(body);

        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView skip = a.text(T.tSkip(), 14, Pal.dim, false);
        skip.setPadding(0, a.dp(12), 0, a.dp(4));
        skip.setOnClickListener(v -> finish());
        row.addView(skip, new LinearLayout.LayoutParams(0, -2, 1f));
        back = a.button(T.tBack(), false);
        back.setOnClickListener(v -> show(idx - 1));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.setMargins(a.dp(6), a.dp(8), 0, 0);
        row.addView(back, bp);
        next = a.button(T.tNext(), true);
        next.setOnClickListener(v -> { if (idx >= steps.size() - 1) finish(); else show(idx + 1); });
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.setMargins(a.dp(8), a.dp(8), 0, 0);
        row.addView(next, np);
        card.addView(row);

        int w = Math.min(a.getResources().getDisplayMetrics().widthPixels - a.dp(32), a.dp(420));
        LayoutParams cp = new LayoutParams(w, LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        addView(card, cp);

        post(() -> show(0));
    }

    private final RectF hole = new RectF();

    private void show(int i) {
        if (i < 0 || i >= steps.size()) return;
        idx = i;
        Step s = steps.get(i);
        body.setText(s.text);
        back.setEnabled(i > 0);
        back.setAlpha(i > 0 ? 1f : 0.4f);
        next.setText(i >= steps.size() - 1 ? T.tDone() : T.tNext());

        int[] me = new int[2], tt = new int[2];
        getLocationInWindow(me);
        s.target.getLocationInWindow(tt);
        float pad = a.dp(8);
        float l = tt[0] - me[0] - pad, t = tt[1] - me[1] - pad;
        hole.set(l, t, l + s.target.getWidth() + pad * 2, t + s.target.getHeight() + pad * 2);
        dim.invalidate();

        // Karte über oder unter das Ziel setzen, je nachdem wo mehr Platz ist
        card.post(() -> {
            int ch = card.getHeight();
            float below = hole.bottom + a.dp(14);
            float above = hole.top - a.dp(14) - ch;
            float y;
            if (hole.centerY() < getHeight() / 2f) y = below;
            else y = above;
            y = Math.max(a.dp(12), Math.min(y, getHeight() - ch - a.dp(12)));
            card.setTranslationY(y);
        });
    }

    private void finish() {
        if (onDone != null) onDone.run();
        if (getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) getParent()).removeView(this);
    }

    /** Dunkler Schleier mit ausgespartem Loch und Ring um das Ziel. */
    private final class Dim extends View {
        private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

        Dim(android.content.Context c) {
            super(c);
            clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(a.dp(2));
            ring.setColor(Pal.accent);
        }

        @Override
        protected void onDraw(Canvas c) {
            int save = c.saveLayer(0, 0, getWidth(), getHeight(), null);
            c.drawColor(0xCC000000);
            if (!hole.isEmpty()) {
                float rad = Math.min(hole.width(), hole.height()) / 2f;
                c.drawRoundRect(hole, rad, rad, clear);
                c.drawRoundRect(hole, rad, rad, ring);
            }
            c.restoreToCount(save);
        }
    }
}
