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
        List<Net.DirEntry> all = new ArrayList<>(entries.values());
        all.sort((a, b) -> Long.compare(b.seen, a.seen));
        long now = System.currentTimeMillis() / 1000;
        int online = 0;
        for (Net.DirEntry e : all) if (now - e.seen < 300) online++;
        status.setText(all.isEmpty() ? (loaded ? T.dirEmpty() : T.dirLoading()) : T.dirCount(all.size(), online));
        for (Net.DirEntry e : all) list.addView(row(e, now - e.seen < 300));
    }

    private String thumbPath(Net.DirEntry e) {
        if (e.thumb == null) return null;
        File f = new File(getCacheDir(), "dir_" + e.pub.substring(0, 16) + "_" + e.seen + ".jpg");
        if (!f.exists()) {
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(e.thumb); } catch (Exception ex) { return null; }
        }
        return f.getAbsolutePath();
    }

    private View row(Net.DirEntry e, boolean online) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        FrameLayout avBox = new FrameLayout(this);
        avBox.addView(new Avatar(e.pub, e.name, thumbPath(e)), new FrameLayout.LayoutParams(dp(48), dp(48)));
        if (online) {
            View dot = new View(this);
            dot.setBackground(round(0xFF3FBF6F, Pal.bg, 7));
            avBox.addView(dot, new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.BOTTOM | Gravity.RIGHT));
        }
        r.addView(avBox, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(14), 0, 0, 0);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = text(e.name, 17, Pal.text, true);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(text(online ? T.online() : shortWhen(e.seen * 1000), 12, online ? 0xFF3FBF6F : Pal.dim, false));
        mid.addView(top);
        String sub = (e.pronouns.isEmpty() ? "" : e.pronouns + (e.bio.isEmpty() ? "" : "  ·  ")) + e.bio.replace('\n', ' ');
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
        Avatar av = new Avatar(e.pub, e.name, path);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(112), dp(112));
        ap.setMargins(0, dp(4), 0, dp(12));
        box.addView(av, ap);
        TextView n = text(e.name, 22, Pal.text, true);
        n.setGravity(Gravity.CENTER);
        box.addView(n, new LinearLayout.LayoutParams(-1, -2));
        if (!e.pronouns.isEmpty()) {
            TextView pr = text(e.pronouns, 14, Pal.dim, false);
            pr.setGravity(Gravity.CENTER);
            box.addView(pr, new LinearLayout.LayoutParams(-1, -2));
        }
        box.addView(label(T.bio()), new LinearLayout.LayoutParams(-1, -2));
        TextView bio = text(e.bio.isEmpty() ? T.noBio() : e.bio, 15, e.bio.isEmpty() ? Pal.dim : Pal.text, false);
        bio.setLineSpacing(0, 1.2f);
        bio.setBackground(round(Pal.field, Pal.line, 10));
        bio.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.addView(bio, new LinearLayout.LayoutParams(-1, -2));
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
