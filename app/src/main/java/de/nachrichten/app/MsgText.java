package de.nachrichten.app;

import android.content.Context;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.widget.TextView;

/**
 * Text einer Nachricht. Misst sich immer mit voller Höhe (nichts wird abgeschnitten)
 * und öffnet Links beim Antippen, ohne langes Drücken oder Wischen zu blockieren.
 */
final class MsgText extends TextView {

    private final int maxW;
    private ClickableSpan pressed;

    MsgText(Context c, int maxWidth) {
        super(c);
        maxW = maxWidth;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int mode = MeasureSpec.getMode(widthSpec), size = MeasureSpec.getSize(widthSpec);
        int limit = mode == MeasureSpec.UNSPECIFIED ? maxW : Math.min(maxW, size);
        // erst die natürliche Breite (höchstens limit) bestimmen …
        super.onMeasure(MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int w = Math.min(getMeasuredWidth(), limit);
        if (mode == MeasureSpec.EXACTLY) w = Math.min(size, maxW);
        // … dann mit genau dieser Breite und unbegrenzter Höhe neu messen
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        CharSequence t = getText();
        if (!(t instanceof Spannable)) return super.onTouchEvent(e);
        Spannable s = (Spannable) t;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
            ClickableSpan span = spanAt(s, e);
            if (action == MotionEvent.ACTION_DOWN) {
                pressed = span;
                if (span != null) Selection.setSelection(s, s.getSpanStart(span), s.getSpanEnd(span));
            } else {
                if (span != null && span == pressed) span.onClick(this);
                pressed = null;
                Selection.removeSelection(s);
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            pressed = null;
            Selection.removeSelection(s);
        }
        // Rest (langes Drücken) normal behandeln
        return super.onTouchEvent(e);
    }

    private ClickableSpan spanAt(Spannable s, MotionEvent e) {
        Layout l = getLayout();
        if (l == null) return null;
        int x = (int) e.getX() - getTotalPaddingLeft() + getScrollX();
        int y = (int) e.getY() - getTotalPaddingTop() + getScrollY();
        int line = l.getLineForVertical(y);
        if (x < l.getLineLeft(line) || x > l.getLineRight(line)) return null;
        int off = l.getOffsetForHorizontal(line, x);
        ClickableSpan[] spans = s.getSpans(off, off, ClickableSpan.class);
        return spans.length > 0 ? spans[0] : null;
    }
}
