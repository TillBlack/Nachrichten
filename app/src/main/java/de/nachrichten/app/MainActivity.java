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

    private static final int REQ_SCAN = 1, REQ_NOTIF = 2, REQ_AVATAR = 3;
    private Dialog settingsDlg;

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
            if (app.amBanned() && !app.isDev()) { startActivity(new Intent(this, BanActivity.class)); return; }
            app.applyBackground();
            refresh();
            app.net.warmDirectory(() -> {
                if (app.net.directoryCount() != shownDirCount) { shownDirCount = app.net.directoryCount(); refresh(); }
            });
            if (ChatActivity.justLeft) {
                ChatActivity.justLeft = false;
                Updater.checkAfterChat(this);
            } else {
                Updater.checkOnStart(this);
            }
            showObtainiumChip();
            if (!askDirectoryConsent() && !Updater.obtainium(this) && !app.prefs.getBoolean("obtainiumFirstAsked", false)) {
                app.prefs.edit().putBoolean("obtainiumFirstAsked", true).apply();
                Updater.offerHelper(this, null, 0, true);
            }
            app.net.publishDirectory(false);
            root.post(this::maybeTutorial);
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
        if (obtChip != null) {
            FrameLayout.LayoutParams cl = (FrameLayout.LayoutParams) obtChip.getLayoutParams();
            cl.setMargins(dp(16), 0, 0, insetBottom + dp(31));
            obtChip.setLayoutParams(cl);
        }
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
            app.prefs.edit().putString("name", name.getText().toString().trim())
                    .putLong("profileTs", name.getText().toString().trim().isEmpty() ? 0 : System.currentTimeMillis()).apply();
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
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        Ico qr = new Ico(this, Ico.QR);
        qr.setContentDescription(T.myCode());
        qr.setOnClickListener(v -> showMyCode());
        header.addView(qr, new LinearLayout.LayoutParams(dp(48), dp(48)));
        hQr = qr;
        Ico shop = new Ico(this, Ico.BAG);
        shop.setContentDescription(T.accessories());
        shop.setOnClickListener(v -> startActivity(new Intent(this, ShopActivity.class)));
        header.addView(shop, new LinearLayout.LayoutParams(dp(48), dp(48)));
        hShop = shop;
        Ico share = new Ico(this, Ico.SHARE);
        share.setContentDescription(T.shareApp());
        share.setOnClickListener(v -> shareApp());
        header.addView(share, new LinearLayout.LayoutParams(dp(48), dp(48)));
        hShare = share;
        Ico set = new Ico(this, Ico.SETTINGS);
        set.setContentDescription(T.settings());
        set.setOnClickListener(v -> openSettings());
        header.addView(set, new LinearLayout.LayoutParams(dp(48), dp(48)));
        hSet = set;
        java.io.File mine = app.myAvatar();
        Avatar me = new Avatar(app.keys.has() ? app.keys.pubHex() : "x", "?", mine != null ? mine.getAbsolutePath() : null)
                .accessories(app.accBehind(), app.accFront(), app.accFrame());
        me.setContentDescription(T.profile());
        me.setOnClickListener(v -> editProfile());
        FrameLayout meBox = new FrameLayout(this);
        meBox.addView(me, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
        meBox.setOnClickListener(v -> editProfile());
        header.addView(meBox, new LinearLayout.LayoutParams(dp(48), dp(48)));
        hProfile = meBox;
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

    private View hint, obtChip, hQr, hShop, hShare, hSet, hProfile;
    private int shownDirCount = -2;
    private boolean tutorialShown;

    /** Dauerhafter kleiner Hinweis unten links, solange Obtainium fehlt. */
    private void showObtainiumChip() {
        if (obtChip != null) { root.removeView(obtChip); obtChip = null; }
        if (onboarding || fab == null || Updater.obtainium(this) || !Updater.enabled(this)) return;
        TextView c = text(T.getObtainiumChip(), 13, Pal.text, true);
        c.setPadding(dp(14), dp(10), dp(14), dp(10));
        c.setBackground(round(Pal.card, Pal.line, 20));
        c.setElevation(dp(4));
        c.setOnClickListener(v -> Updater.offerHelper(this, null, 0));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        lp.setMargins(dp(16), 0, 0, insetBottom + dp(31));
        root.addView(c, lp);
        obtChip = c;
    }

    /** Kleiner Update-Hinweis unten rechts, verschwindet nach 3 Sekunden von selbst. */
    void updateHint(long version, Runnable open) {
        if (onboarding || fab == null) return;
        if (hint != null) root.removeView(hint);
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setBackground(round(Pal.card, Pal.accent, 12));
        h.setPadding(dp(14), dp(10), dp(14), dp(10));
        h.setElevation(dp(8));
        h.addView(text(T.updateTitle() + " (" + version + ")", 14, Pal.text, true));
        h.addView(text(T.tapToUpdate(), 12, Pal.dim, false));
        h.setOnClickListener(v -> {
            root.removeView(h);
            hint = null;
            open.run();
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
        lp.setMargins(0, 0, dp(16), insetBottom + dp(96));
        root.addView(h, lp);
        hint = h;
        h.setAlpha(0f);
        h.setTranslationY(dp(16));
        h.animate().alpha(1f).translationY(0).setDuration(200).start();
        h.postDelayed(() -> {
            if (hint != h) return;
            h.animate().alpha(0f).translationY(dp(16)).setDuration(250).withEndAction(() -> {
                root.removeView(h);
                if (hint == h) hint = null;
            }).start();
        }, 3000);
    }

    @Override
    public void onChanged(String peer) { refresh(); }

    @Override
    public void onPresence(String peer) {
        long now = System.currentTimeMillis();
        if (now - lastPresenceRefresh > 2000) { lastPresenceRefresh = now; refresh(); }
    }

    private long lastPresenceRefresh;

    @Override
    public void onStatus() { updateStatus(); }

    @Override
    public void onSuggestion() { refresh(); }

    private void updateStatus() {
        if (statusView == null) return;
        int n = app.net.connectedCount(), total = app.relays().size();
        statusView.setText(n == 0 ? T.offline() : T.relaysConnected(n, total));
    }

    private void refresh() {
        if (list == null || onboarding) return;
        updateStatus();
        list.removeAllViews();
        if (app.isDev()) list.addView(suggestionsRow());
        if (app.isDev() || app.db.pollCount() > 0) list.addView(pollsRow());
        list.addView(discoverRow());
        List<Db.Contact> cs = app.db.contacts();
        List<Db.Group> gs = app.db.groupList();
        if (cs.isEmpty() && gs.isEmpty()) {
            TextView e = text(T.noChats(), 16, Pal.dim, false);
            e.setGravity(Gravity.CENTER);
            e.setLineSpacing(0, 1.25f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(120), 0, 0);
            list.addView(e, lp);
            return;
        }
        List<Object> items = new java.util.ArrayList<>();
        items.addAll(gs);
        items.addAll(cs);
        items.sort((a, b) -> Long.compare(itemTs(b), itemTs(a)));
        for (Object it : items)
            list.addView(it instanceof Db.Group ? groupRow((Db.Group) it) : row((Db.Contact) it));
    }

    private static long itemTs(Object o) {
        return o instanceof Db.Group ? ((Db.Group) o).lastTs : ((Db.Contact) o).lastTs;
    }

    private void openGroup(String gid) {
        startActivity(new Intent(this, GroupChatActivity.class).putExtra("gid", gid));
    }

    private View groupRow(Db.Group g) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setBackground(round(Pal.bg, 0, 12));
        Avatar ga = new Avatar(g.gid, g.name == null || g.name.isEmpty() ? "#" : g.name);
        ga.silhouette = false;
        r.addView(ga, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(14), 0, dp(8), 0);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView nv = text(g.name, 17, Pal.text, true);
        nv.setSingleLine(true);
        nv.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(nv, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView tv = text(g.lastText != null ? shortWhen(g.lastTs) : "", 12, g.unread > 0 ? Pal.accent : Pal.dim, false);
        top.addView(tv);
        mid.addView(top);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        String preview;
        if (g.lastText == null) preview = T.groupMembers(g.memberCount);
        else {
            String who = g.lastOutgoing ? T.you() : (g.lastSender != null ? app.nameOf(g.lastSender) + ": " : "");
            preview = who + g.lastText.replace('\n', ' ');
        }
        TextView pv = text(preview, 14, Pal.dim, false);
        pv.setSingleLine(true);
        pv.setEllipsize(TextUtils.TruncateAt.END);
        bottom.addView(pv, new LinearLayout.LayoutParams(0, -2, 1f));
        if (g.unread > 0) {
            TextView badge = text(g.unread > 99 ? "99+" : String.valueOf(g.unread), 12, Pal.onAccent, true);
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

        r.setOnClickListener(v -> openGroup(g.gid));
        r.setOnLongClickListener(v -> {
            menu(g.name, new String[]{T.leaveGroup()}, new int[]{Pal.red}, i ->
                    confirm(T.leaveGroupQ(), T.leaveGroup(), () -> { app.net.leaveGroup(g.gid); refresh(); }));
            return true;
        });
        return r;
    }

    private void shareApp() {
        String link = "https://github.com/TillBlack/Nachrichten/releases/latest/download/Nachrichten.apk";
        Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, T.shareAppText() + "\n" + link);
        try { startActivity(Intent.createChooser(s, T.shareApp())); } catch (Exception e) { toast(T.noApp()); }
    }

    private void maybeTutorial() {
        if (tutorialShown || onboarding || !app.keys.has() || app.prefs.getBoolean("tutorialDone", false)) return;
        if (fab == null || hQr == null || list == null || list.getChildCount() == 0) return;
        tutorialShown = true;
        java.util.List<TutorialOverlay.Step> steps = new java.util.ArrayList<>();
        steps.add(new TutorialOverlay.Step(list.getChildAt(0), T.tuDiscover()));
        steps.add(new TutorialOverlay.Step(fab, T.tuAdd()));
        steps.add(new TutorialOverlay.Step(hQr, T.tuQr()));
        steps.add(new TutorialOverlay.Step(hShare, T.tuShare()));
        steps.add(new TutorialOverlay.Step(hSet, T.tuSettings()));
        steps.add(new TutorialOverlay.Step(hProfile, T.tuProfile()));
        TutorialOverlay ov = new TutorialOverlay(this, steps, () -> app.prefs.edit().putBoolean("tutorialDone", true).apply());
        root.addView(ov, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Nur für den Entwickler: Liste der anonymen Vorschläge, ganz oben in der Chatliste. */
    private View suggestionsRow() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(circle(Pal.accent, 0));
        Ico f = new Ico(this, Ico.FILE);
        f.color = Pal.onAccent;
        ic.addView(f, new FrameLayout.LayoutParams(-1, -1));
        r.addView(ic, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(14), 0, 0, 0);
        t.addView(text(T.suggestions(), 17, Pal.text, true));
        t.addView(text(T.suggestionsSub(app.db.suggestionCount()), 14, Pal.dim, false));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        Ico arrow = new Ico(this, Ico.BACK);
        arrow.color = Pal.dim;
        arrow.setRotation(180);
        r.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(24)));
        r.setOnClickListener(v -> startActivity(new Intent(this, SuggestionsActivity.class)));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(r);
        View line = new View(this);
        line.setBackgroundColor(Pal.line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
        lp.setMargins(dp(12), dp(2), dp(12), dp(6));
        wrap.addView(line, lp);
        return wrap;
    }

    /** Entwickler-Einrichtung: erzeugt/zeigt die öffentliche ID des Vorschlags-Postfachs zum Weitergeben. */
    private void openDevSetup() {
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.addView(text(T.devSetupTitle(), 20, Pal.text, true));
        TextView info = text(T.devSetupInfo(), 14, Pal.dim, false);
        info.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
        ip.setMargins(0, dp(6), 0, dp(10));
        box.addView(info, ip);
        String id = app.ensureSugKey();
        TextView idv = text(id, 13, Pal.text, false);
        idv.setTypeface(android.graphics.Typeface.MONOSPACE);
        idv.setTextIsSelectable(true);
        idv.setBackground(round(Pal.field, Pal.line, 10));
        idv.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.addView(idv, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout buttons = buttonRow();
        TextView close = button(T.close(), false);
        close.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, close, true);
        TextView copyBtn = button(T.copy(), true);
        copyBtn.setOnClickListener(v -> { copy(id); toast(T.copied()); });
        addTo(buttons, copyBtn, false);
        addRow(box, buttons, 18);
        showScrollDialog(dlg, box);
    }

    /** Umfragen-Zeile in der Chatliste (für alle, wenn es Umfragen gibt; für den Admin immer). */
    private View pollsRow() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(circle(Pal.accent, 0));
        Ico f = new Ico(this, Ico.CHECK2);
        f.color = Pal.onAccent;
        ic.addView(f, new FrameLayout.LayoutParams(-1, -1));
        r.addView(ic, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(14), 0, 0, 0);
        t.addView(text(T.polls(), 17, Pal.text, true));
        t.addView(text(T.pollsSub(app.db.pollCount()), 14, Pal.dim, false));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        Ico arrow = new Ico(this, Ico.BACK);
        arrow.color = Pal.dim;
        arrow.setRotation(180);
        r.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(24)));
        r.setOnClickListener(v -> startActivity(new Intent(this, PollsActivity.class)));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(r);
        View line = new View(this);
        line.setBackgroundColor(Pal.line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
        lp.setMargins(dp(12), dp(2), dp(12), dp(6));
        wrap.addView(line, lp);
        return wrap;
    }

    /** Dialog zum Einreichen eines anonymen Vorschlags. */
    private void openSuggestion() {
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.addView(text(T.suggestions(), 20, Pal.text, true));
        TextView info = text(T.suggestInfo(), 14, Pal.dim, false);
        info.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
        ip.setMargins(0, dp(6), 0, dp(10));
        box.addView(info, ip);
        EditText fld = field(T.suggestHint(), true);
        fld.setMaxLines(8);
        box.addView(fld, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout buttons = buttonRow();
        TextView cancel = button(T.cancel(), false);
        cancel.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, cancel, true);
        TextView send = button(T.suggestSend(), true);
        send.setOnClickListener(v -> {
            String s = fld.getText().toString().trim();
            if (s.isEmpty()) { toast(T.suggestEmpty()); return; }
            if (!app.suggestReady()) { toast(T.suggestOff()); return; }
            app.net.sendSuggestion(s);
            dlg.dismiss();
            toast(T.suggestThanks());
        });
        addTo(buttons, send, false);
        addRow(box, buttons, 18);
        showScrollDialog(dlg, box);
    }

    private View discoverRow() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(circle(Pal.field, Pal.line));
        Avatar a1 = new Avatar("discover-a", "?");
        Avatar a2 = new Avatar("discover-b", "?");
        ic.addView(a1, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP | Gravity.LEFT));
        FrameLayout.LayoutParams p2 = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM | Gravity.RIGHT);
        ic.addView(a2, p2);
        ic.setPadding(dp(6), dp(6), dp(6), dp(6));
        r.addView(ic, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(14), 0, 0, 0);
        t.addView(text(T.discover(), 17, Pal.text, true));
        int dc = app.net.directoryCount();
        int total = dc < 0 ? -1 : dc + (app.selfListed() ? 1 : 0);
        t.addView(text(total >= 0 ? T.discoverCount(total) : T.discoverSub(), 14, Pal.dim, false));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        Ico arrow = new Ico(this, Ico.BACK);
        arrow.color = Pal.dim;
        arrow.setRotation(180);
        r.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(24)));
        r.setOnClickListener(v -> startActivity(new Intent(this, DirectoryActivity.class)));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(r);
        View line = new View(this);
        line.setBackgroundColor(Pal.line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
        lp.setMargins(dp(12), dp(2), dp(12), dp(6));
        wrap.addView(line, lp);
        return wrap;
    }

    /** Einmal fragen: im öffentlichen Verzeichnis erscheinen oder privat stellen. */
    /** Gibt true zurück, wenn das Popup gezeigt wird. */
    private boolean askDirectoryConsent() {
        if (app.prefs.contains("dirConsent") || onboarding) return false;
        if (app.prefs.getBoolean("profilePrivate", false)) {
            app.prefs.edit().putBoolean("dirConsent", false).apply();
            return false;
        }
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.addView(text(T.consentTitle(), 19, Pal.text, true));
        TextView info = text(T.consentText(), 15, Pal.dim, false);
        info.setPadding(0, dp(8), 0, 0);
        info.setLineSpacing(0, 1.15f);
        box.addView(info);
        LinearLayout buttons = buttonRow();
        TextView priv = button(T.goPrivate(), false);
        priv.setOnClickListener(v -> {
            app.prefs.edit().putBoolean("dirConsent", false).putBoolean("profilePrivate", true)
                    .putLong("profileTs", System.currentTimeMillis()).apply();
            app.net.profileChanged();
            dlg.dismiss();
            refresh();
        });
        addTo(buttons, priv, true);
        TextView pub = button(T.stayPublic(), true);
        pub.setOnClickListener(v -> {
            app.prefs.edit().putBoolean("dirConsent", true).apply();
            app.net.publishDirectory(true);
            dlg.dismiss();
        });
        addTo(buttons, pub, false);
        addRow(box, buttons, 18);
        dlg.setCancelable(false);
        showDialog(dlg, box);
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("openProfile", false) && app.keys.has()) editProfile();
    }

    private View row(Db.Contact c) {
        String name = app.nameOf(c.pub);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setBackground(round(Pal.bg, 0, 12));
        View av = avatarWithDot(c.pub, c.name == null || c.name.isEmpty() ? "?" : c.name, c.avatar, app.net.isOnline(c.pub), 48, c.accBehind, c.accFront, c.accFrame);
        av.setOnClickListener(v -> showProfile(c.pub, true));
        r.addView(av, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(14), 0, dp(8), 0);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView nv = text(name, 17, Pal.text, true);
        nv.setSingleLine(true);
        nv.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(nv, new LinearLayout.LayoutParams(-2, -2));
        String sub = app.subName(c.pub);
        if (sub != null) {
            TextView sn = text(sub, 12, Pal.dim, false);
            sn.setSingleLine(true);
            sn.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1f);
            sp.setMargins(dp(6), dp(1), dp(6), 0);
            top.addView(sn, sp);
        } else {
            top.addView(new View(this), new LinearLayout.LayoutParams(0, -2, 1f));
        }
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
        menu(T.addContact(), new String[]{T.scanQr(), T.enterCode(), T.newGroup()}, null, i -> {
            if (i == 0) startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
            else if (i == 1) enterCodeDialog(null, "");
            else openCreateGroup();
        });
    }

    /** Neue Gruppe anlegen: Name + Mitglieder aus den eigenen Kontakten. */
    private void openCreateGroup() {
        List<Db.Contact> cs = app.db.contacts();
        if (cs.isEmpty()) { toast(T.groupNeedContacts()); return; }
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.addView(text(T.newGroup(), 20, Pal.text, true));
        EditText nameF = field(T.groupName(), false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.setMargins(0, dp(10), 0, dp(6));
        box.addView(nameF, np);
        box.addView(label(T.groupAddMembers()));
        final java.util.Set<String> sel = new java.util.HashSet<>();
        LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        for (Db.Contact c : cs) {
            String nm = app.nameOf(c.pub);
            LinearLayout rr = new LinearLayout(this);
            rr.setGravity(Gravity.CENTER_VERTICAL);
            rr.setPadding(dp(4), dp(8), dp(4), dp(8));
            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setOnCheckedChangeListener((b, on) -> { if (on) sel.add(c.pub); else sel.remove(c.pub); });
            rr.addView(cb, new LinearLayout.LayoutParams(dp(36), -2));
            rr.addView(avatarWithDot(c.pub, nm, c.avatar, false, 36, c.accBehind, c.accFront, c.accFrame),
                    new LinearLayout.LayoutParams(dp(36), dp(36)));
            TextView tn = text(nm, 16, Pal.text, false);
            tn.setPadding(dp(10), 0, 0, 0);
            tn.setSingleLine(true);
            tn.setEllipsize(TextUtils.TruncateAt.END);
            rr.addView(tn, new LinearLayout.LayoutParams(0, -2, 1f));
            rr.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
            listBox.addView(rr, new LinearLayout.LayoutParams(-1, -2));
        }
        box.addView(listBox, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout buttons = buttonRow();
        TextView cancel = button(T.cancel(), false);
        cancel.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, cancel, true);
        TextView create = button(T.create(), true);
        create.setOnClickListener(v -> {
            String nm = nameF.getText().toString().trim();
            if (nm.isEmpty()) { toast(T.groupNameNeeded()); return; }
            if (sel.isEmpty()) { toast(T.groupPickOne()); return; }
            String gid = app.net.createGroup(nm, new java.util.ArrayList<>(sel));
            dlg.dismiss();
            refresh();
            openGroup(gid);
        });
        addTo(buttons, create, false);
        addRow(box, buttons, 18);
        showScrollDialog(dlg, box);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_AVATAR && res == RESULT_OK && data != null && data.getData() != null) {
            android.net.Uri u = data.getData();
            new Thread(() -> {
                boolean ok = setAvatar(u);
                runOnUiThread(() -> {
                    if (!ok) toast(T.readError());
                    if (settingsDlg != null) settingsDlg.dismiss();
                    buildList();
                    root.requestApplyInsets();
                    refresh();
                    editProfile();
                });
            }).start();
            return;
        }
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

    private String appVersion() { return String.valueOf(Updater.installedVersion(this)); }

    private void openSettings() {
        Dialog dlg = dialog();
        LinearLayout box = panel();
        TextView settTitle = text(T.settings(), 20, Pal.text, true);
        // Verstecktes Entwickler-Postfach: nur das eingetragene Gerät (oder vor der Einrichtung) darf es öffnen.
        settTitle.setOnLongClickListener(v -> {
            if (app.isDev() || !app.suggestReady()) { dlg.dismiss(); openDevSetup(); }
            return true;
        });
        box.addView(settTitle);
        TextView ver = text(T.versionLabel() + " " + appVersion(), 13, Pal.dim, false);
        ver.setPadding(0, dp(3), 0, 0);
        box.addView(ver);

        settingsDlg = dlg;
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

        // Privatsphäre im Chat
        box.addView(prefToggle(T.readReceipts(), "readReceipts"));
        TextView rri = text(T.readReceiptsInfo(), 13, Pal.dim, false);
        rri.setPadding(0, dp(4), 0, 0);
        box.addView(rri);
        box.addView(prefToggle(T.showTyping(), "typing"));

        // Updates
        LinearLayout up = new LinearLayout(this);
        up.setGravity(Gravity.CENTER_VERTICAL);
        up.setPadding(0, dp(18), 0, 0);
        up.addView(text(T.autoUpdates(), 16, Pal.text, false), new LinearLayout.LayoutParams(0, -2, 1f));
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
        TextView uti = text(T.autoUpdatesInfo(Updater.installedVersion(this)), 13, Pal.dim, false);
        uti.setPadding(0, dp(4), 0, 0);
        box.addView(uti);

        // Vorschläge – nur sichtbar, wenn ein Postfach im Code hinterlegt ist.
        if (app.suggestReady()) {
            box.addView(label(T.suggestions()));
            LinearLayout sug = new LinearLayout(this);
            sug.setGravity(Gravity.CENTER_VERTICAL);
            sug.setPadding(dp(12), dp(10), dp(12), dp(10));
            sug.setBackground(round(Pal.field, Pal.line, 12));
            TextView sst = text(T.suggestOpen(), 16, Pal.text, false);
            sug.addView(sst, new LinearLayout.LayoutParams(0, -2, 1f));
            Ico sarr = new Ico(this, Ico.BACK);
            sarr.color = Pal.dim;
            sarr.setRotation(180);
            sug.addView(sarr, new LinearLayout.LayoutParams(dp(22), dp(22)));
            sug.setOnClickListener(v -> { dlg.dismiss(); openSuggestion(); });
            LinearLayout.LayoutParams sugp = new LinearLayout.LayoutParams(-1, -2);
            sugp.setMargins(0, dp(6), 0, 0);
            box.addView(sug, sugp);
        }

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


        TextView gh = outlineButton(T.github(), Pal.accent);
        gh.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/TillBlack"))); } catch (Exception e) { toast(T.noApp()); }
        });
        addRow(box, gh, 24);

        LinearLayout buttons = buttonRow();
        TextView close = button(T.close(), true);
        close.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, close, true);
        addRow(box, buttons, 20);
        showScrollDialog(dlg, box);
    }

    /** Profilbild: quadratisch zuschneiden, 256 px, Drehung beachten, als JPEG speichern und verschicken. */
    private boolean setAvatar(android.net.Uri u) {
        try {
            byte[] data;
            try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
                if (in == null) return false;
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > 40 * 1024 * 1024) return false;
                }
                data = bo.toByteArray();
            }
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) return false;
            int sample = 1;
            while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= 256) sample *= 2;
            o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b == null) return false;
            int rot = 0;
            try {
                int or = new android.media.ExifInterface(new java.io.ByteArrayInputStream(data))
                        .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
                rot = or == android.media.ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == android.media.ExifInterface.ORIENTATION_ROTATE_180 ? 180
                        : or == android.media.ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            } catch (Exception ignored) { }
            int side = Math.min(b.getWidth(), b.getHeight());
            android.graphics.Matrix mx = new android.graphics.Matrix();
            float sc = 256f / side;
            mx.postScale(sc, sc);
            mx.postRotate(rot);
            Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side, mx, true);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int q = 80;
            do {
                out.reset();
                sq.compress(Bitmap.CompressFormat.JPEG, q, out);
                q -= 10;
            } while (out.size() > 20000 && q >= 30);
            long ts = System.currentTimeMillis();
            java.io.File f = new java.io.File(app.mediaDir(), "me_" + ts + ".jpg");
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) { fo.write(out.toByteArray()); }
            java.io.File old = app.myAvatar();
            if (old != null) old.delete();
            app.prefs.edit().putString("avatar", f.getAbsolutePath()).putLong("profileTs", ts).apply();
            app.net.profileChanged();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Profil bearbeiten: Bild, Name, Pronomen, Biografie, Sichtbarkeit. */
    private void editProfile() {
        Dialog dlg = dialog();
        settingsDlg = dlg;
        LinearLayout box = panel();
        box.addView(text(T.editProfile(), 20, Pal.text, true));

        box.addView(label(T.profilePicture()));
        LinearLayout pr = new LinearLayout(this);
        pr.setGravity(Gravity.CENTER_VERTICAL);
        java.io.File mine = app.myAvatar();
        Avatar me = new Avatar(app.keys.pubHex(), app.myName().isEmpty() ? "?" : app.myName(), mine != null ? mine.getAbsolutePath() : null)
                .accessories(app.accBehind(), app.accFront(), app.accFrame());
        pr.addView(me, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView pick = button(T.choose(), true);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
        pp.setMargins(dp(14), 0, 0, 0);
        pr.addView(pick, pp);
        TextView rm = null;
        if (mine != null) {
            rm = button(T.remove(), false);
            LinearLayout.LayoutParams rp2 = new LinearLayout.LayoutParams(-2, -2);
            rp2.setMargins(dp(8), 0, 0, 0);
            pr.addView(rm, rp2);
        }
        box.addView(pr);

        box.addView(label(T.yourName()));
        EditText name = field(T.yourName(), false);
        name.setText(app.myName());
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));

        box.addView(label(T.pronouns()));
        EditText pron = field(T.pronounsHint(), false);
        pron.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        pron.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        pron.setText(app.prefs.getString("pronouns", ""));
        box.addView(pron, new LinearLayout.LayoutParams(-1, -2));

        box.addView(label(T.bio()));
        EditText bio = field(T.bioHint(), true);
        bio.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        bio.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(500)});
        bio.setMinLines(3);
        bio.setText(app.prefs.getString("bio", ""));
        box.addView(bio, new LinearLayout.LayoutParams(-1, -2));

        box.addView(label(T.tagsLabel()));
        EditText tags = field(T.tagsHint(), false);
        tags.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        tags.setText(app.prefs.getString("tags", ""));
        box.addView(tags, new LinearLayout.LayoutParams(-1, -2));
        TextView tagsInfo = text(T.tagsInfo(), 13, Pal.dim, false);
        tagsInfo.setPadding(0, dp(4), 0, 0);
        box.addView(tagsInfo);

        // Sichtbarkeit
        box.addView(label(T.visibility()));
        final boolean[] priv = {app.prefs.getBoolean("profilePrivate", false)};
        java.util.Set<String> allowed = new java.util.HashSet<>(app.prefs.getStringSet("profileAllowed", java.util.Collections.emptySet()));
        LinearLayout people = new LinearLayout(this);
        people.setOrientation(LinearLayout.VERTICAL);
        Ico rPub = new Ico(this, Ico.RADIO), rPriv = new Ico(this, Ico.RADIO);
        Runnable showVis = () -> {
            rPub.on = !priv[0];
            rPriv.on = priv[0];
            rPub.invalidate();
            rPriv.invalidate();
            people.setVisibility(priv[0] ? View.VISIBLE : View.GONE);
        };
        box.addView(radioRow(rPub, T.visPublic(), T.visPublicInfo(), v -> { priv[0] = false; showVis.run(); }));
        box.addView(radioRow(rPriv, T.visPrivate(), T.visPrivateInfo(), v -> { priv[0] = true; showVis.run(); }));
        List<Db.Contact> cs = app.db.contacts();
        if (cs.isEmpty()) people.addView(text(T.noChats().split("\n")[0], 14, Pal.dim, false));
        for (Db.Contact c : cs) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(6), 0, dp(6));
            row.addView(new Avatar(c.pub, c.name == null || c.name.isEmpty() ? "?" : c.name, c.avatar), new LinearLayout.LayoutParams(dp(32), dp(32)));
            TextView cn = text(app.nameOf(c.pub), 15, Pal.text, false);
            cn.setPadding(dp(10), 0, 0, 0);
            cn.setSingleLine(true);
            row.addView(cn, new LinearLayout.LayoutParams(0, -2, 1f));
            Ico tg = new Ico(this, Ico.TOGGLE);
            tg.on = allowed.contains(c.pub);
            row.addView(tg, new LinearLayout.LayoutParams(dp(52), dp(36)));
            row.setOnClickListener(v -> {
                if (allowed.contains(c.pub)) allowed.remove(c.pub); else allowed.add(c.pub);
                tg.on = allowed.contains(c.pub);
                tg.invalidate();
            });
            tg.setOnClickListener(v -> row.performClick());
            people.addView(row);
        }
        box.addView(people);
        showVis.run();

        // Online-Status
        LinearLayout on = new LinearLayout(this);
        on.setGravity(Gravity.CENTER_VERTICAL);
        on.setPadding(0, dp(16), 0, 0);
        on.addView(text(T.showOnline(), 16, Pal.text, false), new LinearLayout.LayoutParams(0, -2, 1f));
        Ico onTg = new Ico(this, Ico.TOGGLE);
        onTg.on = app.prefs.getBoolean("showOnline", true);
        on.addView(onTg, new LinearLayout.LayoutParams(dp(52), dp(40)));
        on.setOnClickListener(v -> {
            boolean val = !app.prefs.getBoolean("showOnline", true);
            app.prefs.edit().putBoolean("showOnline", val).apply();
            onTg.on = val;
            onTg.invalidate();
            app.net.publishDirectory(true);
        });
        onTg.setOnClickListener(v -> on.performClick());
        box.addView(on);
        TextView oni = text(T.showOnlineInfo(), 13, Pal.dim, false);
        oni.setPadding(0, dp(4), 0, 0);
        box.addView(oni);

        // Speichern (auch vor dem Bildwechsel, damit nichts verloren geht)
        Runnable save = () -> {
            String n = name.getText().toString().trim(), p = pron.getText().toString().trim(), b = bio.getText().toString().trim();
            String tg = tags.getText().toString().trim();
            boolean changed = !n.equals(app.myName()) || !p.equals(app.prefs.getString("pronouns", ""))
                    || !b.equals(app.prefs.getString("bio", "")) || !tg.equals(app.prefs.getString("tags", ""))
                    || priv[0] != app.prefs.getBoolean("profilePrivate", false)
                    || !allowed.equals(app.prefs.getStringSet("profileAllowed", java.util.Collections.emptySet()));
            if (!changed) return;
            app.prefs.edit().putString("name", n).putString("pronouns", p).putString("bio", b).putString("tags", tg)
                    .putBoolean("dirConsent", !priv[0])
                    .putBoolean("profilePrivate", priv[0]).putStringSet("profileAllowed", new java.util.HashSet<>(allowed))
                    .putLong("profileTs", System.currentTimeMillis()).apply();
            app.net.profileChanged();
        };
        pick.setOnClickListener(v -> {
            save.run();
            try {
                startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_AVATAR);
            } catch (Exception e) { toast(T.noApp()); }
        });
        if (rm != null) rm.setOnClickListener(v -> {
            save.run();
            mine.delete();
            app.prefs.edit().remove("avatar").putLong("profileTs", System.currentTimeMillis()).apply();
            app.net.profileChanged();
            dlg.dismiss();
            buildList();
            root.requestApplyInsets();
            refresh();
            editProfile();
        });

        TextView info = text(T.profileInfo(), 13, Pal.dim, false);
        info.setPadding(0, dp(14), 0, 0);
        box.addView(info);

        // Mein Code
        box.addView(label(T.myCode()));
        TextView code = outlineButton(T.myCode(), Pal.accent);
        code.setOnClickListener(v -> showMyCode());
        box.addView(code, new LinearLayout.LayoutParams(-1, -2));

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
        TextView cancel = button(T.cancel(), false);
        cancel.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, cancel, true);
        TextView ok = button(T.save(), true);
        ok.setOnClickListener(v -> { save.run(); dlg.dismiss(); buildList(); root.requestApplyInsets(); refresh(); });
        addTo(buttons, ok, false);
        addRow(box, buttons, 18);
        showScrollDialog(dlg, box);
    }

    private View radioRow(Ico radio, String title, String info, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(4), 0, dp(4));
        r.addView(radio, new LinearLayout.LayoutParams(dp(36), dp(42)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text(title, 16, Pal.text, false));
        t.addView(text(info, 13, Pal.dim, false));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        r.setOnClickListener(click);
        radio.setOnClickListener(click);
        return r;
    }

    /** Zeile mit Schalter für eine Einstellung (Standard an). */
    private View prefToggle(String label, String key) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(18), 0, 0);
        r.addView(text(label, 16, Pal.text, false), new LinearLayout.LayoutParams(0, -2, 1f));
        Ico tg = new Ico(this, Ico.TOGGLE);
        tg.on = app.prefs.getBoolean(key, true);
        r.addView(tg, new LinearLayout.LayoutParams(dp(52), dp(40)));
        r.setOnClickListener(v -> {
            boolean on = !app.prefs.getBoolean(key, true);
            app.prefs.edit().putBoolean(key, on).apply();
            tg.on = on;
            tg.invalidate();
        });
        tg.setOnClickListener(v -> r.performClick());
        return r;
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
