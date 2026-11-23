package de.nachrichten.app;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.File;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Start: Einrichtung beim ersten Mal, danach die Chatliste. */
public class MainActivity extends Base implements Net.Listener {

    private static final int REQ_SCAN = 1, REQ_NOTIF = 2;

    private LinearLayout header, list;
    private ScrollView scroll;
    private TextView statusView;
    private Ico fab;
    private boolean onboarding;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (app.keys.has()) buildList(); else buildOnboarding();
        File crash = new File(getFilesDir(), "crash.txt");
        if (crash.exists()) {
            try {
                String s = new String(java.nio.file.Files.readAllBytes(crash.toPath()));
                crash.delete();
                showCrash(s);
            } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (app.keys.has()) {
            app.net.addListener(this);
            app.net.acquire("ui");
            app.applyBackground();
            refresh();
            Updater.checkOnStart(this);
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        app.net.removeListener(this);
        app.net.release("ui");
    }

    @Override
    protected void onInsets() {
        if (onboarding || header == null) {
            if (root.getChildCount() > 0) root.getChildAt(0).setPadding(dp(24), insetTop + dp(40), dp(24), insetBottom + dp(24));
            return;
        }
        header.setPadding(dp(20), insetTop + dp(14), dp(10), dp(8));
        list.setPadding(dp(8), dp(4), dp(8), insetBottom + dp(100));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fab.getLayoutParams();
        lp.setMargins(0, 0, dp(22), insetBottom + dp(22));
        fab.setLayoutParams(lp);
    }

    // ------------------------------------------------------------------ Einrichtung

    private void buildOnboarding() {
        onboarding = true;
        root.removeAllViews();
        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        sc.addView(box);
        sc.setPadding(dp(24), insetTop + dp(40), dp(24), insetBottom + dp(24));
        sc.setClipToPadding(false);
        root.addView(sc, new FrameLayout.LayoutParams(-1, -1));

        Ico lock = new Ico(this, Ico.LOCK);
        lock.color = Pal.accent;
        lock.setScaleX(3f);
        lock.setScaleY(3f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
        lp.setMargins(dp(8), dp(8), 0, dp(20));
        box.addView(lock, lp);
        box.addView(text(T.welcome(), 30, Pal.text, true));
        TextView info = text(T.welcomeText(), 16, Pal.dim, false);
        info.setLineSpacing(0, 1.25f);
        info.setPadding(0, dp(10), 0, dp(8));
        box.addView(info);

        box.addView(label(T.yourName()));
        EditText name = field(T.yourName(), false);
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView ni = text(T.nameInfo(), 13, Pal.dim, false);
        ni.setPadding(0, dp(6), 0, 0);
        box.addView(ni);

        TextView go = button(T.start(), true);
        go.setOnClickListener(v -> {
            app.prefs.edit().putString("name", name.getText().toString().trim()).apply();
            app.keys.create();
            finishOnboarding();
        });
        addRow(box, go, 28);

        TextView rest = outlineButton(T.restore(), Pal.text);
        rest.setOnClickListener(v -> restoreDialog(name));
        addRow(box, rest, 12);
    }

    private void restoreDialog(EditText nameField) {
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.addView(text(T.restore(), 19, Pal.text, true));
        box.addView(label(T.backup()));
        EditText key = field(T.restoreHint(), false);
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        box.addView(key, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout buttons = buttonRow();
        TextView cancel = button(T.cancel(), false);
        cancel.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, cancel, true);
        TextView ok = button(T.restore().split(" ")[0], true);
        ok.setText(T.start());
        ok.setOnClickListener(v -> {
            if (!app.keys.restore(key.getText().toString())) { toast(T.invalidKey()); return; }
            app.prefs.edit().putString("name", nameField.getText().toString().trim()).apply();
            dlg.dismiss();
            finishOnboarding();
        });
        addTo(buttons, ok, false);
        addRow(box, buttons, 20);
        showDialog(dlg, box);
    }

    private void finishOnboarding() {
        onboarding = false;
        buildList();
        root.requestApplyInsets();
        app.net.addListener(this);
        app.net.acquire("ui");
        askNotifications();
        app.applyBackground();
        refresh();
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !Notifs.allowed(this)) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }
    }

