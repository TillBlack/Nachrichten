package de.nachrichten.app;

import android.app.Dialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Umfragen: nur der Admin (Postfach-Gerät) kann sie erstellen, jeder stimmt einmal ab. */
public class PollsActivity extends Base implements Net.Listener {

    private LinearLayout header, list;
    private TextView status;

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
        titles.addView(text(T.polls(), 22, Pal.text, true));
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
        list.setPadding(dp(12), dp(4), dp(12), insetBottom + dp(24));
    }

    @Override
    protected void onResume() {
        super.onResume();
        app.net.acquire("ui");
        app.net.addListener(this);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        app.net.removeListener(this);
        app.net.release("ui");
    }

    @Override
    public void onChanged(String peer) { render(); }

    @Override
    public void onStatus() { }

    private void render() {
        list.removeAllViews();
        List<Db.Poll> polls = app.db.polls();
        status.setText(T.pollsSub(polls.size()));
        if (app.isDev()) list.addView(newPollRow());
        if (polls.isEmpty()) {
            TextView e = text(T.pollsNone(), 16, Pal.dim, false);
            e.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(80), 0, 0);
            list.addView(e, lp);
            return;
        }
        String me = app.keys.has() ? app.keys.pubHex() : "";
        for (Db.Poll p : polls) list.addView(pollCard(p, me));
    }

    private View newPollRow() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(round(withAlpha(Pal.accent, 0x22), Pal.accent, 12));
        r.setPadding(dp(14), dp(12), dp(14), dp(12));
        Ico plus = new Ico(this, Ico.PLUS);
        plus.color = Pal.accent;
        r.addView(plus, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView t = text(T.newPoll(), 16, Pal.accent, true);
        t.setPadding(dp(12), 0, 0, 0);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(8));
        r.setLayoutParams(lp);
        r.setOnClickListener(v -> openEditor(null));
        return r;
    }

    private View pollCard(Db.Poll p, String me) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(Pal.card, Pal.line, 14));
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.setMargins(0, dp(4), 0, dp(8));
        box.setLayoutParams(blp);

        TextView title = text(p.title, 18, Pal.text, true);
        box.addView(title);

        int n = p.options.size();
        int[] counts = app.db.voteCounts(p.id, n);
        int total = 0;
        for (int c : counts) total += c;
        int mine = app.db.myVote(p.id, me);

        for (int i = 0; i < n; i++) {
            final int idx = i;
            boolean chosen = mine == i;
            LinearLayout opt = new LinearLayout(this);
            opt.setOrientation(LinearLayout.VERTICAL);
            opt.setBackground(round(chosen ? withAlpha(Pal.accent, 0x22) : Pal.field, chosen ? Pal.accent : Pal.line, 10));
            opt.setPadding(dp(12), dp(9), dp(12), dp(9));
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
            olp.setMargins(0, dp(8), 0, 0);
            opt.setLayoutParams(olp);

            LinearLayout rowTop = new LinearLayout(this);
            rowTop.setGravity(Gravity.CENTER_VERTICAL);
            TextView ot = text(p.options.get(i), 16, Pal.text, chosen);
            ot.setMaxLines(3);
            rowTop.addView(ot, new LinearLayout.LayoutParams(0, -2, 1f));
            int pct = total > 0 ? Math.round(counts[i] * 100f / total) : 0;
            rowTop.addView(text(counts[i] + " · " + pct + "%", 13, chosen ? Pal.accent : Pal.dim, false));
            opt.addView(rowTop);

            // Balken
            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(4));
            barLp.setMargins(0, dp(7), 0, 0);
            bar.setLayoutParams(barLp);
            View fill = new View(this);
            fill.setBackgroundColor(chosen ? Pal.accent : Pal.dim);
            View rest = new View(this);
            bar.addView(fill, new LinearLayout.LayoutParams(0, -1, Math.max(0, counts[i])));
            bar.addView(rest, new LinearLayout.LayoutParams(0, -1, Math.max(1, total - counts[i]) + (total == 0 ? 1 : 0)));
            opt.addView(bar);

            opt.setOnClickListener(v -> app.net.vote(p.id, idx));
            box.addView(opt);
        }

        TextView foot = text(T.pollVotes(total) + (mine >= 0 ? "  ·  " + T.pollYouVoted() : ""), 12, Pal.dim, false);
        foot.setPadding(0, dp(10), 0, 0);
        box.addView(foot);

        if (app.isDev()) {
            box.setOnLongClickListener(v -> {
                menu(p.title, new String[]{T.editPoll(), T.deletePoll()}, new int[]{0, Pal.red}, i -> {
                    if (i == 0) openEditor(p);
                    else confirm(T.deletePollQ(), T.deletePoll(), () -> app.net.savePoll(p.id, p.title, p.options, true));
                });
                return true;
            });
        }
        return box;
    }

    // ------------------------------------------------------------------ Erstellen / Bearbeiten

    private void openEditor(Db.Poll existing) {
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.addView(text(existing == null ? T.newPoll() : T.editPoll(), 20, Pal.text, true));

        EditText title = field(T.pollTitleHint(), false);
        if (existing != null) title.setText(existing.title);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.setMargins(0, dp(10), 0, dp(6));
        box.addView(title, tlp);

        box.addView(label(T.pollOptions()));
        LinearLayout optBox = new LinearLayout(this);
        optBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(optBox, new LinearLayout.LayoutParams(-1, -2));
        final java.util.List<EditText> optFields = new java.util.ArrayList<>();
        Runnable addField = () -> {
            EditText f = field(T.pollOption() + " " + (optFields.size() + 1), false);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
            flp.setMargins(0, dp(6), 0, 0);
            optBox.addView(f, flp);
            optFields.add(f);
        };
        if (existing != null && !existing.options.isEmpty()) {
            for (String o : existing.options) { addField.run(); optFields.get(optFields.size() - 1).setText(o); }
        } else { addField.run(); addField.run(); }

        TextView more = text("+ " + T.pollAddOption(), 15, Pal.accent, true);
        more.setPadding(dp(4), dp(12), dp(4), dp(4));
        more.setOnClickListener(v -> addField.run());
        box.addView(more);

        LinearLayout buttons = buttonRow();
        TextView cancel = button(T.cancel(), false);
        cancel.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, cancel, true);
        TextView save = button(existing == null ? T.create() : T.save(), true);
        save.setOnClickListener(v -> {
            String tt = title.getText().toString().trim();
            if (tt.isEmpty()) { toast(T.pollNeedTitle()); return; }
            java.util.List<String> opts = new java.util.ArrayList<>();
            for (EditText f : optFields) {
                String o = f.getText().toString().trim();
                if (!o.isEmpty()) opts.add(o);
            }
            if (opts.size() < 2) { toast(T.pollNeedOptions()); return; }
            String id = existing != null ? existing.id : newId();
            app.net.savePoll(id, tt, opts, false);
            dlg.dismiss();
        });
        addTo(buttons, save, false);
        addRow(box, buttons, 16);
        showScrollDialog(dlg, box);
    }

    private static String newId() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

}
