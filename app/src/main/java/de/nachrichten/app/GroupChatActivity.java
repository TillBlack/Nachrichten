package de.nachrichten.app;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import de.nachrichten.crypto.Proto;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/** Gruppen-Chat (nur Text). Nachrichten sind mit dem gemeinsamen Gruppenschlüssel verschlüsselt. */
public class GroupChatActivity extends Base implements Net.Listener {

    private static final int REQ_PHOTO = 21, REQ_FILE = 22;
    private static final LruCache<String, Bitmap> GTHUMBS = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
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
        Ico attach = new Ico(this, Ico.ATTACH);
        attach.color = Pal.dim;
        attach.setOnClickListener(v -> attachMenu());
        bar.addView(attach, new LinearLayout.LayoutParams(dp(42), dp(46)));
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

    private static boolean isVoice(Db.Msg m) {
        return m.kind == Db.FILE && m.fileMime != null && m.fileMime.startsWith("audio/");
    }

    private View bubble(Db.Msg m, String me, boolean showName) {
        // Zeile: [Profilbild des Absenders] [Blase]  (eigene Nachrichten rechts, ohne Bild)
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.setMargins(0, dp(2), 0, dp(2));
        row.setLayoutParams(rp);
        row.setGravity(m.outgoing ? Gravity.END : (Gravity.START | Gravity.TOP));

        if (!m.outgoing) {
            // Profilbild des Absenders vor jeder (neuen) Nachricht
            FrameLayout avSlot = new FrameLayout(this);
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(36), dp(36));
            ap.setMargins(0, dp(2), dp(8), 0);
            if (showName) {
                Db.Contact c = app.db.contact(m.sender);
                avSlot.addView(avatarWithDot(m.sender, app.nameOf(m.sender), c != null ? c.avatar : null, false, 36,
                        c != null ? c.accBehind : -1, c != null ? c.accFront : -1, c != null ? c.accFrame : -1),
                        new FrameLayout.LayoutParams(dp(36), dp(36)));
            }
            row.addView(avSlot, ap);
        }

        LinearLayout bub = new LinearLayout(this);
        bub.setOrientation(LinearLayout.VERTICAL);
        bub.setPadding(dp(12), dp(8), dp(12), dp(8));
        bub.setBackground(round(m.outgoing ? Pal.accent : Pal.card, m.outgoing ? 0 : Pal.line, 14));
        bub.setMinimumWidth(dp(48));
        int textColor = m.outgoing ? Pal.onAccent : Pal.text;
        int metaColor = m.outgoing ? Pal.onAccent : Pal.dim;

        if (showName && !m.outgoing) bub.addView(text(app.nameOf(m.sender), 12, Pal.accent, true));

        boolean receiving = m.kind != Db.TEXT && !m.complete();
        if (receiving) {
            bub.addView(text(T.receiving() + " " + (m.kind == Db.IMAGE ? T.photo() : T.fileLabel()) + "… " + m.have + "/" + m.total, 14, metaColor, false));
        } else if (m.kind == Db.IMAGE) {
            int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.62f);
            ImageView iv = new ImageView(this);
            Bitmap bm = gthumb(m.filePath, maxW - dp(8));
            if (bm != null) {
                iv.setImageBitmap(bm);
                iv.setAdjustViewBounds(true);
                iv.setMaxWidth(maxW);
                iv.setOnClickListener(x -> bigImage(m.filePath));
                bub.addView(iv, new LinearLayout.LayoutParams(-2, -2));
            } else bub.addView(text(T.photo(), 15, textColor, false));
        } else if (isVoice(m)) {
            TextView vt = text("▶ " + T.voiceMessage(), 15, textColor, false);
            vt.setOnClickListener(x -> openFile(m));
            bub.addView(vt);
        } else if (m.kind == Db.FILE) {
            TextView ft = text(m.fileName != null ? m.fileName : T.fileLabel(), 15, textColor, true);
            ft.setOnClickListener(x -> openFile(m));
            bub.addView(ft);
        } else {
            TextView body = text(m.body, 16, textColor, false);
            body.setLineSpacing(0, 1.15f);
            bub.addView(body);
        }

        TextView tm = text(time(m.ts), 10, metaColor, false);
        tm.setAlpha(m.outgoing ? 0.8f : 1f);
        tm.setGravity(Gravity.END);
        bub.addView(tm);

        int max = (int) (getResources().getDisplayMetrics().widthPixels * 0.72f);
        FrameLayout wrap = new FrameLayout(this);
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(-2, -2);
        wrap.addView(bub, wl);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, -2);
        wlp.weight = 0;
        row.addView(wrap, wlp);
        return row;
    }

    private void openFile(Db.Msg m) {
        if (!m.complete()) return;
        File f = new File(m.filePath);
        Uri u = FilesProvider.uriFor(f);
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, m.fileMime != null ? m.fileMime : "application/octet-stream")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(i, T.openWith()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
        catch (ActivityNotFoundException e) { toast(T.noApp()); }
    }

    // ------------------------------------------------------------------ Anhänge (Bilder/Dateien)

    private void attachMenu() {
        menu(null, new String[]{T.attachPhoto(), T.attachFile()}, null, i -> {
            try {
                if (i == 0) startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                        .addCategory(Intent.CATEGORY_OPENABLE), REQ_PHOTO);
                else startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE), REQ_FILE);
            } catch (ActivityNotFoundException e) { toast(T.noApp()); }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        boolean photo = req == REQ_PHOTO;
        new Thread(() -> prepareAndSend(u, photo)).start();
    }

    private void prepareAndSend(Uri u, boolean photo) {
        try {
            String name = "file", mime = getContentResolver().getType(u);
            try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
            } catch (Exception ignored) { }
            byte[] data = readAll(u, Proto.MAX_FILE * 25);
            if (data == null) { runOnUiThread(() -> toast(T.readError())); return; }
            boolean image = false;
            boolean looksImage = photo || (mime != null && mime.startsWith("image/") && !mime.equals("image/gif"));
            if (looksImage) {
                byte[] jpg = compressPhoto(data);
                if (jpg != null) {
                    data = jpg; image = true; mime = "image/jpeg";
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
            app.net.sendGroupFile(gid, f.getAbsolutePath(), name, mime, image, null);
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
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); if (out.size() > limit) return new byte[limit + 1]; }
            return out.toByteArray();
        } catch (Exception e) { return null; }
    }

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
        } catch (Throwable t) { return null; }
    }

    private Bitmap gthumb(String path, int targetW) {
        String key = path + "@" + targetW;
        Bitmap b = GTHUMBS.get(key);
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
            if (b != null) GTHUMBS.put(key, b);
            return b;
        } catch (Throwable t) { return null; }
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
