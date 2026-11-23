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

/** Gruppen-Chat (nur Text). Nachrichten sind mit dem gemeinsamen Gruppenschlüssel verschlüsselt. */
public class GroupChatActivity extends Base implements Net.Listener {

    private String gid;
    private LinearLayout header, list;
    private TextView title, sub;
    private ScrollView scroll;
    private EditText input;
    private LinearLayout bar;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        gid = getIntent().getStringExtra("gid");
        if (gid == null || app.db.group(gid) == null) { finish(); return; }

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
        title = text("", 19, Pal.text, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(title);
        sub = text("", 13, Pal.dim, false);
        titles.addView(sub);
        LinearLayout tWrap = new LinearLayout(this);
        tWrap.addView(titles, new LinearLayout.LayoutParams(-1, -2));
        tWrap.setOnClickListener(v -> openInfo());
        header.addView(tWrap, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(header);

        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        input = field(T.messageHint(), true);
        bar.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));
        Ico send = new Ico(this, Ico.SEND);
        send.setOnClickListener(v -> doSend());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(46), dp(46));
        sp.setMargins(dp(6), 0, 0, 0);
        bar.addView(send, sp);
        col.addView(bar);
        onInsets();
    }

    @Override
    protected void onInsets() {
        if (header == null) return;
        header.setPadding(dp(4), insetTop + dp(8), dp(12), dp(8));
        list.setPadding(dp(12), dp(8), dp(12), dp(12));
        if (bar != null) bar.setPadding(dp(8), dp(6), dp(8), insetBottom + dp(6));
    }

    @Override
    protected void onResume() {
        super.onResume();
        app.net.acquire("ui");
        app.net.addListener(this);
        reload();
        app.db.markGroupRead(gid);
    }

    @Override
    protected void onPause() {
        super.onPause();
        app.net.removeListener(this);
        app.net.release("ui");
        app.db.markGroupRead(gid);
    }

    @Override
    public void onChanged(String peer) {
        if (peer == null || peer.equals(gid)) { reload(); app.db.markGroupRead(gid); }
    }

    @Override
    public void onStatus() { }

    private void doSend() {
        String t = input.getText().toString().trim();
        if (t.isEmpty()) return;
        input.setText("");
        app.net.sendGroup(gid, t, null);
    }

    private void reload() {
        Db.Group g = app.db.group(gid);
        if (g == null) { finish(); return; }
        title.setText(g.name);
        sub.setText(T.groupMembers(app.db.memberPubs(gid).size()));
        list.removeAllViews();
        String me = app.keys.pubHex();
        List<Db.Msg> msgs = app.db.messages(gid);
        String lastSender = null;
        for (Db.Msg m : msgs) {
            boolean showName = !m.outgoing && !java.util.Objects.equals(m.sender, lastSender);
            list.addView(bubble(m, me, showName));
            lastSender = m.sender;
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private View bubble(Db.Msg m, String me, boolean showName) {
        LinearLayout rowBox = new LinearLayout(this);
        rowBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.setMargins(0, dp(2), 0, dp(2));
        rowBox.setLayoutParams(rp);
        rowBox.setGravity(m.outgoing ? Gravity.END : Gravity.START);

        LinearLayout bub = new LinearLayout(this);
        bub.setOrientation(LinearLayout.VERTICAL);
        bub.setPadding(dp(12), dp(8), dp(12), dp(8));
        bub.setBackground(round(m.outgoing ? Pal.accent : Pal.card, m.outgoing ? 0 : Pal.line, 14));
        int max = (int) (getResources().getDisplayMetrics().widthPixels * 0.78f);
        bub.setMinimumWidth(dp(48));

        if (showName) {
            TextView nm = text(app.nameOf(m.sender), 12, Pal.accent, true);
            bub.addView(nm);
        }
        TextView body = text(m.body, 16, m.outgoing ? Pal.onAccent : Pal.text, false);
        body.setLineSpacing(0, 1.15f);
        bub.addView(body);
        TextView tm = text(time(m.ts), 10, m.outgoing ? Pal.onAccent : Pal.dim, false);
        tm.setAlpha(m.outgoing ? 0.8f : 1f);
        tm.setGravity(Gravity.END);
        bub.addView(tm);

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.width = Math.min(max, -2);
        FrameLayout wrap = new FrameLayout(this);
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(-2, -2);
        wl.setMarginStart(m.outgoing ? dp(48) : 0);
        wl.setMarginEnd(m.outgoing ? 0 : dp(48));
        wrap.addView(bub, wl);
        rowBox.addView(wrap, new LinearLayout.LayoutParams(-1, -2));
        return rowBox;
    }

    // ------------------------------------------------------------------ Gruppen-Info

    private void openInfo() {
        Db.Group g = app.db.group(gid);
        if (g == null) return;
        String me = app.keys.pubHex();
        boolean iAmAdmin = app.db.isAdmin(gid, me);
        boolean iAmOwner = g.owner.equals(me);

        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.addView(text(g.name, 20, Pal.text, true));
        box.addView(label(T.groupMembers(app.db.memberPubs(gid).size())));

        for (Db.Member mem : app.db.members(gid)) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(2), dp(7), dp(2), dp(7));
            Db.Contact c = app.db.contact(mem.pub);
            String nm = mem.pub.equals(me) ? T.youName() : app.nameOf(mem.pub);
            r.addView(avatarWithDot(mem.pub, nm, c != null ? c.avatar : null, false, 36,
                    c != null ? c.accBehind : -1, c != null ? c.accFront : -1, c != null ? c.accFrame : -1),
                    new LinearLayout.LayoutParams(dp(36), dp(36)));
            TextView tn = text(nm, 16, Pal.text, false);
            tn.setPadding(dp(10), 0, 0, 0);
            tn.setSingleLine(true);
            tn.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(tn, new LinearLayout.LayoutParams(0, -2, 1f));
            if (mem.pub.equals(g.owner)) r.addView(badge(T.groupOwner()));
            else if (mem.admin) r.addView(badge(T.groupAdmin()));
            // Eigentümer kann Admin-Rolle umschalten (nicht bei sich selbst).
            if (iAmOwner && !mem.pub.equals(g.owner)) {
                final boolean makeAdmin = !mem.admin;
                r.setOnClickListener(v -> {
                    dlg.dismiss();
                    app.net.setGroupAdmin(gid, mem.pub, makeAdmin);
                    toast(makeAdmin ? T.groupNowAdmin() : T.groupNoAdmin());
                });
            }
            box.addView(r, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout buttons = buttonRow();
        TextView close = button(T.close(), false);
        close.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, close, true);
        if (iAmAdmin) {
            TextView add = button(T.groupAddMembers(), true);
            add.setOnClickListener(v -> { dlg.dismiss(); openAddMembers(); });
            addTo(buttons, add, false);
        }
        addRow(box, buttons, 16);

        TextView leave = text(T.leaveGroup(), 15, Pal.red, true);
        leave.setGravity(Gravity.CENTER);
        leave.setPadding(0, dp(14), 0, dp(4));
        leave.setOnClickListener(v -> { dlg.dismiss(); confirm(T.leaveGroupQ(), T.leaveGroup(), () -> { app.net.leaveGroup(gid); finish(); }); });
        box.addView(leave, new LinearLayout.LayoutParams(-1, -2));

        showScrollDialog(dlg, box);
    }

    private TextView badge(String s) {
        TextView b = text(s, 11, Pal.onAccent, true);
        b.setPadding(dp(8), dp(2), dp(8), dp(2));
        b.setBackground(round(Pal.accent, 0, 10));
        return b;
    }

    /** Mitglieder aus den eigenen Kontakten hinzufügen (die noch nicht in der Gruppe sind). */
    private void openAddMembers() {
        java.util.List<String> inGroup = app.db.memberPubs(gid);
        java.util.List<Db.Contact> cs = new java.util.ArrayList<>();
        for (Db.Contact c : app.db.contacts()) if (!inGroup.contains(c.pub)) cs.add(c);
        if (cs.isEmpty()) { toast(T.groupNoneToAdd()); return; }
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.addView(text(T.groupAddMembers(), 20, Pal.text, true));
        final java.util.Set<String> sel = new java.util.HashSet<>();
        for (Db.Contact c : cs) {
            String nm = app.nameOf(c.pub);
            LinearLayout rr = new LinearLayout(this);
            rr.setGravity(Gravity.CENTER_VERTICAL);
            rr.setPadding(dp(4), dp(8), dp(4), dp(8));
            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setOnCheckedChangeListener((bv, on) -> { if (on) sel.add(c.pub); else sel.remove(c.pub); });
            rr.addView(cb, new LinearLayout.LayoutParams(dp(36), -2));
            TextView tn = text(nm, 16, Pal.text, false);
            tn.setPadding(dp(10), 0, 0, 0);
            rr.addView(tn, new LinearLayout.LayoutParams(0, -2, 1f));
            rr.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
            box.addView(rr, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout buttons = buttonRow();
        TextView cancel = button(T.cancel(), false);
        cancel.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, cancel, true);
        TextView ok = button(T.groupAdd(), true);
        ok.setOnClickListener(v -> {
            if (sel.isEmpty()) { dlg.dismiss(); return; }
            app.net.addGroupMembers(gid, new java.util.ArrayList<>(sel));
            dlg.dismiss();
            reload();
        });
        addTo(buttons, ok, false);
        addRow(box, buttons, 16);
        showScrollDialog(dlg, box);
    }
}
