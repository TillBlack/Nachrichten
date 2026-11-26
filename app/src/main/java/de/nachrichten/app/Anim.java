package de.nachrichten.app;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * Kleine, einheitliche Animationen:
 *  - pop(): Knopf gibt beim Drücken leicht nach und federt beim Loslassen zurück.
 *  - popIn(): Fenster/Dialog ploppt weich auf statt abrupt zu erscheinen.
 * Läuft über StateListAnimator bzw. ViewPropertyAnimator auf dem Render-Thread,
 * blockiert keine Klicks und braucht keine eigenen Touch-Listener.
 */
final class Anim {

    /** Zeitpunkt, an dem zuletzt ein Dialog geschlossen wurde (für „Fenster neu öffnen" ohne Flackern). */
    static long lastDialogClose;
    private static java.lang.ref.WeakReference<View> lastPopView;
    private static long lastPopAt;

    /** Wird beim Schließen eines Dialogs aufgerufen. Kommt (wie bei Android üblich) erst kurz nach
     *  dem Öffnen eines Ersatz-Dialogs an – dann den neuen sofort fertig zeigen, statt ihn aufploppen zu lassen. */
    static void dialogClosed() {
        long now = System.currentTimeMillis();
        lastDialogClose = now;
        View v = lastPopView != null ? lastPopView.get() : null;
        if (v != null && now - lastPopAt < 150) {
            v.animate().cancel();
            v.setScaleX(1f); v.setScaleY(1f); v.setAlpha(1f);
        }
    }

    /** Drück-Feder für Knöpfe. depth = wie stark er beim Drücken schrumpft (0.95 = 5 %). */
    static void pop(View v, float depth) {
        if (v == null) return;
        ObjectAnimator dx = ObjectAnimator.ofFloat(v, View.SCALE_X, depth);
        ObjectAnimator dy = ObjectAnimator.ofFloat(v, View.SCALE_Y, depth);
        AnimatorSet down = new AnimatorSet();
        down.playTogether(dx, dy);
        down.setDuration(90);
        down.setInterpolator(new DecelerateInterpolator());

        ObjectAnimator ux = ObjectAnimator.ofFloat(v, View.SCALE_X, 1f);
        ObjectAnimator uy = ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f);
        AnimatorSet up = new AnimatorSet();
        up.playTogether(ux, uy);
        up.setDuration(260);
        up.setInterpolator(new OvershootInterpolator(2.6f));

        StateListAnimator sla = new StateListAnimator();
        sla.addState(new int[]{ android.R.attr.state_pressed }, down);
        sla.addState(new int[0], up);
        v.setStateListAnimator(sla);
    }

    static void pop(View v) { pop(v, 0.93f); }

    /** Alle anklickbaren Elemente in einem (kleinen) Baum mit der Drück-Feder versehen, z. B. in Dialogen. */
    static void popAll(View root) {
        if (root == null) return;
        popAll(root, root.getResources().getDisplayMetrics().widthPixels);
    }

    private static void popAll(View v, int screenW) {
        if (v.isClickable() && v.getStateListAnimator() == null && v.getWidth() > 0
                && !(v instanceof android.widget.EditText)) {
            // breite Zeilen nur ganz leicht, kleine Knöpfe deutlicher
            pop(v, v.getWidth() > screenW * 0.6f ? 0.985f : 0.93f);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) popAll(g.getChildAt(i), screenW);
        }
    }

    /** Hängt an einen Wurzel-View: nach jedem Layout (gebündelt) neue Knöpfe mit der Drück-Feder versehen. */
    static void autoPop(View root) {
        if (root == null) return;
        final boolean[] pending = { false };
        final Runnable walk = () -> { pending[0] = false; popAll(root); };
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (pending[0]) return;
            pending[0] = true;
            root.postDelayed(walk, 120);
        });
    }

    /** Weiches Aufploppen (leicht vergrößern + einblenden). */
    static void popIn(View v) {
        if (v == null) return;
        // Direkt nach dem Schließen eines anderen Fensters (z. B. Einstellungen neu aufbauen)
        // nicht noch einmal aufploppen – das würde flackern.
        if (System.currentTimeMillis() - lastDialogClose < 350) return;
        lastPopView = new java.lang.ref.WeakReference<>(v);
        lastPopAt = System.currentTimeMillis();
        popInNow(v);
    }

    /** Aufploppen ohne die „gerade ersetzt“-Prüfung (z. B. Tutorial-Karte bei jedem Schritt). */
    static void popInNow(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.setScaleX(0.86f);
        v.setScaleY(0.86f);
        v.setAlpha(0f);
        v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(230)
                .setInterpolator(new OvershootInterpolator(1.3f))
                .withLayer()
                .start();
    }

    /** Neu eingefügtes Element weich einblenden (von alpha 0, leicht verschoben um dy Pixel). */
    static void enter(View v, float dy) {
        if (v == null) return;
        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        v.setAlpha(0f);
        v.setTranslationY(dy);
        v.animate().alpha(1f).translationY(0f)
                .setDuration(200)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
    }

    /** Ausgeblendete Leiste weich einblenden; ist sie schon sichtbar, nichts tun (bricht ein Ausblenden ab). */
    static void reveal(View v, float dy) {
        if (v == null) return;
        if (v.getVisibility() == View.VISIBLE && v.getAlpha() >= 0.99f) {
            v.animate().cancel();
            v.setTranslationY(0f);
            return;
        }
        if (v.getVisibility() != View.VISIBLE) { enter(v, dy); return; }
        // sichtbar, aber gerade am Ausblenden → zurückholen
        v.animate().cancel();
        v.animate().alpha(1f).translationY(0f).setDuration(150).start();
    }

    /** Weich ausblenden und danach GONE (für schwebende Elemente, die kein Layout verschieben). */
    static void fadeOut(View v) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;
        v.animate().cancel();
        v.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            v.setVisibility(View.GONE);
            v.setAlpha(1f);
        }).start();
    }

    private Anim() {}
}
