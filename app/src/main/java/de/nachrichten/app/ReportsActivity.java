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

/** Nur für den Admin: eingegangene Meldungen über Nutzer. */
public class ReportsActivity extends Base {

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
        titles.addView(text(T.reports(), 22, Pal.text, true));
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
        List<Db.ModItem> reports = app.db.modItems(Db.MOD_REPORT);
        status.setText(reports.isEmpty() ? T.reportsNone() : T.reportsSub(reports.size()));
        list.removeAllViews();
        if (reports.isEmpty()) {
            TextView e = text(T.reportsNone(), 16, Pal.dim, false);
            e.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(120), 0, 0);
            list.addView(e, lp);
            return;
        }
        for (Db.ModItem m : reports) list.addView(card(m));
    }

    private View card(Db.ModItem m) {
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
        who.addView(text(T.reportTarget() + ": " + app.nameOf(m.target), 16, Pal.text, true));
        who.addView(text(shortId(m.target), 12, Pal.dim, false));
        top.addView(who, new LinearLayout.LayoutParams(0, -2, 1f));
        CharSequence when = DateUtils.getRelativeTimeSpanString(m.ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        top.addView(text(when.toString(), 12, Pal.dim, false));
        box.addView(top);

        TextView from = text(T.reportWho() + ": " + app.nameOf(m.frompub), 13, Pal.dim, false);
        from.setPadding(0, dp(6), 0, 0);
        box.addView(from);

        if (m.body != null && !m.body.isEmpty()) {
            TextView body = text(m.body, 16, Pal.text, false);
            body.setLineSpacing(0, 1.2f);
            body.setPadding(0, dp(8), 0, dp(10));
            box.addView(body);
        }

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        boolean banned = app.db.isBanned(m.target);
        TextView mod = outlineButton(banned ? T.modUnban() : T.moderate(), banned ? Pal.accent : Pal.red);
        mod.setOnClickListener(v -> {
            if (banned) { app.net.adminCommand("unban", m.target, 0, ""); render(); }
            else moderate(m.target, app.nameOf(m.target));
        });
        row.addView(mod);
        box.addView(row, new LinearLayout.LayoutParams(-1, -2));

        box.setOnLongClickListener(v -> {
            confirm(T.delete(), T.delete(), () -> { app.db.deleteModItem(m.id); render(); });
            return true;
        });
        return box;
    }
}
