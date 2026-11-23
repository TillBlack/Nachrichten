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

/** Nur für den Entwickler: eingegangene anonyme Vorschläge. */
public class SuggestionsActivity extends Base {

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
        titles.addView(text(T.suggestions(), 22, Pal.text, true));
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
        List<Db.Suggestion> all = app.db.suggestions();
        status.setText(all.isEmpty() ? T.suggestNone() : T.suggestionsSub(all.size()));
        list.removeAllViews();
        if (all.isEmpty()) {
            TextView e = text(T.suggestNone(), 16, Pal.dim, false);
            e.setGravity(Gravity.CENTER);
            e.setLineSpacing(0, 1.25f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(120), 0, 0);
            list.addView(e, lp);
            return;
        }
        for (Db.Suggestion s : all) list.addView(card(s));
    }

    private View card(Db.Suggestion s) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(Pal.card, Pal.line, 12));
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(6));
        box.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView who = text(T.suggestAnon(), 13, Pal.accent, true);
        top.addView(who, new LinearLayout.LayoutParams(0, -2, 1f));
        CharSequence when = DateUtils.getRelativeTimeSpanString(
                s.ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        top.addView(text(when.toString(), 12, Pal.dim, false));
        box.addView(top);

        TextView body = text(s.body, 16, Pal.text, false);
        body.setLineSpacing(0, 1.2f);
        body.setPadding(0, dp(6), 0, 0);
        box.addView(body);

        box.setOnLongClickListener(v -> {
            confirm(T.suggestDelete(), T.delete(), () -> { app.db.deleteSuggestion(s.id); render(); });
            return true;
        });
        return box;
    }
}
