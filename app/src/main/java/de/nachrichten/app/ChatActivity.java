package de.nachrichten.app;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import de.nachrichten.crypto.Bech32;
import de.nachrichten.crypto.Event;

/** Ein Chat mit einem Kontakt. */
public class ChatActivity extends Base implements Net.Listener {

    private static final int MAX_SHOWN = 400;

    private String peer;
    private LinearLayout header, list, inputBar;
    private ScrollView scroll;
    private EditText input;
    private Ico send;
    private TextView nameView;
    private int shownCount = -1;
    private String lastShownId;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        peer = getIntent().getStringExtra("peer");
        if (peer == null || !app.keys.has()) { finish(); return; }
        if (app.db.contact(peer) == null) app.db.addContact(peer, "");
        build();
    }

    @Override
    public void finish() {
        if (isTaskRoot()) startActivity(new Intent(this, MainActivity.class));
        super.finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String p = intent.getStringExtra("peer");
        if (p != null && !p.equals(peer)) {
            peer = p;
            if (app.db.contact(peer) == null) app.db.addContact(peer, "");
            build();
            root.requestApplyInsets();
            onResumeWork();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        app.net.addListener(this);
        app.net.acquire("ui");
        onResumeWork();
    }

    private void onResumeWork() {
        app.net.openChat = peer;
        app.db.markRead(peer);
        Notifs.clear(this, peer);
        shownCount = -1;
        reload();
    }

    @Override
    protected void onPause() {
        super.onPause();
        app.net.openChat = null;
        app.net.removeListener(this);
        app.net.release("ui");
        app.db.markRead(peer);
    }

    @Override
    protected void onInsets() {
        if (header == null) return;
        header.setPadding(dp(4), insetTop + dp(6), dp(8), dp(6));
        inputBar.setPadding(dp(10), dp(8), dp(10), insetBottom + dp(8));
        scrollToEnd();
    }

    private void build() {
        root.removeAllViews();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // Kopfzeile
        header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Pal.card);
        Ico back = new Ico(this, Ico.BACK);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(48)));
        Db.Contact c = app.db.contact(peer);
        Avatar av = new Avatar(peer, c != null && c.name != null && !c.name.isEmpty() ? c.name : "?");
        header.addView(av, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, dp(8), 0);
        nameView = text(app.nameOf(peer), 17, Pal.text, true);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(nameView);
        LinearLayout enc = new LinearLayout(this);
        enc.setGravity(Gravity.CENTER_VERTICAL);
        Ico lock = new Ico(this, Ico.LOCK);
        lock.color = Pal.dim;
        enc.addView(lock, new LinearLayout.LayoutParams(dp(14), dp(16)));
        TextView et = text(T.s("Encrypted", "Verschlüsselt", "Cifrado", "Зашифровано", "Šifrirano"), 12, Pal.dim, false);
        et.setPadding(dp(4), 0, 0, 0);
        enc.addView(et);
        titles.addView(enc);
        titles.setOnClickListener(v -> contactMenu());
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        Ico more = new Ico(this, Ico.MORE);
        more.setOnClickListener(v -> contactMenu());
        header.addView(more, new LinearLayout.LayoutParams(dp(40), dp(48)));
        col.addView(header);
        View line = new View(this);
        line.setBackgroundColor(Pal.line);
        col.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2)));

        // Nachrichten
        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(10), dp(8), dp(10), dp(8));
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Eingabe
        inputBar = new LinearLayout(this);
        inputBar.setGravity(Gravity.BOTTOM);
        input = new EditText(this);
        input.setHint(T.messageHint());
        input.setTextColor(Pal.text);
        input.setHintTextColor(Pal.dim);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setBackground(round(Pal.card, Pal.line, 22));
        input.setPadding(dp(16), dp(11), dp(16), dp(11));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(6);
        input.setMinHeight(dp(46));
        inputBar.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));
        send = new Ico(this, Ico.SEND);
        send.setOnClickListener(v -> doSend());
        send.setContentDescription(T.messageHint());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(46), dp(46));
        sp.setMargins(dp(8), 0, 0, 0);
        inputBar.addView(send, sp);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                boolean on = s.toString().trim().length() > 0;
                if (on != send.on) { send.on = on; send.invalidate(); }
            }
        });
        col.addView(inputBar);
        onInsets();
    }

    private void doSend() {
        String t = input.getText().toString().trim();
        if (t.isEmpty()) return;
        if (t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60000) t = t.substring(0, 20000);
        app.net.send(peer, t);
        input.setText("");
    }

    @Override
    public void onChanged(String p) {
        if (p == null || p.equals(peer)) {
            nameView.setText(app.nameOf(peer));
            reload();
            app.db.markRead(peer);
        }
    }

    @Override
    public void onStatus() { }

    // ------------------------------------------------------------------ Liste

    private boolean atBottom() {
        View child = scroll.getChildAt(0);
        return child == null || scroll.getScrollY() + scroll.getHeight() >= child.getHeight() - dp(80);
    }

    private void scrollToEnd() {
        if (scroll != null) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void reload() {
        if (list == null) return;
        boolean stick = shownCount < 0 || atBottom();
        List<Db.Msg> all = app.db.messages(peer);
        List<Db.Msg> msgs = all.size() > MAX_SHOWN ? all.subList(all.size() - MAX_SHOWN, all.size()) : all;
        list.removeAllViews();
        if (msgs.isEmpty()) {
            TextView e = text(T.chatEmpty(), 14, Pal.dim, false);
            e.setGravity(Gravity.CENTER);
            e.setLineSpacing(0, 1.25f);
            e.setPadding(dp(20), dp(14), dp(20), dp(14));
            e.setBackground(round(Pal.card, Pal.line, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.setMargins(0, dp(40), 0, 0);
            list.addView(e, lp);
        }
        long lastDay = -1;
        Db.Msg prev = null;
        for (Db.Msg m : msgs) {
            long day = dayKey(m.ts);
            if (day != lastDay) {
                list.addView(dayChip(m.ts));
                lastDay = day;
                prev = null;
            }
            boolean grouped = prev != null && prev.outgoing == m.outgoing && m.ts - prev.ts < 120000;
            list.addView(bubble(m, grouped));
            prev = m;
        }
        String newLast = msgs.isEmpty() ? null : msgs.get(msgs.size() - 1).id;
        boolean newMessage = newLast != null && !newLast.equals(lastShownId);
        boolean mineNew = newMessage && msgs.get(msgs.size() - 1).outgoing;
        lastShownId = newLast;
        shownCount = msgs.size();
        if (stick || mineNew) scrollToEnd();
    }

    private static long dayKey(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR);
    }

    private View dayChip(long ts) {
        long today = dayKey(System.currentTimeMillis());
        long d = dayKey(ts);
        String label;
        if (d == today) label = T.today();
        else if (d == dayKey(System.currentTimeMillis() - 86400000L)) label = T.yesterday();
        else label = DateFormat.getDateInstance(DateFormat.MEDIUM, T.locale()).format(new Date(ts));
        TextView t = text(label, 12, Pal.dim, true);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        t.setBackground(round(Pal.card, 0, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, dp(12), 0, dp(8));
        t.setLayoutParams(lp);
        return t;
    }

    private View bubble(Db.Msg m, boolean grouped) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(m.outgoing ? Pal.bubbleOut : Pal.bubbleIn);
        float r = dp(16), small = dp(5);
        if (m.outgoing) bg.setCornerRadii(new float[]{r, r, grouped ? r : small, grouped ? r : small, r, r, r, r});
        else bg.setCornerRadii(new float[]{grouped ? r : small, grouped ? r : small, r, r, r, r, r, r});
        if (!m.outgoing) bg.setStroke(Math.max(1, dp(1) / 2), Pal.line);
        b.setBackground(bg);
        b.setPadding(dp(12), dp(8), dp(12), dp(6));

        TextView body = text(m.body, 16, m.outgoing ? Pal.onOut : Pal.text, false);
        body.setLineSpacing(0, 1.1f);
        body.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        body.setLinkTextColor(Pal.accent);
        b.addView(body);

        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        TextView tm = text(time(m.ts), 11, m.outgoing ? (Pal.light ? 0xFF4A6B62 : 0xFF9CC3B7) : Pal.dim, false);
        meta.addView(tm);
        if (m.outgoing) {
            Ico st = new Ico(this, m.status == Db.SENT ? Ico.CHECK : Ico.CLOCK);
            st.color = tm.getCurrentTextColor();
            st.setContentDescription(m.status == Db.SENT ? "sent" : T.pendingInfo());
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(16), dp(14));
            sp.setMargins(dp(3), 0, 0, 0);
            meta.addView(st, sp);
        }
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.gravity = Gravity.END;
        mp.setMargins(dp(24), dp(2), 0, 0);
        b.addView(meta, mp);

        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.8f);
        body.setMaxWidth(maxW - dp(24));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = m.outgoing ? Gravity.END : Gravity.START;
        lp.setMargins(m.outgoing ? dp(48) : 0, dp(grouped ? 2 : 6), m.outgoing ? 0 : dp(48), 0);
        b.setLayoutParams(lp);

        b.setOnLongClickListener(v -> {
            menu(null, new String[]{T.copy(), T.delete()}, new int[]{0, Pal.red}, i -> {
                if (i == 0) copy(m.body);
                else confirm(T.deleteMsgQ(), T.delete(), () -> { app.db.deleteMessage(m.id); reload(); });
            });
            return true;
        });
        return b;
    }

    // ------------------------------------------------------------------ Kontakt

    private void contactMenu() {
        menu(app.nameOf(peer), new String[]{T.rename(), T.showCode(), T.deleteChat()}, new int[]{0, 0, Pal.red}, i -> {
            if (i == 0) renameDialog();
            else if (i == 1) showPeerCode();
            else confirm(T.deleteChatQ(), T.delete(), () -> { app.db.deleteContact(peer); finish(); });
        });
    }

    private void renameDialog() {
        android.app.Dialog dlg = dialog();
        LinearLayout box = panel();
        box.addView(text(T.rename(), 19, Pal.text, true));
        box.addView(label(T.name()));
        EditText name = field(T.name(), false);
        Db.Contact c = app.db.contact(peer);
        if (c != null) name.setText(c.name);
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout buttons = buttonRow();
        TextView cancel = button(T.cancel(), false);
        cancel.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, cancel, true);
        TextView ok = button(T.save(), true);
        ok.setOnClickListener(v -> {
            app.db.rename(peer, name.getText().toString().trim());
            dlg.dismiss();
            build();
            root.requestApplyInsets();
            shownCount = -1;
            reload();
        });
        addTo(buttons, ok, false);
        addRow(box, buttons, 20);
        showDialog(dlg, box);
    }

    private void showPeerCode() {
        String npub = Bech32.encode("npub", Event.unhex(peer));
        android.app.Dialog dlg = dialog();
        LinearLayout box = panel();
        box.addView(text(app.nameOf(peer), 19, Pal.text, true));
        box.addView(label(T.textCode()));
        TextView code = text(npub, 14, Pal.text, false);
        code.setTypeface(android.graphics.Typeface.MONOSPACE);
        code.setTextIsSelectable(true);
        code.setBackground(round(Pal.field, Pal.line, 10));
        code.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.addView(code);
        LinearLayout buttons = buttonRow();
        TextView close = button(T.close(), false);
        close.setOnClickListener(v -> dlg.dismiss());
        addTo(buttons, close, true);
        TextView cp = button(T.copy(), true);
        cp.setOnClickListener(v -> copy(npub));
        addTo(buttons, cp, false);
        addRow(box, buttons, 18);
        showDialog(dlg, box);
    }
}
