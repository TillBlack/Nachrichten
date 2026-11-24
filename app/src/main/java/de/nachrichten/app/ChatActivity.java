package de.nachrichten.app;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.nachrichten.crypto.Bech32;
import de.nachrichten.crypto.Event;
import de.nachrichten.crypto.Proto;

/** Ein Chat mit einem Kontakt: Nachrichten, Antworten, Anhänge. */
public class ChatActivity extends Base implements Net.Listener {

    private static final int MAX_SHOWN = 400;
    private static final int REQ_PHOTO = 11, REQ_FILE = 12, REQ_SAVE = 13, REQ_AUDIO = 14;

    // Sprachnachrichten: Aufnahme + Wiedergabe
    private LinearLayout inputRow, recBar;
    private Ico mic;
    private TextView recTime;
    private android.media.MediaRecorder recorder;
    private File recFile;
    private boolean recording;
    private long recStart;
    private Runnable recTick;
    private android.media.MediaPlayer player;
    private String playingPath;
    private Ico playingBtn;
    private static final LruCache<String, Bitmap> THUMBS = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private String peer;
    private TextView subtitle;
    private View headerAvatar;
    private View typingRow;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable stopTyping = this::hideTyping;
    private LinearLayout header, list, inputBar, replyBar;
    private ScrollView scroll;
    private EditText input;
    private Ico send;
    private TextView nameView, replyName, replyText;
    private int shownCount = -1;
    private String lastShownId;
    private String replyTo;
    private String pendingSavePath;
    private final Map<String, Db.Msg> byId = new HashMap<>();
    private final Map<String, View> viewById = new HashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        peer = getIntent().getStringExtra("peer");
        if (peer == null || !app.keys.has()) { finish(); return; }
        if (app.db.contact(peer) == null) app.db.addContact(peer, "");
        build();
    }

    /** Wurde gerade ein Chat verlassen? (für den Update-Hinweis in der Liste) */
    static boolean justLeft;

    @Override
    public void finish() {
        justLeft = true;
        if (isTaskRoot()) startActivity(new Intent(this, MainActivity.class));
        super.finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String p = intent.getStringExtra("peer");
        if (p != null && !p.equals(peer)) {
            peer = p;
            replyTo = null;
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
        app.net.chatSeen(peer);
    }

    @Override
    protected void onPause() {
        super.onPause();
        app.net.openChat = null;
        app.net.removeListener(this);
        app.net.release("ui");
        app.db.markRead(peer);
        stopPlayer();
        if (recording) cancelRecording();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        shownCount = -1;
        reload();
    }

    @Override
    protected void onInsets() {
        if (header == null) return;
        header.setPadding(dp(4), insetTop + dp(6), dp(8), dp(6));
        inputBar.setPadding(dp(8), dp(6), dp(10), insetBottom + dp(8));
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
        View av = avatarWithDot(peer, c != null && c.name != null && !c.name.isEmpty() ? c.name : "?", c != null ? c.avatar : null, app.net.isOnline(peer), 38,
                c != null ? c.accBehind : -1, c != null ? c.accFront : -1, c != null ? c.accFrame : -1);
        av.setOnClickListener(v -> showProfile(peer, false));
        headerAvatar = av;
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
        TextView et = text(T.encrypted(), 12, Pal.dim, false);
        et.setPadding(dp(4), 0, 0, 0);
        enc.addView(et);
        subtitle = et;
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
        list.setClipChildren(false);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Eingabe mit Antwort-Leiste
        inputBar = new LinearLayout(this);
        inputBar.setOrientation(LinearLayout.VERTICAL);

        replyBar = new LinearLayout(this);
        replyBar.setGravity(Gravity.CENTER_VERTICAL);
        replyBar.setBackground(round(Pal.card, Pal.line, 12));
        replyBar.setPadding(0, 0, dp(4), 0);
        View stripe = new View(this);
        stripe.setBackground(round(Pal.accent, 0, 2));
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(dp(4), dp(36));
        stp.setMargins(dp(8), dp(8), dp(10), dp(8));
        replyBar.addView(stripe, stp);
        LinearLayout rt = new LinearLayout(this);
        rt.setOrientation(LinearLayout.VERTICAL);
        replyName = text("", 13, Pal.accent, true);
        replyName.setSingleLine(true);
        replyText = text("", 14, Pal.dim, false);
        replyText.setSingleLine(true);
        replyText.setEllipsize(TextUtils.TruncateAt.END);
        rt.addView(replyName);
        rt.addView(replyText);
        replyBar.addView(rt, new LinearLayout.LayoutParams(0, -2, 1f));
        Ico close = new Ico(this, Ico.CLOSE);
        close.color = Pal.dim;
        close.setOnClickListener(v -> setReply(null));
        replyBar.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        replyBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams rbp = new LinearLayout.LayoutParams(-1, -2);
        rbp.setMargins(dp(2), 0, dp(2), dp(6));
        inputBar.addView(replyBar, rbp);

        inputRow = new LinearLayout(this);
        LinearLayout row = inputRow;
        row.setGravity(Gravity.BOTTOM);
        Ico attach = new Ico(this, Ico.ATTACH);
        attach.color = Pal.dim;
        attach.setContentDescription(T.attachFile());
        attach.setOnClickListener(v -> attachMenu());
        row.addView(attach, new LinearLayout.LayoutParams(dp(40), dp(46)));
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
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1f);
        ip.setMargins(dp(2), 0, 0, 0);
        row.addView(input, ip);
        mic = new Ico(this, Ico.MIC);
        mic.color = Pal.dim;
        mic.setContentDescription(T.voiceRecord());
        mic.setOnClickListener(v -> startRecording());
        LinearLayout.LayoutParams micp = new LinearLayout.LayoutParams(dp(46), dp(46));
        micp.setMargins(dp(4), 0, 0, 0);
        row.addView(mic, micp);
        send = new Ico(this, Ico.SEND);
        send.setOnClickListener(v -> doSend());
        send.setContentDescription(T.messageHint());
        send.setVisibility(View.GONE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(46), dp(46));
        sp.setMargins(dp(4), 0, 0, 0);
        row.addView(send, sp);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                boolean on = s.toString().trim().length() > 0;
                if (s.toString().trim().length() >= 2) app.net.typing(peer);
                if (on != send.on) { send.on = on; send.invalidate(); }
                send.setVisibility(on ? View.VISIBLE : View.GONE);
                mic.setVisibility(on ? View.GONE : View.VISIBLE);
            }
        });
        inputBar.addView(row);

        // Aufnahmeleiste (verborgen, bis aufgenommen wird)
        recBar = new LinearLayout(this);
        recBar.setGravity(Gravity.CENTER_VERTICAL);
        recBar.setVisibility(View.GONE);
        recBar.setPadding(dp(10), dp(8), dp(10), dp(8));
        View dot = new View(this);
        dot.setBackground(round(Pal.red, 0, 6));
        recBar.addView(dot, new LinearLayout.LayoutParams(dp(11), dp(11)));
        recTime = text("0:00", 15, Pal.text, true);
        recTime.setPadding(dp(10), 0, 0, 0);
        recBar.addView(recTime);
        TextView recLbl = text(T.voiceRecording(), 13, Pal.dim, false);
        recLbl.setPadding(dp(10), 0, 0, 0);
        recBar.addView(recLbl);
        recBar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        Ico cancelRec = new Ico(this, Ico.CLOSE);
        cancelRec.color = Pal.dim;
        cancelRec.setOnClickListener(v -> cancelRecording());
        recBar.addView(cancelRec, new LinearLayout.LayoutParams(dp(44), dp(44)));
        Ico stopRec = new Ico(this, Ico.SEND);
        stopRec.on = true;
        stopRec.setOnClickListener(v -> stopAndSend());
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(dp(46), dp(46));
        stp.setMargins(dp(4), 0, 0, 0);
        recBar.addView(stopRec, stp);
        inputBar.addView(recBar);

        col.addView(inputBar);
        onInsets();
    }

    private void doSend() {
        String t = input.getText().toString().trim();
        if (t.isEmpty()) return;
        if (t.length() > 20000) t = t.substring(0, 20000);
        app.net.send(peer, t, replyTo);
        input.setText("");
        setReply(null);
    }

    @Override
    public void onChanged(String p) {
        if (p == null || p.equals(peer)) {
            String before = lastShownId;
            nameView.setText(app.nameOf(peer));
            reload();
            app.db.markRead(peer);
            Db.Msg last = lastShownId == null ? null : byId.get(lastShownId);
            if (last != null && !last.outgoing && !lastShownId.equals(before)) hideTyping();
            if (typingRow != null && typingRow.getParent() == null) list.addView(typingRow);
            app.net.chatSeen(peer);
        }
    }

    @Override
    public void onStatus() { }

    @Override
    public void onPresence(String p) {
        if (p.equals(peer) && headerAvatar != null && headerAvatar.getParent() instanceof LinearLayout) {
            int i = header.indexOfChild(headerAvatar);
            if (i >= 0) {
                Db.Contact c = app.db.contact(peer);
                header.removeViewAt(i);
                View av = avatarWithDot(peer, c != null && c.name != null && !c.name.isEmpty() ? c.name : "?", c != null ? c.avatar : null, app.net.isOnline(peer), 38,
                        c != null ? c.accBehind : -1, c != null ? c.accFront : -1, c != null ? c.accFrame : -1);
                av.setOnClickListener(v -> showProfile(peer, false));
                headerAvatar = av;
                header.addView(av, i, new LinearLayout.LayoutParams(dp(38), dp(38)));
            }
        }
    }

    @Override
    public void onTyping(String p) {
        if (!p.equals(peer)) return;
        boolean wasBottom = atBottom();
        if (typingRow == null || typingRow.getParent() == null) {
            typingRow = typingBubble();
            list.addView(typingRow);
            if (wasBottom) scrollToEnd();
        }
        subtitle.setText(T.typing());
        subtitle.setTextColor(Pal.accent);
        ui.removeCallbacks(stopTyping);
        ui.postDelayed(stopTyping, 6000);
    }

    private void hideTyping() {
        ui.removeCallbacks(stopTyping);
        if (typingRow != null && typingRow.getParent() != null) list.removeView(typingRow);
        typingRow = null;
        if (subtitle != null) {
            subtitle.setText(T.encrypted());
            subtitle.setTextColor(Pal.dim);
        }
    }

    private View typingBubble() {
        FrameLayout row = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Pal.bubbleIn);
        float r = dp(16), small = dp(5);
        bg.setCornerRadii(new float[]{small, small, r, r, r, r, r, r});
        bg.setStroke(Math.max(1, dp(1) / 2), Pal.line);
        TypingDots dots = new TypingDots(this);
        dots.setBackground(bg);
        row.addView(dots, new FrameLayout.LayoutParams(dp(64), dp(36), Gravity.START));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, 0);
        row.setLayoutParams(lp);
        return row;
    }

    // ------------------------------------------------------------------ Antworten

    private void setReply(String id) {
        Db.Msg m = id == null ? null : byId.get(id);
        if (m == null) {
            replyTo = null;
            replyBar.setVisibility(View.GONE);
            return;
        }
        replyTo = id;
        replyName.setText(m.outgoing ? T.youName() : app.nameOf(peer));
        replyText.setText(snippet(m));
        replyBar.setVisibility(View.VISIBLE);
        input.requestFocus();
        android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.showSoftInput(input, 0);
    }

    private String snippet(Db.Msg m) {
        if (m.kind == Db.IMAGE) return T.photo();
        if (isVoice(m)) return T.voiceMessage();
        if (m.kind == Db.FILE) return T.fileLabel() + (m.fileName != null ? ": " + m.fileName : "");
        return m.body.replace('\n', ' ');
    }

    // ------------------------------------------------------------------ Liste

    private boolean atBottom() {
        View child = scroll.getChildAt(0);
        return child == null || scroll.getScrollY() + scroll.getHeight() >= child.getHeight() - dp(80);
    }

    private void scrollToEnd() {
        if (scroll != null) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private int bubbleMaxWidth() {
        int w = getResources().getDisplayMetrics().widthPixels;
        return Math.min((int) (w * 0.78f), dp(520));
    }

    private void reload() {
        if (list == null) return;
        boolean stick = shownCount < 0 || atBottom();
        List<Db.Msg> all = app.db.messages(peer);
        List<Db.Msg> msgs = all.size() > MAX_SHOWN ? all.subList(all.size() - MAX_SHOWN, all.size()) : all;
        byId.clear();
        viewById.clear();
        for (Db.Msg m : all) byId.put(m.id, m);
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
            View row = row(m, grouped);
            viewById.put(m.id, row);
            list.addView(row);
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

    /** Eine Zeile: Blase in einer wischbaren Hülle. */
    private View row(Db.Msg m, boolean grouped) {
        SwipeRow row = new SwipeRow(this);
        View b = bubble(m, grouped);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, m.outgoing ? Gravity.END : Gravity.START);
        lp.setMargins(m.outgoing ? dp(48) : 0, 0, m.outgoing ? 0 : dp(48), 0);
        row.addView(b, lp);
        row.onSwipe = () -> setReply(m.id);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.setMargins(0, dp(grouped ? 2 : 6), 0, 0);
        row.setLayoutParams(rp);
        return row;
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
        boolean isImage = m.kind == Db.IMAGE && m.complete();
        b.setPadding(dp(isImage ? 4 : 12), dp(isImage ? 4 : 8), dp(isImage ? 4 : 12), dp(6));
        int maxW = bubbleMaxWidth();
        int textColor = m.outgoing ? Pal.onOut : Pal.text;
        int metaColor = m.outgoing ? (Pal.light ? 0xFF4A6B62 : 0xFF9CC3B7) : Pal.dim;

        // Zitat
        if (m.replyTo != null) {
            View q = quote(m.replyTo, m.outgoing, maxW);
            LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-2, -2);
            qp.setMargins(isImage ? dp(4) : 0, isImage ? dp(4) : 0, isImage ? dp(4) : 0, dp(6));
            b.addView(q, qp);
        }

        if (m.kind == Db.TEXT) {
            MsgText body = new MsgText(this, maxW - dp(24));
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            body.setTextColor(textColor);
            body.setLineSpacing(0, 1.1f);
            body.setLinkTextColor(m.outgoing ? (Pal.light ? 0xFF1B5E4F : 0xFF8FE0CB) : Pal.accent);
            android.text.SpannableString sp = new android.text.SpannableString(m.body);
            android.text.util.Linkify.addLinks(sp, android.text.util.Linkify.WEB_URLS);
            body.setText(sp, TextView.BufferType.SPANNABLE);
            body.setOnLongClickListener(v -> { messageMenu(m); return true; });
            b.addView(body, new LinearLayout.LayoutParams(-2, -2));
        } else if (!m.complete()) {
            TextView t = text(T.receiving() + " " + (m.kind == Db.IMAGE ? T.photo() : T.fileLabel())
                    + "  " + Math.min(m.have, m.total) + "/" + Math.max(1, m.total), 14, metaColor, false);
            b.addView(t, new LinearLayout.LayoutParams(-2, -2));
        } else if (m.kind == Db.IMAGE) {
            ImageView iv = new ImageView(this);
            Bitmap bm = thumb(m.filePath, maxW - dp(8));
            if (bm != null) {
                iv.setImageBitmap(bm);
                int w = Math.min(maxW - dp(8), dp(300));
                int h = Math.round(w * (bm.getHeight() / (float) bm.getWidth()));
                h = Math.min(h, dp(360));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setOutlineProvider(new ViewOutlineProvider() {
                    @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12)); }
                });
                iv.setClipToOutline(true);
                b.addView(iv, new LinearLayout.LayoutParams(w, h));
            } else {
                b.addView(text(T.photo(), 15, textColor, false), new LinearLayout.LayoutParams(-2, -2));
            }
            iv.setOnClickListener(v -> viewImage(m));
            iv.setOnLongClickListener(v -> { messageMenu(m); return true; });
        } else if (isVoice(m)) {
            b.addView(voicePlayer(m, textColor, metaColor), new LinearLayout.LayoutParams(-2, -2));
        } else {
            LinearLayout f = new LinearLayout(this);
            f.setGravity(Gravity.CENTER_VERTICAL);
            Ico fi = new Ico(this, Ico.FILE);
            fi.color = textColor;
            f.addView(fi, new LinearLayout.LayoutParams(dp(32), dp(40)));
            LinearLayout ft = new LinearLayout(this);
            ft.setOrientation(LinearLayout.VERTICAL);
            ft.setPadding(dp(6), 0, 0, 0);
            TextView fn = text(m.fileName, 15, textColor, true);
            fn.setMaxWidth(maxW - dp(70));
            fn.setMaxLines(2);
            fn.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            ft.addView(fn);
            ft.addView(text(size(m.fileSize), 12, metaColor, false));
            f.addView(ft);
            f.setOnClickListener(v -> openFile(m));
            f.setOnLongClickListener(v -> { messageMenu(m); return true; });
            b.addView(f, new LinearLayout.LayoutParams(-2, -2));
        }

        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        TextView tm = text(time(m.ts), 11, metaColor, false);
        meta.addView(tm);
        if (m.outgoing) {
            Ico st = new Ico(this, m.status == Db.PENDING ? Ico.CLOCK : m.status == Db.SENT ? Ico.CHECK : Ico.CHECK2);
            st.color = m.status == Db.READ ? (Pal.light ? 0xFF1E88E5 : 0xFF5BC0F8) : metaColor;
            st.setContentDescription(m.status == Db.PENDING ? T.pendingInfo() : m.status == Db.SENT ? T.statusSent()
                    : m.status == Db.DELIVERED ? T.statusDelivered() : T.statusRead());
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(22), dp(16));
            sp.setMargins(dp(3), 0, 0, 0);
            meta.addView(st, sp);
        }
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.gravity = Gravity.END;
        mp.setMargins(dp(24), dp(2), isImage ? dp(6) : 0, 0);
        b.addView(meta, mp);

        b.setOnLongClickListener(v -> { messageMenu(m); return true; });
        if (m.kind == Db.FILE && m.complete() && !isVoice(m)) b.setOnClickListener(v -> openFile(m));
        return b;
    }

    private View quote(String id, boolean inOutgoing, int maxW) {
        Db.Msg q = byId.get(id);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        int bgc = inOutgoing ? (Pal.light ? 0x14000000 : 0x22000000) : (Pal.light ? 0x0F000000 : 0x18FFFFFF);
        box.setBackground(round(bgc, 0, 8));
        View stripe = new View(this);
        stripe.setBackgroundColor(Pal.accent);
        box.addView(stripe, new LinearLayout.LayoutParams(dp(3), -1));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(8), dp(5), dp(10), dp(6));
        TextView n = text(q == null ? "…" : (q.outgoing ? T.youName() : app.nameOf(peer)), 13, Pal.accent, true);
        n.setSingleLine(true);
        t.addView(n);
        TextView s = text(q == null ? "" : snippet(q), 13, inOutgoing ? Pal.onOut : Pal.dim, false);
        s.setMaxLines(2);
        s.setEllipsize(TextUtils.TruncateAt.END);
        s.setMaxWidth(maxW - dp(40));
        t.addView(s);
        box.addView(t);
        box.setOnClickListener(v -> jumpTo(id));
        return box;
    }

    private void jumpTo(String id) {
        View v = viewById.get(id);
        if (v == null) return;
        scroll.smoothScrollTo(0, Math.max(0, v.getTop() - dp(80)));
        v.setAlpha(0.35f);
        v.animate().alpha(1f).setDuration(700).setStartDelay(250).start();
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024f);
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576f);
    }

    private Bitmap thumb(String path, int targetW) {
        String key = path + "@" + targetW;
        Bitmap b = THUMBS.get(key);
        if (b != null) return b;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= targetW) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            b = BitmapFactory.decodeFile(path, o);
            if (b != null) THUMBS.put(key, b);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Menü je Nachricht

    private void messageMenu(Db.Msg m) {
        boolean file = m.kind != Db.TEXT && m.complete();
        String[] items = file
                ? new String[]{T.reply(), T.saveFile(), T.openWith(), T.delete()}
                : new String[]{T.reply(), T.copy(), T.delete()};
        int[] colors = file ? new int[]{0, 0, 0, Pal.red} : new int[]{0, 0, Pal.red};
        String title = !m.outgoing ? null : m.status == Db.PENDING ? T.pendingInfo() : m.status == Db.SENT ? T.statusSent()
                : m.status == Db.DELIVERED ? T.statusDelivered() : T.statusRead();
        menu(title, items, colors, i -> {
            String it = items[i];
            if (it.equals(T.reply())) setReply(m.id);
            else if (it.equals(T.copy())) copy(m.body);
            else if (it.equals(T.saveFile())) saveFile(m);
            else if (it.equals(T.openWith())) openFile(m);
            else confirm(T.deleteMsgQ(), T.delete(), () -> {
                String path = app.db.deleteMessage(m.id);
                if (path != null && !m.outgoing) new File(path).delete();
                else if (path != null) new File(path).delete();
                if (m.id.equals(replyTo)) setReply(null);
                reload();
            });
        });
    }

    private void viewImage(Db.Msg m) {
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout fl = new FrameLayout(this);
        fl.setBackgroundColor(0xFF000000);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap bm = thumb(m.filePath, Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels));
        iv.setImageBitmap(bm);
        iv.setOnClickListener(v -> d.dismiss());
        fl.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.END);
        bar.setPadding(dp(12), dp(12), dp(12), dp(28));
        TextView save = text(T.saveFile(), 16, 0xFFFFFFFF, true);
        save.setPadding(dp(16), dp(10), dp(16), dp(10));
        save.setBackground(round(0x66000000, 0x88FFFFFF, 20));
        save.setOnClickListener(v -> saveFile(m));
        TextView open = text(T.openWith(), 16, 0xFFFFFFFF, true);
        open.setPadding(dp(16), dp(10), dp(16), dp(10));
        open.setBackground(round(0x66000000, 0x88FFFFFF, 20));
        open.setOnClickListener(v -> openFile(m));
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-2, -2);
        op.setMargins(dp(8), 0, 0, 0);
        bar.addView(save);
        bar.addView(open, op);
        fl.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        d.setContentView(fl);
        d.show();
    }

    private void showBigAvatar(String path) {
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ImageView iv = new ImageView(this);
        iv.setBackgroundColor(0xFF000000);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageBitmap(avatarBitmap(path));
        iv.setOnClickListener(v -> d.dismiss());
        d.setContentView(iv);
        d.show();
    }

    private void openFile(Db.Msg m) {
        if (!m.complete()) return;
        File f = new File(m.filePath);
        Uri u = FilesProvider.uriFor(f);
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, m.fileMime != null ? m.fileMime : "application/octet-stream")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, T.openWith()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (ActivityNotFoundException e) {
            toast(T.noApp());
        }
    }

    private void saveFile(Db.Msg m) {
        if (!m.complete()) return;
        pendingSavePath = m.filePath;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(m.fileMime != null ? m.fileMime : "application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, m.fileName);
        try { startActivityForResult(i, REQ_SAVE); } catch (ActivityNotFoundException e) { toast(T.noApp()); }
    }

    // ------------------------------------------------------------------ Anhängen

    // ------------------------------------------------------------------ Sprachnachrichten

    private void startRecording() {
        if (recording) return;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        try {
            recFile = new File(getCacheDir(), "rec_" + System.currentTimeMillis() + ".m4a");
            recorder = new android.media.MediaRecorder();
            recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(recFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            recStart = System.currentTimeMillis();
            stopPlayer();
            inputRow.setVisibility(View.GONE);
            recBar.setVisibility(View.VISIBLE);
            recTick = new Runnable() {
                public void run() {
                    if (!recording) return;
                    long s = (System.currentTimeMillis() - recStart) / 1000;
                    recTime.setText(s / 60 + ":" + String.format(Locale.US, "%02d", s % 60));
                    ui.postDelayed(this, 300);
                }
            };
            ui.post(recTick);
        } catch (Exception e) {
            toast(T.recordError());
            cleanupRecorder();
            resetRecUI();
        }
    }

    private File finishRecording() {
        recording = false;
        if (recTick != null) ui.removeCallbacks(recTick);
        File f = recFile;
        try {
            if (recorder != null) recorder.stop();
        } catch (Exception e) {
            f = null; // zu kurz oder fehlgeschlagen
        }
        cleanupRecorder();
        return f;
    }

    private void cleanupRecorder() {
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) { }
        recorder = null;
    }

    private void resetRecUI() {
        if (recBar != null) recBar.setVisibility(View.GONE);
        if (inputRow != null) inputRow.setVisibility(View.VISIBLE);
    }

    private void cancelRecording() {
        File f = finishRecording();
        resetRecUI();
        if (f != null) f.delete();
    }

    private void stopAndSend() {
        long dur = System.currentTimeMillis() - recStart;
        File rec = finishRecording();
        resetRecUI();
        if (rec == null) return;
        if (dur < 800) { rec.delete(); toast(T.recordTooShort()); return; }
        String reply = replyTo;
        setReply(null);
        new Thread(() -> {
            try {
                byte[] data = java.nio.file.Files.readAllBytes(rec.toPath());
                rec.delete();
                if (data.length == 0 || data.length > Proto.MAX_FILE) { runOnUiThread(() -> toast(T.tooLarge())); return; }
                String fid = Proto.newFileId();
                File f = new File(app.mediaDir(), fid + "_voice.m4a");
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
                app.net.sendFile(peer, f.getAbsolutePath(), "Sprachnachricht.m4a", "audio/mp4", false, reply);
            } catch (Throwable t) {
                runOnUiThread(() -> toast(T.readError()));
            }
        }).start();
    }

    private static boolean isVoice(Db.Msg m) {
        return m.kind == Db.FILE && m.fileMime != null && m.fileMime.startsWith("audio/");
    }

    private View voicePlayer(Db.Msg m, int textColor, int metaColor) {
        LinearLayout v = new LinearLayout(this);
        v.setGravity(Gravity.CENTER_VERTICAL);
        boolean playingThis = playingPath != null && playingPath.equals(m.filePath);
        Ico btn = new Ico(this, playingThis ? Ico.PAUSE : Ico.PLAY);
        btn.color = textColor;
        v.addView(btn, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout col2 = new LinearLayout(this);
        col2.setOrientation(LinearLayout.VERTICAL);
        col2.setPadding(dp(8), 0, dp(4), 0);
        View track = new View(this);
        track.setBackground(round(Pal.line, 0, 2));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(130), dp(3));
        tp.setMargins(0, 0, 0, dp(5));
        col2.addView(track, tp);
        col2.addView(text(T.voiceMessage() + " · " + formatDur(durationOf(m.filePath)), 12, metaColor, false));
        v.addView(col2);
        View.OnClickListener clk = x -> togglePlay(m.filePath, btn);
        btn.setOnClickListener(clk);
        v.setOnClickListener(clk);
        v.setOnLongClickListener(x -> { messageMenu(m); return true; });
        return v;
    }

    private void togglePlay(String path, Ico btn) {
        try {
            if (playingPath != null && playingPath.equals(path) && player != null) {
                if (player.isPlaying()) { player.pause(); btn.setKind(Ico.PLAY); }
                else { player.start(); btn.setKind(Ico.PAUSE); playingBtn = btn; }
                return;
            }
            stopPlayer();
            player = new android.media.MediaPlayer();
            player.setDataSource(path);
            player.prepare();
            player.start();
            playingPath = path;
            playingBtn = btn;
            btn.setKind(Ico.PAUSE);
            player.setOnCompletionListener(mp -> stopPlayer());
        } catch (Exception e) {
            toast(T.playError());
            stopPlayer();
        }
    }

    private void stopPlayer() {
        if (playingBtn != null) playingBtn.setKind(Ico.PLAY);
        try { if (player != null) { player.stop(); player.release(); } } catch (Exception ignored) { }
        player = null;
        playingPath = null;
        playingBtn = null;
    }

    private String formatDur(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s / 60 + ":" + String.format(Locale.US, "%02d", s % 60);
    }

    private long durationOf(String path) {
        try {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            r.setDataSource(path);
            String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            r.release();
            return d == null ? 0 : Long.parseLong(d);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_AUDIO && res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startRecording();
        }
    }

    private void attachMenu() {
        menu(null, new String[]{T.attachPhoto(), T.attachFile()}, null, i -> {
            try {
                if (i == 0) {
                    startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                            .addCategory(Intent.CATEGORY_OPENABLE), REQ_PHOTO);
                } else {
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                            .addCategory(Intent.CATEGORY_OPENABLE), REQ_FILE);
                }
            } catch (ActivityNotFoundException e) {
                toast(T.noApp());
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        if (req == REQ_SAVE) {
            String path = pendingSavePath;
            new Thread(() -> {
                boolean ok = false;
                try (InputStream in = new java.io.FileInputStream(path); OutputStream out = getContentResolver().openOutputStream(u)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    ok = true;
                } catch (Exception ignored) { }
                boolean done = ok;
                runOnUiThread(() -> toast(done ? T.saved() : T.readError()));
            }).start();
            return;
        }
        boolean photo = req == REQ_PHOTO;
        String reply = replyTo;
        setReply(null);
        new Thread(() -> prepareAndSend(u, photo, reply)).start();
    }

    /** Liest den Anhang, verkleinert Fotos, legt eine Kopie ab und verschickt sie. */
    private void prepareAndSend(Uri u, boolean photo, String reply) {
        try {
            String name = "file", mime = getContentResolver().getType(u);
            try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
            } catch (Exception ignored) { }
            byte[] data = readAll(u, Proto.MAX_FILE * 25);
            if (data == null) { runOnUiThread(() -> toast(T.readError())); return; }
            boolean image = false;
            boolean looksImage = photo || (mime != null && mime.startsWith("image/") && !mime.equals("image/gif"));
            if (looksImage && !"image/gif".equals(mime)) {
                byte[] jpg = compressPhoto(data);
                if (jpg != null) {
                    data = jpg;
                    image = true;
                    mime = "image/jpeg";
                    int dot = name.lastIndexOf('.');
                    name = (dot > 0 ? name.substring(0, dot) : "photo") + ".jpg";
                }
            }
            if (data.length > Proto.MAX_FILE) { runOnUiThread(() -> toast(T.tooLarge())); return; }
            if (mime == null || !mime.contains("/")) mime = "application/octet-stream";
            name = Proto.safeName(name);
            String fid = Proto.newFileId();
            File f = new File(app.mediaDir(), fid + "_" + name);
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
            app.net.sendFile(peer, f.getAbsolutePath(), name, mime, image, reply);
        } catch (Throwable t) {
            runOnUiThread(() -> toast(T.readError()));
        }
    }

    private byte[] readAll(Uri u, int limit) {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > limit) return new byte[limit + 1];
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** Foto auf höchstens 1600 px verkleinern, Drehung beachten, als JPEG unter 1 MB speichern. */
    private static byte[] compressPhoto(byte[] data) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int maxSide = 1600, sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b == null) return null;
            float scale = Math.min(1f, maxSide / (float) Math.max(b.getWidth(), b.getHeight()));
            int rot = 0;
            try {
                int or = new ExifInterface(new java.io.ByteArrayInputStream(data))
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                rot = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                        : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            } catch (Exception ignored) { }
            if (scale < 1f || rot != 0) {
                Matrix mx = new Matrix();
                mx.postScale(scale, scale);
                mx.postRotate(rot);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), mx, true);
                if (r != b) b.recycle();
                b = r;
            }
            int q = 82;
            byte[] out;
            do {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, q, bo);
                out = bo.toByteArray();
                q -= 12;
            } while (out.length > 900 * 1024 && q >= 34);
            b.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Kontakt

    private void contactMenu() {
        menu(app.nameOf(peer), new String[]{T.rename(), T.showCode(), T.deleteChat()}, new int[]{0, 0, Pal.red}, i -> {
            if (i == 0) renameDialog();
            else if (i == 1) showPeerCode();
            else confirm(T.deleteChatQ(), T.delete(), () -> {
                for (String p : app.db.deleteContact(peer)) new File(p).delete();
                finish();
            });
        });
    }

    private void renameDialog() {
        Dialog dlg = dialog();
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
        Dialog dlg = dialog();
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
