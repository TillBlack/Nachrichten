package de.nachrichten.app;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Öffentliche Nutzerliste: alle mit öffentlichem Profil, die die App in den letzten 30 Tagen genutzt haben. */
public class DirectoryActivity extends Base implements Net.DirListener {

    private LinearLayout header, list;
    private TextView status;
    private Ico visTg;
    private final Map<String, Net.DirEntry> entries = new HashMap<>();
    private boolean loaded;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Ico back = new Ico(this, Ico.BACK);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(48)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text(T.discover(), 22, Pal.text, true));
        status = text(T.dirLoading(), 13, Pal.dim, false);
        titles.addView(status);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Schieberegler: in der Liste "Alle Nutzer" sichtbar (öffentlich)
        LinearLayout vis = new LinearLayout(this);
        vis.setGravity(Gravity.CENTER_VERTICAL);
        vis.setBackground(round(Pal.card, Pal.line, 12));
        vis.setPadding(dp(14), dp(10), dp(12), dp(10));
        LinearLayout vt = new LinearLayout(this);
        vt.setOrientation(LinearLayout.VERTICAL);
        vt.addView(text(T.visibleToAll(), 16, Pal.text, true));
        TextView vinfo = text(T.visibleToAllInfo(), 12, Pal.dim, false);
        vinfo.setLineSpacing(0, 1.12f);
        vt.addView(vinfo);
        vis.addView(vt, new LinearLayout.LayoutParams(0, -2, 1f));
        visTg = new Ico(this, Ico.TOGGLE);
        visTg.on = app.selfListed();
        LinearLayout.LayoutParams tgp = new LinearLayout.LayoutParams(dp(52), dp(40));
        tgp.setMargins(dp(8), 0, 0, 0);
        vis.addView(visTg, tgp);
        Runnable toggleVis = () -> {
            boolean val = !(app.prefs.getBoolean("dirConsent", false) && !app.prefs.getBoolean("profilePrivate", false));
            app.prefs.edit().putBoolean("dirConsent", val).putBoolean("profilePrivate", !val)
                    .putLong("profileTs", System.currentTimeMillis()).apply();
            visTg.on = val;
            visTg.invalidate();
            app.net.publishDirectory(true);
            render();
        };
        vis.setOnClickListener(v -> toggleVis.run());
        visTg.setOnClickListener(v -> toggleVis.run());
        LinearLayout.LayoutParams visp = new LinearLayout.LayoutParams(-1, -2);
        visp.setMargins(dp(12), dp(6), dp(12), dp(4));
        content.addView(vis, visp);

        // Hinweis: so wird man unsichtbar, mit Knopf direkt zur Einstellung
        LinearLayout hint = new LinearLayout(this);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        hint.setBackground(round(Pal.card, Pal.line, 12));
        hint.setPadding(dp(14), dp(10), dp(8), dp(10));
        TextView ht = text(T.dirHint(), 13, Pal.dim, false);
        ht.setLineSpacing(0, 1.15f);
        hint.addView(ht, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView go = text(T.dirHintButton(), 13, Pal.onAccent, true);
        go.setPadding(dp(12), dp(8), dp(12), dp(8));
        go.setBackground(round(Pal.accent, 0, 16));
        go.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class).putExtra("openProfile", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2);
        gp.setMargins(dp(8), 0, 0, 0);
        hint.addView(go, gp);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.setMargins(dp(12), dp(4), dp(12), dp(8));
        content.addView(hint, hp);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), 0, dp(8), 0);
        content.addView(list);
        onInsets();
    }

    @Override
    protected void onInsets() {
        if (header == null) return;
        header.setPadding(dp(4), insetTop + dp(8), dp(12), dp(8));
        list.setPadding(dp(8), 0, dp(8), insetBottom + dp(24));
    }

    @Override
    protected void onResume() {
        super.onResume();
        app.net.acquire("ui");
        if (!app.listOpen()) { render(); return; }   // Liste erst ab 1.000 Nutzern
        // Verbindungen brauchen evtl. einen Moment
        root.postDelayed(() -> app.net.loadDirectory(this), 600);
        root.postDelayed(() -> { if (!loaded) { loaded = true; render(); } }, 6000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        app.net.closeDirectory();
        app.net.release("ui");
    }

    @Override
    public void entry(Net.DirEntry e) {
        Net.DirEntry old = entries.get(e.pub);
        if (old != null && old.seen >= e.seen) return;
        entries.put(e.pub, e);
        if (loaded) render();
    }

    @Override
    public void done() {
        loaded = true;
        render();
    }

    private void render() {
        list.removeAllViews();
        if (visTg != null) { visTg.on = app.selfListed(); visTg.invalidate(); }
        String myPub = app.keys.has() ? app.keys.pubHex() : null;
        if (!app.listOpen()) {
            // Noch gesperrt: eigenes Profil und Sichtbarkeits-Schalter bleiben, andere Nutzer werden nicht gezeigt.
            status.setText(T.listLockedSub());
            if (myPub != null) list.addView(selfRow());
            TextView info = text(T.listLockedInfo(), 15, Pal.dim, false);
            info.setGravity(Gravity.CENTER);
            info.setLineSpacing(0, 1.25f);
            info.setPadding(dp(20), dp(28), dp(20), dp(20));
            list.addView(info, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        List<Net.DirEntry> all = new ArrayList<>();
        for (Net.DirEntry e : entries.values()) {
            if (myPub != null && e.pub.equals(myPub)) continue;
            if (app.db.hidden(e.pub) && !app.isDev()) continue; // auf privat gestellt (Admin sieht sie weiter)
            if (e.listed) all.add(e);
        }
        all.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

        int total = all.size() + (app.selfListed() ? 1 : 0);
        status.setText(all.isEmpty() ? (loaded ? T.dirEmpty() : T.dirLoading()) : T.discoverCount(total));

        if (myPub != null) list.addView(selfRow());
        for (Net.DirEntry e : all) list.addView(row(e));
    }

    private View selfRow() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setBackground(round(Pal.card, Pal.accent, 12));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.setMargins(0, dp(2), 0, dp(6));
        r.setLayoutParams(rlp);
        java.io.File mine = app.myAvatar();
        String nm = app.myName().isEmpty() ? "?" : app.myName();
        r.addView(new Avatar(app.keys.pubHex(), nm, mine != null ? mine.getAbsolutePath() : null)
                .accessories(app.accBehind(), app.accFront(), app.accFrame()), new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(14), 0, 0, 0);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = text(app.myName().isEmpty() ? T.youName() : app.myName(), 17, Pal.text, true);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(n, new LinearLayout.LayoutParams(-2, -2));
        TextView badge = text(T.youName(), 11, Pal.onAccent, true);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        badge.setBackground(round(Pal.accent, 0, 10));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.setMargins(dp(8), 0, 0, 0);
        top.addView(badge, bp);
        mid.addView(top);
        String pron = app.prefs.getString("pronouns", "");
        String bioS = app.prefs.getString("bio", "").replace('\n', ' ');
        String sub = (pron.isEmpty() ? "" : pron + (bioS.isEmpty() ? "" : "  ·  ")) + bioS;
        if (!sub.isEmpty()) {
            TextView s = text(sub, 14, Pal.dim, false);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            mid.addView(s);
        }
        r.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
        r.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class).putExtra("openProfile", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });
        return r;
    }

    private String thumbPath(Net.DirEntry e) {
        if (e.thumb == null) return null;
        File f = new File(getCacheDir(), "dir_" + e.pub.substring(0, 16) + "_" + e.seen + ".jpg");
        if (!f.exists()) {
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(e.thumb); } catch (Exception ex) { return null; }
        }
        return f.getAbsolutePath();
    }

    /** Großer Name in der Nutzerliste: Admin-Überschreibung > lokal gespeicherter Name > selbst gewählter Name. */
    private String dirBigName(Net.DirEntry e) {
        String ov = app.db.nameOverride(e.pub);
        if (ov != null) return ov.trim().isEmpty() ? app.fallbackName(e.pub) : ov;
        Db.Contact c = app.db.contact(e.pub);
        if (c != null && c.name != null && !c.name.trim().isEmpty()) return c.name;
        return e.name == null || e.name.isEmpty() ? app.fallbackName(e.pub) : e.name;
    }

    /** Kleiner Zusatz: der selbst gewählte Name, falls er sich vom großen Namen unterscheidet. */
    private String dirSubName(Net.DirEntry e) {
        if (app.db.nameOverride(e.pub) != null) return null;
        if (e.name == null || e.name.isEmpty()) return null;
        return e.name.equals(dirBigName(e)) ? null : e.name;
    }

    private View row(Net.DirEntry e) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.addView(new Avatar(e.pub, e.name, modPath(e.pub, thumbPath(e))).accessories(e.accBehind, e.accFront, e.accFrame), new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(14), 0, 0, 0);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = text(dirBigName(e), 17, Pal.text, true);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(n, new LinearLayout.LayoutParams(-2, -2));
        String subName = dirSubName(e);
        if (subName != null) {
            TextView sn = text(subName, 12, Pal.dim, false);
            sn.setSingleLine(true);
            sn.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams snp = new LinearLayout.LayoutParams(0, -2, 1f);
            snp.setMargins(dp(6), dp(1), dp(6), 0);
            top.addView(sn, snp);
        } else {
            top.addView(new android.view.View(this), new LinearLayout.LayoutParams(0, -2, 1f));
        }
        if (app.isDev() && app.db.isBanned(e.pub)) {
            TextView badge = text(T.modActive(), 12, Pal.red, true);
            top.addView(badge, new LinearLayout.LayoutParams(-2, -2));
        }
        mid.addView(top);
        // unter dem Namen die Biografie (statt der Pronomen)
        String bioOv = app.db.bioOverride(e.pub);
        String sub = (bioOv != null ? bioOv : e.bio).replace('\n', ' ');
        if (!sub.isEmpty()) {
            TextView s = text(sub, 14, Pal.dim, false);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            mid.addView(s);
        }
        r.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
        r.setOnClickListener(v -> openEntry(e));
        return r;
    }

    /** Profil aus der Liste ansehen, mit "Schreiben". */
    private void openEntry(Net.DirEntry e) {
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        String path = thumbPath(e);
        Avatar av = new Avatar(e.pub, e.name, modPath(e.pub, path)).accessories(e.accBehind, e.accFront, e.accFrame);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(112), dp(112));
        ap.setMargins(0, dp(4), 0, dp(12));
        box.addView(av, ap);
        TextView n = text(dirBigName(e), 22, Pal.text, true);
        n.setGravity(Gravity.CENTER);
        box.addView(n, new LinearLayout.LayoutParams(-1, -2));
        String subName = dirSubName(e);
        if (subName != null) {
            TextView sn = text(subName, 14, Pal.dim, false);
            sn.setGravity(Gravity.CENTER);
            box.addView(sn, new LinearLayout.LayoutParams(-1, -2));
        }
        String pronOv = app.db.pronOverride(e.pub);
        String pron = pronOv != null ? pronOv : e.pronouns;
        if (pron != null && !pron.isEmpty()) {
            TextView pr = text(pron, 14, Pal.dim, false);
            pr.setGravity(Gravity.CENTER);
            box.addView(pr, new LinearLayout.LayoutParams(-1, -2));
        }
        box.addView(label(T.bio()), new LinearLayout.LayoutParams(-1, -2));
        String bioOv = app.db.bioOverride(e.pub);
        String bioStr = bioOv != null ? bioOv : e.bio;
        boolean bioEmpty = bioStr == null || bioStr.isEmpty();
        TextView bio = text(bioEmpty ? T.noBio() : bioStr, 15, bioEmpty ? Pal.dim : Pal.text, false);
        bio.setLineSpacing(0, 1.2f);
        bio.setBackground(round(Pal.field, Pal.line, 10));
        bio.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.addView(bio, new LinearLayout.LayoutParams(-1, -2));
        if (e.tags != null && !e.tags.isEmpty()) {
            box.addView(label(T.tagsLabel()), new LinearLayout.LayoutParams(-1, -2));
            TextView tg = text(e.tags, 15, Pal.accent, true);
            box.addView(tg, new LinearLayout.LayoutParams(-1, -2));
        }
        if (app.isDev() && !App.SUGGEST_PUBKEY.equals(e.pub)) {
            TextView mod = outlineButton(T.moderate(), Pal.red);
            mod.setOnClickListener(v -> { dlg.dismiss(); moderate(e.pub, e.name); });
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
            ml.setMargins(0, dp(16), 0, 0);
            box.addView(mod, ml);
        }
        LinearLayout buttons = buttonRow();
        TextView close = button(T.close(), false);
        close.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, close, true);
        TextView write = button(T.write(), true);
        write.setOnClickListener(v -> {
            dlg.dismiss();
            if (app.db.contact(e.pub) == null) app.db.addContact(e.pub, e.name);
            startActivity(new Intent(this, ChatActivity.class).putExtra("peer", e.pub));
        });
        addTo(buttons, write, false);
        addRow(box, buttons, 18);
        showScrollDialog(dlg, box);
    }
}
