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

    // ------------------------------------------------------------------ Profil ansehen

    /** Profil eines Kontakts: Bild, Name, Pronomen, Biografie. */
    void showProfile(String pub, boolean withChatButton) {
        Db.Contact c = app.db.contact(pub);
        if (c == null) return;
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        Avatar av = new Avatar(pub, c.name == null || c.name.isEmpty() ? "?" : c.name, c.avatar).accessories(c.accBehind, c.accFront, c.accFrame);
        if (c.avatar != null) av.setOnClickListener(v -> bigImage(c.avatar));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(112), dp(112));
        ap.setMargins(0, dp(4), 0, dp(12));
        box.addView(av, ap);
        TextView n = text(app.nameOf(pub), 22, Pal.text, true);
        n.setGravity(Gravity.CENTER);
        box.addView(n, new LinearLayout.LayoutParams(-1, -2));
        if (c.pronouns != null && !c.pronouns.isEmpty()) {
            TextView pr = text(c.pronouns, 14, Pal.dim, false);
            pr.setGravity(Gravity.CENTER);
            pr.setPadding(0, dp(2), 0, 0);
            box.addView(pr, new LinearLayout.LayoutParams(-1, -2));
        }
        TextView bl = label(T.bio());
        box.addView(bl, new LinearLayout.LayoutParams(-1, -2));
        TextView bio = text(c.bio == null || c.bio.isEmpty() ? T.noBio() : c.bio, 15, c.bio == null || c.bio.isEmpty() ? Pal.dim : Pal.text, false);
        bio.setLineSpacing(0, 1.2f);
        bio.setTextIsSelectable(true);
        bio.setBackground(round(Pal.field, Pal.line, 10));
        bio.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.addView(bio, new LinearLayout.LayoutParams(-1, -2));
        if (c.tags != null && !c.tags.isEmpty()) {
            box.addView(label(T.tagsLabel()), new LinearLayout.LayoutParams(-1, -2));
            TextView tg = text(c.tags, 15, Pal.accent, true);
            tg.setTextIsSelectable(true);
            box.addView(tg, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout buttons = buttonRow();
        TextView close = button(T.close(), !withChatButton);
        close.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, close, true);
        if (withChatButton) {
            TextView chat = button(T.openChat(), true);
            chat.setOnClickListener(v -> {
                dlg.dismiss();
                startActivity(new android.content.Intent(this, ChatActivity.class).putExtra("peer", pub));
            });
            addTo(buttons, chat, false);
        }
        addRow(box, buttons, 18);
        showScrollDialog(dlg, box);
    }

    /** Avatar mit grünem Online-Punkt unten rechts (Discord-Stil). */
    View avatarWithDot(String pub, String name, String path, boolean online, int sizeDp) {
        return avatarWithDot(pub, name, path, online, sizeDp, -1, -1, -1);
    }

    View avatarWithDot(String pub, String name, String path, boolean online, int sizeDp, int ab, int af, int ar) {
        FrameLayout f = new FrameLayout(this);
        f.addView(new Avatar(pub, name, path).accessories(ab, af, ar), new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        if (online) {
            int ds = Math.max(10, Math.round(sizeDp * 0.30f));
            View dot = new View(this);
            dot.setBackground(round(0xFF3FBF6F, Pal.bg, ds / 2));
            f.addView(dot, new FrameLayout.LayoutParams(dp(ds), dp(ds), Gravity.BOTTOM | Gravity.RIGHT));
        }
        return f;
    }

    void bigImage(String path) {
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setBackgroundColor(0xFF000000);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setImageBitmap(avatarBitmap(path));
        iv.setOnClickListener(v -> d.dismiss());
        d.setContentView(iv);
        d.show();
    }

    // ------------------------------------------------------------------ Avatar

    private static final android.util.LruCache<String, android.graphics.Bitmap> AVATARS = new android.util.LruCache<>(64);

    /** Profilbild laden (mit Zwischenspeicher; der Pfad enthält den Zeitstempel). */
    static android.graphics.Bitmap avatarBitmap(String path) {
        android.graphics.Bitmap b = AVATARS.get(path);
        if (b == null) {
            try { b = android.graphics.BitmapFactory.decodeFile(path); } catch (Throwable ignored) { }
            if (b != null) AVATARS.put(path, b);
        }
        return b;
    }

    /** Kreis mit Anfangsbuchstaben. */
    final class Avatar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        String letter;
        int color;
        private android.graphics.Bitmap img;
        private final android.graphics.Path clip = new android.graphics.Path();

        /** Ohne Bild: neutrale Person statt Anfangsbuchstabe. */
        boolean silhouette = true;
        int accBehind = -1, accFront = -1, accFrame = -1;

        Avatar accessories(int b, int f, int fr) { accBehind = b; accFront = f; accFrame = fr; return this; }

        Avatar(String pub, String name) { this(pub, name, null); }

        Avatar(String pub, String name, String imagePath) {
            super(Base.this);
            color = Pal.avatar(pub);
            String n = name == null ? "" : name.trim();
            letter = n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(T.locale());
            if (imagePath != null) img = avatarBitmap(imagePath);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
            float rFull = Math.min(w, h) / 2f;
            boolean any = accBehind >= 0 || accFront >= 0 || accFrame >= 0;
            float r = accFrame >= 0 ? rFull * 0.60f : any ? rFull * 0.72f : rFull;
            Accessories.drawBehind(Base.this, c, cx, cy, rFull, r, accBehind, p);
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            if (img != null) {
                clip.reset();
                clip.addCircle(cx, cy, r, android.graphics.Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                android.graphics.RectF dst = new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r);
                p.setFilterBitmap(true);
                c.drawBitmap(img, null, dst, p);
                c.restore();
            } else if (silhouette) {
                p.setColor(0xFF000000);
                c.drawCircle(cx, cy, r, p);
                p.setColor(Pal.light ? 0xFFC9CCD0 : 0xFF3A3E42);
                c.save();
                clip.reset();
                clip.addCircle(cx, cy, r, android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                c.drawCircle(cx, cy - r * 0.18f, r * 0.36f, p);
                android.graphics.RectF body = new android.graphics.RectF(cx - r * 0.66f, cy + r * 0.3f, cx + r * 0.66f, cy + r * 1.62f);
                c.drawOval(body, p);
                c.restore();
            } else {
                p.setColor(color);
                c.drawCircle(cx, cy, r, p);
                p.setColor(0xFFFFFFFF);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(r * 0.9f);
                p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                Paint.FontMetrics fm = p.getFontMetrics();
                c.drawText(letter, cx, cy - (fm.ascent + fm.descent) / 2, p);
            }
            Accessories.drawFrame(Base.this, c, cx, cy, rFull, r, accFrame, p);
            Accessories.drawFront(Base.this, c, cx, cy, rFull, r, accFront, p);
        }
    }
}
