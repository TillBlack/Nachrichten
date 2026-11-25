package de.nachrichten.app;

import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Nur für den Admin: Einsprüche gesperrter Nutzer und Übersicht der Sperren. */
public class AppealsActivity extends Base {

    private LinearLayout header, list;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!app.isDev()) { finish(); return; }
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
        titles.addView(text(T.appeals(), 22, Pal.text, true));
        status = text("", 13, Pal.dim, false);
        titles.addView(status);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        onInsets();
    }

    @Override
    protected void onInsets() {
        if (header == null) return;
        header.setPadding(dp(4), insetTop + dp(8), dp(12), dp(8));
        list.setPadding(dp(12), 0, dp(12), insetBottom + dp(24));
    }

    @Override
    protected void onResume() {
        super.onResume();
        app.net.acquire("ui");
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        app.net.release("ui");
    }

    private void render() {
        list.removeAllViews();
        List<String> banned = app.db.bannedList();
        List<Db.ModItem> appeals = app.db.modItems(Db.MOD_APPEAL);
        status.setText(appeals.isEmpty() ? T.appealsNone() : T.appealsSub(appeals.size()));

        if (!banned.isEmpty()) {
            list.addView(sectionLabel(T.bannedOverview()));
            for (String pub : banned) list.addView(bannedCard(pub));
        }

        if (appeals.isEmpty() && banned.isEmpty()) {
            TextView e = text(T.appealsNone(), 16, Pal.dim, false);
            e.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(120), 0, 0);
            list.addView(e, lp);
            return;
        }
        if (!appeals.isEmpty()) {
            list.addView(sectionLabel(T.appeals()));
            for (Db.ModItem m : appeals) list.addView(appealCard(m));
        }
    }

    private TextView sectionLabel(String s) {
        TextView t = text(s, 13, Pal.dim, true);
        t.setPadding(dp(2), dp(14), 0, dp(6));
        return t;
    }

    private View bannedCard(String pub) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(round(Pal.card, Pal.line, 12));
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(6));
        box.setLayoutParams(lp);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text(app.nameOf(pub), 16, Pal.text, true));
        t.addView(text(shortId(pub), 12, Pal.dim, false));
        box.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView unban = outlineButton(T.modUnban(), Pal.accent);
        unban.setOnClickListener(v -> { app.net.adminCommand("unban", pub, 0, ""); render(); });
        box.addView(unban);
        return box;
    }

    private View appealCard(Db.ModItem m) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(Pal.card, Pal.line, 12));
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(6));
        box.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout who = new LinearLayout(this);
        who.setOrientation(LinearLayout.VERTICAL);
        who.addView(text(app.nameOf(m.frompub), 16, Pal.text, true));
        who.addView(text(shortId(m.frompub), 12, Pal.dim, false));
        top.addView(who, new LinearLayout.LayoutParams(0, -2, 1f));
        CharSequence when = DateUtils.getRelativeTimeSpanString(m.ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        top.addView(text(when.toString(), 12, Pal.dim, false));
        box.addView(top);

        TextView body = text(m.body, 16, Pal.text, false);
        body.setLineSpacing(0, 1.2f);
        body.setPadding(0, dp(8), 0, dp(10));
        box.addView(body);

        if (m.replied && m.reply != null) {
            TextView r = text("↩ " + m.reply, 14, Pal.accent, false);
            r.setLineSpacing(0, 1.2f);
            r.setPadding(0, 0, 0, dp(8));
            box.addView(r);
        }

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        boolean banned = app.db.isBanned(m.frompub);
        TextView unban = outlineButton(banned ? T.modUnban() : T.moderate(), banned ? Pal.accent : Pal.red);
        unban.setOnClickListener(v -> {
            if (banned) { app.net.adminCommand("unban", m.frompub, 0, ""); render(); }
            else moderate(m.frompub, app.nameOf(m.frompub));
        });
        row.addView(unban);
        TextView rep = button(m.replied ? T.replied() : T.reply(), true);
        rep.setOnClickListener(v -> ask(T.reply(), null, true, t -> {
            if (t.isEmpty()) return;
            app.net.adminReply(m.frompub, t);
            app.db.setModReply(m.id, t);
            render();
        }));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.setMargins(dp(8), 0, 0, 0);
        row.addView(rep, rp);
        box.addView(row);

        box.setOnLongClickListener(v -> {
            confirm(T.delete(), T.delete(), () -> { app.db.deleteModItem(m.id); render(); });
            return true;
        });
        return box;
    }
}