    // ------------------------------------------------------------------ Chatliste

    private void buildList() {
        onboarding = false;
        root.removeAllViews();
        applyColors();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text(T.chats(), 28, Pal.text, true));
        statusView = text("", 13, Pal.dim, false);
        titles.addView(statusView);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        Ico qr = new Ico(this, Ico.QR);
        qr.setContentDescription(T.myCode());
        qr.setOnClickListener(v -> showMyCode());
        header.addView(qr, new LinearLayout.LayoutParams(dp(48), dp(48)));
        Ico set = new Ico(this, Ico.SETTINGS);
        set.setContentDescription(T.settings());
        set.setOnClickListener(v -> openSettings());
        header.addView(set, new LinearLayout.LayoutParams(dp(48), dp(48)));
        col.addView(header);

        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        fab = new Ico(this, Ico.PLUS);
        fab.setElevation(dp(6));
        fab.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        fab.setContentDescription(T.addContact());
        fab.setOnClickListener(v -> addContactMenu());
        root.addView(fab, new FrameLayout.LayoutParams(dp(62), dp(62), Gravity.BOTTOM | Gravity.RIGHT));
        onInsets();
    }

    @Override
    public void onChanged(String peer) { refresh(); }

    @Override
    public void onStatus() { updateStatus(); }

    private void updateStatus() {
        if (statusView == null) return;
        int n = app.net.connectedCount(), total = app.relays().size();
        statusView.setText(n == 0 ? T.offline() : T.relaysConnected(n, total));
    }

    private void refresh() {
        if (list == null || onboarding) return;
        updateStatus();
        list.removeAllViews();
        List<Db.Contact> cs = app.db.contacts();
        if (cs.isEmpty()) {
            TextView e = text(T.noChats(), 16, Pal.dim, false);
            e.setGravity(Gravity.CENTER);
            e.setLineSpacing(0, 1.25f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(120), 0, 0);
            list.addView(e, lp);
            return;
        }
        for (Db.Contact c : cs) list.addView(row(c));
    }

    private View row(Db.Contact c) {
        String name = app.nameOf(c.pub);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setBackground(round(Pal.bg, 0, 12));
        Avatar av = new Avatar(c.pub, c.name == null || c.name.isEmpty() ? "?" : c.name);
        r.addView(av, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(14), 0, dp(8), 0);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView nv = text(name, 17, Pal.text, true);
        nv.setSingleLine(true);
        nv.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(nv, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView tv = text(c.lastText != null ? shortWhen(c.lastTs) : "", 12, c.unread > 0 ? Pal.accent : Pal.dim, false);
        top.addView(tv);
        mid.addView(top);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        String lt = c.lastText == null ? null
                : c.lastKind == Db.IMAGE ? T.photo()
                : c.lastKind == Db.FILE ? T.fileLabel() + ": " + c.lastText
                : c.lastText.replace('\n', ' ');
        String preview = lt == null ? T.noMessagesYet() : (c.lastOutgoing ? T.you() : "") + lt;
        TextView pv = text(preview, 14, Pal.dim, false);
        pv.setSingleLine(true);
        pv.setEllipsize(TextUtils.TruncateAt.END);
        bottom.addView(pv, new LinearLayout.LayoutParams(0, -2, 1f));
        if (c.unread > 0) {
            TextView badge = text(c.unread > 99 ? "99+" : String.valueOf(c.unread), 12, Pal.onAccent, true);
            badge.setGravity(Gravity.CENTER);
            badge.setMinWidth(dp(22));
            badge.setPadding(dp(6), dp(1), dp(6), dp(1));
            badge.setBackground(round(Pal.accent, 0, 11));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, dp(22));
            bp.setMargins(dp(8), 0, 0, 0);
            bottom.addView(badge, bp);
        }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.setMargins(0, dp(3), 0, 0);
        mid.addView(bottom, blp);
        r.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));

        r.setOnClickListener(v -> openChat(c.pub));
        r.setOnLongClickListener(v -> {
            menu(name, new String[]{T.rename(), T.deleteChat()}, new int[]{0, Pal.red}, i -> {
                if (i == 0) renameDialog(c.pub, this::refresh);
                else confirm(T.deleteChatQ(), T.delete(), () -> {
                    for (String p : app.db.deleteContact(c.pub)) new File(p).delete();
                    refresh();
                });
            });
            return true;
        });
        return r;
    }

    private void openChat(String pub) {
        startActivity(new Intent(this, ChatActivity.class).putExtra("peer", pub));
    }

    void renameDialog(String pub, Runnable after) {
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.addView(text(T.rename(), 19, Pal.text, true));
        box.addView(label(T.name()));
        EditText name = field(T.name(), false);
        Db.Contact c = app.db.contact(pub);
        if (c != null) name.setText(c.name);
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout buttons = buttonRow();
        TextView cancel = button(T.cancel(), false);
        cancel.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, cancel, true);
        TextView ok = button(T.save(), true);
        ok.setOnClickListener(v -> {
            app.db.rename(pub, name.getText().toString().trim());
            dlg.dismiss();
            after.run();
        });
        addTo(buttons, ok, false);
        addRow(box, buttons, 20);
        showDialog(dlg, box);
    }

    // ------------------------------------------------------------------ Kontakt hinzufügen

    private void addContactMenu() {
        menu(T.addContact(), new String[]{T.scanQr(), T.enterCode()}, null, i -> {
            if (i == 0) startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
            else enterCodeDialog(null, "");
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SCAN && res == RESULT_OK && data != null) {
            String code = data.getStringExtra("code");
            String[] parsed = Keys.parseContact(code);
            if (parsed == null) { toast(T.invalidCode()); return; }
            enterCodeDialog(code, parsed[1]);
        }
    }

    /** Code (eingegeben oder gescannt) bestätigen und Namen vergeben. */
    private void enterCodeDialog(String presetCode, String presetName) {
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.addView(text(T.addContact(), 19, Pal.text, true));
        EditText code = null;
        if (presetCode == null) {
            box.addView(label(T.textCode()));
            code = field(T.codeHint(), false);
            code.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            code.setTypeface(Typeface.MONOSPACE);
            box.addView(code, new LinearLayout.LayoutParams(-1, -2));
        }
        box.addView(label(T.name()));
        EditText name = field(T.name(), false);
        name.setText(presetName);
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout buttons = buttonRow();
        TextView cancel = button(T.cancel(), false);
        cancel.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, cancel, true);
        TextView ok = button(T.add(), true);
        final EditText codeField = code;
        ok.setOnClickListener(v -> {
            String raw = presetCode != null ? presetCode : codeField.getText().toString();
            String[] parsed = Keys.parseContact(raw);
            if (parsed == null) { toast(T.invalidCode()); return; }
            if (parsed[0].equals(app.keys.pubHex())) { toast(T.ownCode()); return; }
            String n = name.getText().toString().trim();
            if (n.isEmpty()) n = parsed[1];
            Db.Contact existing = app.db.contact(parsed[0]);
            if (existing == null) app.db.addContact(parsed[0], n);
            else if (!n.isEmpty()) app.db.rename(parsed[0], n);
            dlg.dismiss();
            refresh();
            openChat(parsed[0]);
        });
        addTo(buttons, ok, false);
        addRow(box, buttons, 20);
        showDialog(dlg, box);
        (code != null ? code : name).requestFocus();
    }

    // ------------------------------------------------------------------ Eigener Code

    static Bitmap qrBitmap(String content, int size, int dark, int lightColor) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints);
            int n = m.getWidth(), scale = Math.max(1, size / n), px = n * scale;
            int[] pixels = new int[px * px];
            for (int y = 0; y < px; y++) for (int x = 0; x < px; x++)
                pixels[y * px + x] = m.get(x / scale, y / scale) ? dark : lightColor;
            return Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }

    /** Zeigt QR-Code und Textcode für einen Schlüssel. */
    void showCode(String title, String qrContent, String textCode, String subtitle) {
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView t = text(title, 20, Pal.text, true);
        t.setGravity(Gravity.CENTER);
        box.addView(t, new LinearLayout.LayoutParams(-1, -2));
        if (subtitle != null) {
            TextView st = text(subtitle, 14, Pal.dim, false);
            st.setGravity(Gravity.CENTER);
            st.setPadding(0, dp(6), 0, 0);
            box.addView(st, new LinearLayout.LayoutParams(-1, -2));
        }
        int size = Math.min(getResources().getDisplayMetrics().widthPixels - dp(120), dp(280));
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(qrBitmap(qrContent, size, 0xFF000000, 0xFFFFFFFF));
        iv.setBackground(round(0xFFFFFFFF, 0, 12));
        iv.setPadding(dp(10), dp(10), dp(10), dp(10));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(size, size);
        qp.setMargins(0, dp(16), 0, dp(14));
        box.addView(iv, qp);

        TextView lt = label(T.textCode());
        lt.setPadding(0, 0, 0, dp(4));
        box.addView(lt, new LinearLayout.LayoutParams(-1, -2));
        TextView code = text(textCode, 14, Pal.text, false);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextIsSelectable(true);
        code.setBackground(round(Pal.field, Pal.line, 10));
        code.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.addView(code, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout buttons = buttonRow();
        TextView close = button(T.close(), false);
        close.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, close, true);
        TextView share = button(T.share(), false);
        share.setOnClickListener(v -> {
            Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, T.shareText() + "\n" + textCode);
            startActivity(Intent.createChooser(s, T.share()));
        });
        addTo(buttons, share, false);
        TextView cp = button(T.copy(), true);
        cp.setOnClickListener(v -> copy(textCode));
        addTo(buttons, cp, false);
        addRow(box, buttons, 18);
        showScrollDialog(dlg, box);
    }

    private void showMyCode() {
        String name = app.myName();
        showCode(T.myCode(), app.keys.qrContent(name), app.keys.npub(), (name.isEmpty() ? "" : name + "\n") + T.myCodeInfo());
    }

    // ------------------------------------------------------------------ Einstellungen

    private void openSettings() {
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.addView(text(T.settings(), 20, Pal.text, true));

        box.addView(label(T.yourName()));
        EditText name = field(T.yourName(), false);
        name.setText(app.myName());
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        dlg.setOnDismissListener(d -> app.prefs.edit().putString("name", name.getText().toString().trim()).apply());

        box.addView(label(T.language()));
        for (int i = 0; i < T.NAMES.length; i++) {
            final int k = i;
            box.addView(optionRow(T.NAMES[i], T.lang == i, v -> {
                T.lang = k;
                app.prefs.edit().putInt("lang", k).apply();
                Notifs.channels(this);
                dlg.dismiss();
                buildList();
                root.requestApplyInsets();
                refresh();
                openSettings();
            }));
        }

        box.addView(label(T.theme()));
        LinearLayout seg = new LinearLayout(this);
        TextView dark = chip(T.dark(), !Pal.light), light = chip(T.light(), Pal.light);
        View.OnClickListener themeClick = v -> {
            boolean wantLight = v == light;
            if (wantLight == Pal.light) return;
            Pal.set(wantLight);
            app.prefs.edit().putBoolean("light", wantLight).apply();
            dlg.dismiss();
            buildList();
            root.requestApplyInsets();
            refresh();
            openSettings();
        };
        dark.setOnClickListener(themeClick);
        light.setOnClickListener(themeClick);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1f);
        sp.setMargins(0, 0, dp(8), 0);
        seg.addView(dark, sp);
        seg.addView(light, new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(seg, new LinearLayout.LayoutParams(-1, -2));

        // Hintergrund
        LinearLayout bg = new LinearLayout(this);
        bg.setGravity(Gravity.CENTER_VERTICAL);
        bg.setPadding(0, dp(18), 0, 0);
        bg.addView(text(T.background(), 16, Pal.text, false), new LinearLayout.LayoutParams(0, -2, 1f));
        Ico tg = new Ico(this, Ico.TOGGLE);
        tg.on = app.background();
        bg.addView(tg, new LinearLayout.LayoutParams(dp(52), dp(40)));
        bg.setOnClickListener(v -> {
            boolean on = !app.background();
            app.prefs.edit().putBoolean("bg", on).apply();
            tg.on = on;
            tg.invalidate();
            if (on) askNotifications();
            app.applyBackground();
        });
        tg.setOnClickListener(v -> bg.performClick());
        box.addView(bg);
        TextView bgi = text(T.backgroundInfo(), 13, Pal.dim, false);
        bgi.setPadding(0, dp(4), 0, 0);
        box.addView(bgi);
        TextView hide = outlineButton(T.hideStatus(), Pal.text);
        hide.setOnClickListener(v -> {
            Intent i = new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())
                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, Notifs.CH_SERVICE);
            try { startActivity(i); } catch (Exception e) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
                } catch (Exception ignored) { }
            }
        });
        addRow(box, hide, 10);
        TextView hidei = text(T.hideStatusInfo(), 13, Pal.dim, false);
        hidei.setPadding(0, dp(6), 0, 0);
        box.addView(hidei);

        // Updates
        LinearLayout up = new LinearLayout(this);
        up.setGravity(Gravity.CENTER_VERTICAL);
        up.setPadding(0, dp(18), 0, 0);
        up.addView(text(T.autoUpdate(), 16, Pal.text, false), new LinearLayout.LayoutParams(0, -2, 1f));
        Ico updToggle = new Ico(this, Ico.TOGGLE);
        updToggle.on = app.prefs.getBoolean("updates", true);
        up.addView(updToggle, new LinearLayout.LayoutParams(dp(52), dp(40)));
        up.setOnClickListener(v -> {
            boolean on = !app.prefs.getBoolean("updates", true);
            app.prefs.edit().putBoolean("updates", on).apply();
            updToggle.on = on;
            updToggle.invalidate();
        });
        updToggle.setOnClickListener(v -> up.performClick());
        box.addView(up);
        TextView uti = text(T.autoUpdateInfo(Updater.installedVersion(this)), 13, Pal.dim, false);
        uti.setPadding(0, dp(4), 0, 0);
        box.addView(uti);

        // Relays
        box.addView(label(T.relays()));
        TextView ri = text(T.relaysInfo(), 13, Pal.dim, false);
        ri.setPadding(0, 0, 0, dp(8));
        box.addView(ri);
        for (String u : app.relays()) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(6), 0, dp(6));
            View dot = new View(this);
            dot.setBackground(round(app.net.isConnected(u) ? Pal.accent : Pal.line, 0, 5));
            LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(dp(10), dp(10));
            dp1.setMargins(0, 0, dp(10), 0);
            r.addView(dot, dp1);
            TextView ut = text(u.replace("wss://", ""), 15, Pal.text, false);
            ut.setSingleLine(true);
            ut.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(ut, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView del = text(T.delete(), 14, Pal.red, false);
            del.setPadding(dp(10), dp(6), 0, dp(6));
            del.setOnClickListener(v -> {
                List<String> rs = app.relays();
                if (rs.size() <= 1) return;
                rs.remove(u);
                app.setRelays(rs);
                dlg.dismiss();
                openSettings();
            });
            r.addView(del);
            box.addView(r);
        }
        LinearLayout addR = new LinearLayout(this);
        addR.setGravity(Gravity.CENTER_VERTICAL);
        EditText relay = field(T.relayHint(), false);
        relay.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        addR.addView(relay, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView addB = button(T.add(), true);
        addB.setOnClickListener(v -> {
            String u = relay.getText().toString().trim();
            if (!u.startsWith("wss://") && !u.startsWith("ws://")) u = "wss://" + u;
            while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            if (u.length() < 10) return;
            List<String> rs = app.relays();
            if (!rs.contains(u)) rs.add(u);
            app.setRelays(rs);
            dlg.dismiss();
            openSettings();
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.setMargins(dp(8), 0, 0, 0);
        addR.addView(addB, ap);
        addRow(box, addR, 6);
        TextView reset = text(T.resetRelays(), 14, Pal.accent, false);
        reset.setPadding(0, dp(10), 0, dp(4));
        reset.setOnClickListener(v -> {
            app.setRelays(new java.util.ArrayList<>(java.util.Arrays.asList(Net.DEFAULT_RELAYS)));
            dlg.dismiss();
            openSettings();
        });
        box.addView(reset);

        // Sicherung
        box.addView(label(T.backup()));
        TextView bi = text(T.backupInfo(), 13, Pal.dim, false);
        bi.setPadding(0, 0, 0, dp(8));
        box.addView(bi);
        TextView show = outlineButton(T.showKey(), Pal.red);
        show.setOnClickListener(v -> {
            Dialog k = dialog();
            LinearLayout kb = panel();
            kb.addView(text(T.backup(), 19, Pal.text, true));
            TextView warn = text(T.backupInfo(), 14, Pal.red, false);
            warn.setPadding(0, dp(8), 0, dp(12));
            kb.addView(warn);
            TextView ks = text(app.keys.nsec(), 14, Pal.text, false);
            ks.setTypeface(Typeface.MONOSPACE);
            ks.setTextIsSelectable(true);
            ks.setBackground(round(Pal.field, Pal.line, 10));
            ks.setPadding(dp(12), dp(10), dp(12), dp(10));
            kb.addView(ks);
            LinearLayout kbtn = buttonRow();
            TextView kc = button(T.close(), false);
            kc.setOnClickListener(x -> k.dismiss());
            addTo(kbtn, kc, true);
            TextView kcopy = button(T.copy(), true);
            kcopy.setOnClickListener(x -> copy(app.keys.nsec()));
            addTo(kbtn, kcopy, false);
            addRow(kb, kbtn, 18);
            showDialog(k, kb);
        });
        box.addView(show, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout buttons = buttonRow();
        TextView close = button(T.close(), true);
        close.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, close, true);
        addRow(box, buttons, 20);
        showScrollDialog(dlg, box);
    }

    private View optionRow(String name, boolean selected, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        Ico radio = new Ico(this, Ico.RADIO);
        radio.on = selected;
        r.addView(radio, new LinearLayout.LayoutParams(dp(36), dp(42)));
        r.addView(text(name, 16, Pal.text, selected), new LinearLayout.LayoutParams(0, -2, 1f));
        r.setOnClickListener(click);
        return r;
    }

    private TextView chip(String s, boolean selected) {
        TextView c = text(s, 15, selected ? Pal.onAccent : Pal.text, selected);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        c.setBackground(round(selected ? Pal.accent : Pal.field, selected ? 0 : Pal.line, 10));
        return c;
    }

    private void showCrash(String s) {
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.addView(text("Crash report", 18, Pal.text, true));
        TextView t = text(s.length() > 3000 ? s.substring(0, 3000) : s, 11, Pal.dim, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        box.addView(t);
        LinearLayout buttons = buttonRow();
        TextView cp = button(T.copy(), true);
        cp.setOnClickListener(v -> copy(s));
        addTo(buttons, cp, true);
        addRow(box, buttons, 12);
        showScrollDialog(dlg, box);
    }
}
