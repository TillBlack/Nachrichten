package de.nachrichten.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;

/**
 * Dialog, der beim Schließen weich ausblendet (leicht verkleinern + ausblenden,
 * Abdunklung dahinter blendet mit aus), statt schlagartig zu verschwinden.
 * Alle Wege zum Schließen (dismiss, cancel, Zurück, Tippen daneben) laufen hier durch.
 */
final class SmoothDialog extends Dialog {

    private static final int OUT_MS = 140;
    private boolean closing, done;
    private View animating;
    private static java.lang.ref.WeakReference<SmoothDialog> fading;

    /** Ein gerade ausblendendes Fenster sofort schließen (wenn ein neues direkt an seine Stelle tritt). */
    static void finishFadingNow() {
        SmoothDialog d = fading != null ? fading.get() : null;
        fading = null;
        if (d != null && d.closing && !d.done) {
            if (d.animating != null) d.animating.animate().cancel();
            d.finishDismiss();
        }
    }

    SmoothDialog(Context c) { super(c); }
    SmoothDialog(Context c, int theme) { super(c, theme); }

    @Override
    public void show() {
        closing = false;
        done = false;
        super.show();
    }

    @Override
    public void dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(this::dismiss);
            return;
        }
        if (closing) return;
        // Sofort merken – ein direkt danach geöffnetes Ersatzfenster ploppt dann nicht noch einmal auf.
        Anim.lastDialogClose = System.currentTimeMillis();

        Window w = getWindow();
        View content = null;
        if (w != null) {
            View root = w.getDecorView().findViewById(android.R.id.content);
            if (root instanceof ViewGroup && ((ViewGroup) root).getChildCount() > 0) content = ((ViewGroup) root).getChildAt(0);
        }
        Activity act = activity(getContext());
        if (!isShowing() || content == null || (act != null && (act.isFinishing() || act.isDestroyed()))) {
            finishDismiss();
            return;
        }
        closing = true;
        animating = content;
        fading = new java.lang.ref.WeakReference<>(this);

        // Abdunklung mit ausblenden
        try {
            final WindowManager.LayoutParams lp = w.getAttributes();
            float from = lp.dimAmount;
            if (from > 0f) {
                ValueAnimator va = ValueAnimator.ofFloat(from, 0f);
                va.setDuration(OUT_MS);
                va.addUpdateListener(an -> {
                    try { lp.dimAmount = (float) an.getAnimatedValue(); w.setAttributes(lp); } catch (Throwable ignored) { }
                });
                va.start();
            }
        } catch (Throwable ignored) { }

        content.animate().cancel();
        content.animate().scaleX(0.92f).scaleY(0.92f).alpha(0f)
                .setDuration(OUT_MS)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(this::finishDismiss)
                .start();
        // Sicherheitsnetz, falls die Animation abgebrochen wird (z. B. View losgelöst)
        new Handler(Looper.getMainLooper()).postDelayed(this::finishDismiss, OUT_MS + 120);
    }

    private void finishDismiss() {
        if (done) return;
        done = true;
        animating = null;
        try { super.dismiss(); } catch (Throwable ignored) { }
    }

    private static Activity activity(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}
