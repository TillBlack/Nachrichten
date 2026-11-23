package de.nachrichten.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;

/** Gemeinsame Bausteine aller Bildschirme: Ränder, Farben, Texte, Dialoge. */
abstract class Base extends Activity {

    protected App app;
    protected FrameLayout root;
    protected int insetTop, insetBottom;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        app = (App) getApplication();
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false);
        else w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        root = new FrameLayout(this);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                insetTop = i.top;
                insetBottom = i.bottom;
                root.setPadding(i.left, 0, i.right, 0);
            } else {
                insetTop = in.getSystemWindowInsetTop();
                insetBottom = in.getSystemWindowInsetBottom();
                root.setPadding(in.getSystemWindowInsetLeft(), 0, in.getSystemWindowInsetRight(), 0);
            }
            onInsets();
            return in;
        });
        applyColors();
    }

    /** Wird aufgerufen, wenn sich Ränder (Statusleiste, Tastatur) ändern. */
    protected void onInsets() { }

    protected void applyColors() {
        root.setBackgroundColor(Pal.bg);
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Pal.bg));
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(Pal.light ? mask : 0, mask);
            }
        } else {
            int f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (Pal.light) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            w.getDecorView().setSystemUiVisibility(f);
        }
    }

    // ------------------------------------------------------------------ Bausteine

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    TextView label(String s) {
        TextView t = text(s, 13, Pal.dim, true);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    EditText field(String hint, boolean multi) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(Pal.text);
        e.setHintTextColor(Pal.dim);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setBackground(round(Pal.field, Pal.line, 10));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            e.setMinLines(2);
            e.setMaxLines(5);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            e.setSingleLine(true);
        }
        return e;
    }

    TextView button(String s, boolean primary) {
        TextView b = text(s, 15, primary ? Pal.onAccent : Pal.text, true);
        b.setPadding(dp(18), dp(11), dp(18), dp(11));
        b.setGravity(Gravity.CENTER);
        if (primary) b.setBackground(round(Pal.accent, 0, 10));
        return b;
    }

    TextView outlineButton(String s, int color) {
        TextView b = text(s, 15, color, true);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(0, Pal.line, 10));
        return b;
    }

    GradientDrawable round(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("code", s));
        if (Build.VERSION.SDK_INT < 33) toast(T.copied());
    }

    String time(long ts) {
        return DateFormat.getTimeInstance(DateFormat.SHORT, T.locale()).format(new Date(ts));
    }

    /** Kurze Zeit für die Liste: Uhrzeit heute, sonst Datum. */
    String shortWhen(long ts) {
        if (ts <= 0) return "";
        Calendar now = Calendar.getInstance(), c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) return time(ts);
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) return T.yesterday();
        return DateFormat.getDateInstance(DateFormat.SHORT, T.locale()).format(new Date(ts));
    }

    // ------------------------------------------------------------------ Dialoge

    Dialog dialog() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return d;
    }

    LinearLayout panel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(Pal.card, Pal.line, 16));
        box.setPadding(dp(20), dp(20), dp(20), dp(16));
        return box;
    }

    void showDialog(Dialog dlg, View content) {
        dlg.setContentView(content);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(480));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dlg.show();
    }

    /** Scrollbarer Dialog (für lange Inhalte). */
    void showScrollDialog(Dialog dlg, LinearLayout box) {
        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        showDialog(dlg, sc);
    }

    LinearLayout buttonRow() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        return r;
    }

    void addTo(LinearLayout row, View v, boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        if (!first) lp.setMargins(dp(8), 0, 0, 0);
        row.addView(v, lp);
    }

    void addRow(LinearLayout box, View row, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(topDp), 0, 0);
        box.addView(row, lp);
    }

    void confirm(String msg, String action, Runnable yes) {
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.addView(text(msg, 17, Pal.text, false));
        LinearLayout buttons = buttonRow();
        TextView no = button(T.cancel(), false);
        no.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, no, true);
        TextView ok = button(action, true);
        ok.setBackground(round(Pal.red, 0, 10));
        ok.setOnClickListener(v -> { dlg.dismiss(); yes.run(); });
        addTo(buttons, ok, false);
        addRow(box, buttons, 20);
        showDialog(dlg, box);
    }

    /** Einfache Auswahl-Liste. */
    void menu(String title, String[] items, int[] colors, java.util.function.IntConsumer pick) {
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.setPadding(dp(8), dp(12), dp(8), dp(8));
        if (title != null) {
            TextView t = text(title, 17, Pal.text, true);
            t.setPadding(dp(12), dp(4), dp(12), dp(8));
            t.setSingleLine(true);
            box.addView(t);
        }
        for (int i = 0; i < items.length; i++) {
            final int k = i;
            TextView it = text(items[i], 16, colors != null && colors[i] != 0 ? colors[i] : Pal.text, false);
            it.setPadding(dp(12), dp(14), dp(12), dp(14));
            it.setOnClickListener(v -> { dlg.dismiss(); pick.accept(k); });
            box.addView(it, new LinearLayout.LayoutParams(-1, -2));
        }
        showDialog(dlg, box);
    }

    // ------------------------------------------------------------------ Avatar

    /** Kreis mit Anfangsbuchstaben. */
    final class Avatar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        String letter;
        int color;

        Avatar(String pub, String name) {
            super(Base.this);
            color = Pal.avatar(pub);
            String n = name == null ? "" : name.trim();
            letter = n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(T.locale());
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            c.drawCircle(w / 2, h / 2, r, p);
            p.setColor(0xFFFFFFFF);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(r * 0.9f);
            p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(letter, w / 2, h / 2 - (fm.ascent + fm.descent) / 2, p);
        }
    }
}
