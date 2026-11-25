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
    private static final int REQ_AUDIO = 23;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final int REQ_GROUP_PIC = 24, REQ_CROP_GROUP = 25;
    private String gid;
    private FrameLayout headerAv;
    private LinearLayout header, selBar, list;
    private Ico selTrash;
    private String selId;              // markierte Nachricht (Auswahlmodus)
    private final java.util.Map<String, View> gViewById = new java.util.HashMap<>();
    private TextView title, sub;
    private ScrollView scroll;
    private EditText input;
    private LinearLayout bar, recBar;
    // Aufnahme
    private Ico mic, pauseBtn;
    private TextView recTime, pauseLbl;
    private WaveView recWave;
    private android.media.MediaRecorder recorder;
    private File recFile;
    private boolean recording, recPaused;
    private long recStart, recordedMs;
    private Runnable recTick;
    private final java.util.ArrayList<Integer> recAmps = new java.util.ArrayList<>();
    private android.media.MediaPlayer previewPlayer;
    private Runnable previewTick;
    // Wiedergabe
    private android.media.MediaPlayer player;
    private String playingPath;
    private Ico playingBtn;
    private WaveView playingWave;
    private TextView playingPos;
    private long playingTotal;
    private Runnable voiceTick;

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
        headerAv = new FrameLayout(this);
        headerAv.setOnClickListener(v -> openInfo());
        LinearLayout.LayoutParams hap = new LinearLayout.LayoutParams(dp(40), dp(40));
        hap.setMargins(0, 0, dp(8), 0);
        header.addView(headerAv, hap);
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

        // Auswahl-Kopfzeile (erscheint, wenn eine Nachricht markiert ist)
        selBar = new LinearLayout(this);
        selBar.setGravity(Gravity.CENTER_VERTICAL);
        selBar.setBackgroundColor(Pal.card);
        selBar.setVisibility(View.GONE);
        Ico selClose = new Ico(this, Ico.CLOSE);
        selClose.setOnClickListener(v -> clearSelection());
        selBar.addView(selClose, new LinearLayout.LayoutParams(dp(44), dp(48)));
        TextView selTitle = text(T.selected(), 17, Pal.text, true);
        selTitle.setPadding(dp(8), 0, dp(8), 0);
        selBar.addView(selTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        selTrash = new Ico(this, Ico.TRASH);
        selTrash.color = Pal.red;
        selBar.addView(selTrash, new LinearLayout.LayoutParams(dp(48), dp(48)));
        col.addView(selBar);

        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        attachFloatDate(root, scroll);

        bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        Ico attach = new Ico(this, Ico.ATTACH);
        attach.color = Pal.dim;
        attach.setOnClickListener(v -> attachMenu());
        bar.addView(attach, new LinearLayout.LayoutParams(dp(42), dp(46)));
        input = field(T.messageHint(), true);
        bar.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));
        mic = new Ico(this, Ico.MIC);
        mic.color = Pal.dim;
        mic.setOnClickListener(v -> startRecording());
        bar.addView(mic, new LinearLayout.LayoutParams(dp(42), dp(46)));
        Ico send = new Ico(this, Ico.SEND);
        send.setOnClickListener(v -> doSend());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(46), dp(46));
        sp.setMargins(dp(4), 0, 0, 0);
        bar.addView(send, sp);
        col.addView(bar);
        buildRecBar(col);
        onInsets();
    }

    @Override
    protected void onInsets() {
        if (header == null) return;
        header.setPadding(dp(4), insetTop + dp(8), dp(12), dp(8));
        if (selBar != null) selBar.setPadding(dp(4), insetTop + dp(8), dp(12), dp(8));
        list.setPadding(dp(12), dp(8), dp(12), dp(12));
        setFloatDateTop(insetTop + dp(54));
        if (bar != null) bar.setPadding(dp(8), dp(6), dp(8), insetBottom + dp(6));
        if (recBar != null) recBar.setPadding(dp(12), dp(8), dp(12), insetBottom + dp(12));
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
        if (recording) cancelRecording();
        stopPlayer();
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
        headerAv.removeAllViews();
        Avatar hav = new Avatar(gid, g.name == null || g.name.isEmpty() ? "#" : g.name, g.avatar);
        hav.silhouette = false;
        headerAv.addView(hav, new FrameLayout.LayoutParams(dp(40), dp(40)));
        list.removeAllViews();
        dayChipViews.clear();
        gViewById.clear();
        String me = app.keys.pubHex();
        List<Db.Msg> msgs = app.db.messages(gid);
        String lastSender = null;
        long lastDay = -1;
        for (Db.Msg m : msgs) {
            long day = dayKey(m.ts);
            if (day != lastDay) {
                View chip = dayChip(m.ts);
                list.addView(chip);
                dayChipViews.add(chip);
                lastDay = day;
                lastSender = null;
            }
            boolean showName = !m.outgoing && !java.util.Objects.equals(m.sender, lastSender);
            View rv = bubble(m, me, showName);
            gViewById.put(m.id, rv);
            list.addView(rv);
            lastSender = m.sender;
        }
        if (selId != null) {
            View r = gViewById.get(selId);
            if (r != null) r.setBackgroundColor(withAlpha(Pal.accent, 0x22));
            else clearSelection();
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
            bub.addView(voicePlayer(m, textColor, metaColor), new LinearLayout.LayoutParams(-2, -2));
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

        bub.setOnLongClickListener(v -> { groupMsgMenu(m); return true; });

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(m.outgoing ? Gravity.END : Gravity.START);
        col.addView(bub, new LinearLayout.LayoutParams(-2, -2));
        View badge = reactionBadge(m.id);
        if (badge != null) {
            badge.setOnClickListener(v -> openGroupReactionPicker(m));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.setMargins(0, dp(2), 0, 0);
            col.addView(badge, bp);
        }

        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(col, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, -2);
        wlp.weight = 0;
        row.addView(wrap, wlp);
        return row;
    }

    /** Reaktions-Auswahl (nur Emojis) – vom Antippen eines bestehenden Badges. */
    private void openGroupReactionPicker(Db.Msg m) {
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.addView(reactionRow(app.db.myReaction(m.id, app.keys.pubHex()), emoji -> {
            dlg.dismiss(); app.net.sendGroupReaction(gid, m.id, emoji);
        }), new LinearLayout.LayoutParams(-1, -2));
        showDialog(dlg, box);
    }

    private void gact(LinearLayout box, Dialog dlg, String label, int color, Runnable run) {
        TextView t = text(label, 16, color == 0 ? Pal.text : color, false);
        t.setPadding(dp(4), dp(13), dp(4), dp(13));
        t.setOnClickListener(v -> { dlg.dismiss(); run.run(); });
        box.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Lang gedrückt: nur die Emoji-Leiste zeigen und die Nachricht markieren. */
    private void groupMsgMenu(Db.Msg m) {
        mark(m);
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.addView(reactionRow(app.db.myReaction(m.id, app.keys.pubHex()), emoji -> {
            dlg.dismiss(); clearSelection(); app.net.sendGroupReaction(gid, m.id, emoji);
        }), new LinearLayout.LayoutParams(-1, -2));
        showDialog(dlg, box);
    }

    // ------------------------------------------------------------------ Auswahl / Löschen

    private void mark(Db.Msg m) {
        clearHighlight();
        selId = m.id;
        View row = gViewById.get(m.id);
        if (row != null) row.setBackgroundColor(withAlpha(Pal.accent, 0x22));
        if (header != null) header.setVisibility(View.GONE);
        if (selBar != null) selBar.setVisibility(View.VISIBLE);
        if (selTrash != null) selTrash.setOnClickListener(v -> groupDeleteOptions(m));
    }

    private void clearHighlight() {
        if (selId != null) { View r = gViewById.get(selId); if (r != null) r.setBackground(null); }
    }

    private void clearSelection() {
        clearHighlight();
        selId = null;
        if (selBar != null) selBar.setVisibility(View.GONE);
        if (header != null) header.setVisibility(View.VISIBLE);
    }

    private void groupDeleteOptions(Db.Msg m) {
        Dialog dlg = dialog();
        LinearLayout box = panel();
        box.addView(text(T.deleteMessageQ(), 18, Pal.text, true));
        gact(box, dlg, T.deleteForMe(), Pal.red, () -> {
            app.db.deleteMessage(m.id);
            clearSelection();
            reload();
        });
        gact(box, dlg, T.cancel(), 0, () -> { });
        showDialog(dlg, box);
    }

    @Override
    public void onBackPressed() {
        if (selId != null) { clearSelection(); return; }
        super.onBackPressed();
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

    // ------------------------------------------------------------------ Sprachnachrichten

    private void buildRecBar(LinearLayout col) {
        recBar = new LinearLayout(this);
        recBar.setOrientation(LinearLayout.VERTICAL);
        recBar.setVisibility(View.GONE);
        recBar.setPadding(dp(12), dp(8), dp(12), dp(10));
        LinearLayout waveRow = new LinearLayout(this);
        waveRow.setGravity(Gravity.CENTER_VERTICAL);
        recTime = text("0:00", 17, Pal.text, true);
        waveRow.addView(recTime, new LinearLayout.LayoutParams(dp(56), -2));
        recWave = new WaveView(this);
        recWave.growFromLeft = true;
        recWave.showHandle = true;
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, dp(44), 1f);
        wl.setMargins(dp(10), 0, 0, 0);
        waveRow.addView(recWave, wl);
        recBar.addView(waveRow);
        LinearLayout ctrlRow = new LinearLayout(this);
        ctrlRow.setGravity(Gravity.CENTER_VERTICAL);
        ctrlRow.setPadding(0, dp(10), 0, 0);
        Ico del = new Ico(this, Ico.TRASH);
        del.color = Pal.red;
        FrameLayout delWrap = new FrameLayout(this);
        delWrap.setBackground(circle(withAlpha(Pal.red, 0x26), 0));
        delWrap.addView(del, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        delWrap.setOnClickListener(v -> cancelRecording());
        ctrlRow.addView(delWrap, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout pausePill = new LinearLayout(this);
        pausePill.setGravity(Gravity.CENTER);
        pausePill.setBackground(round(Pal.field, 0, 26));
        pausePill.setPadding(dp(16), dp(14), dp(16), dp(14));
        pauseBtn = new Ico(this, Ico.PAUSE);
        pauseBtn.color = Pal.text;
        pausePill.addView(pauseBtn, new LinearLayout.LayoutParams(dp(22), dp(22)));
        pauseLbl = text(T.pauseRec(), 16, Pal.text, true);
        pauseLbl.setPadding(dp(10), 0, 0, 0);
        pausePill.addView(pauseLbl);
        pausePill.setOnClickListener(v -> togglePauseRec());
        LinearLayout.LayoutParams ppl = new LinearLayout.LayoutParams(0, -2, 1f);
        ppl.setMargins(dp(12), 0, dp(12), 0);
        ctrlRow.addView(pausePill, ppl);
        Ico stopRec = new Ico(this, Ico.SEND);
        stopRec.on = true;
        stopRec.setOnClickListener(v -> stopAndSend());
        ctrlRow.addView(stopRec, new LinearLayout.LayoutParams(dp(52), dp(52)));
        recBar.addView(ctrlRow);
        col.addView(recBar);
    }

    private void startRecording() {
        if (recording) return;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        try {
            recFile = new File(getCacheDir(), "grec_" + System.currentTimeMillis() + ".aac");
            recorder = new android.media.MediaRecorder();
            recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.AAC_ADTS);
            recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(recFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true; recPaused = false; recordedMs = 0; recStart = System.currentTimeMillis();
            recAmps.clear();
            if (recWave != null) { recWave.setProgress(-1); recWave.setAmps(new float[0]); recWave.seekListener = null; }
            if (pauseBtn != null) pauseBtn.setKind(Ico.PAUSE);
            if (pauseLbl != null) pauseLbl.setText(T.pauseRec());
            stopPlayer();
            bar.setVisibility(View.GONE);
            recBar.setVisibility(View.VISIBLE);
            recTick = new Runnable() {
                public void run() {
                    if (!recording) return;
                    if (!recPaused && recorder != null) {
                        try { recAmps.add(recorder.getMaxAmplitude()); } catch (Exception ignored) { }
                        updateWaveLive();
                    }
                    long ms = recordedMs + (recPaused ? 0 : System.currentTimeMillis() - recStart);
                    long s = ms / 1000;
                    recTime.setText(s / 60 + ":" + String.format(java.util.Locale.US, "%02d", s % 60));
                    ui.postDelayed(this, 70);
                }
            };
            ui.post(recTick);
        } catch (Exception e) {
            toast(T.recordError());
            cleanupRecorder();
            resetRecUI();
        }
    }

    private void updateWaveLive() {
        int n = recAmps.size();
        if (n == 0) { recWave.setAmps(new float[0]); return; }
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = Math.min(1f, recAmps.get(i) / 12000f);
        recWave.setAmps(a);
    }

    private void togglePauseRec() {
        if (!recording || recorder == null) return;
        try {
            if (!recPaused) {
                recorder.pause(); recPaused = true;
                recordedMs += System.currentTimeMillis() - recStart;
                pauseBtn.setKind(Ico.PLAY); pauseLbl.setText(T.resumeRec());
                recWave.setProgress(0f);
                recWave.seekListener = f -> previewSeek(f);
            } else {
                stopPreview(); recorder.resume(); recPaused = false;
                recStart = System.currentTimeMillis();
                recWave.setProgress(-1); recWave.seekListener = null;
                pauseBtn.setKind(Ico.PAUSE); pauseLbl.setText(T.pauseRec());
            }
        } catch (Exception ignored) { }
    }

    private void previewSeek(float frac) {
        if (!recPaused || recFile == null) return;
        try {
            if (previewPlayer == null) {
                previewPlayer = new android.media.MediaPlayer();
                previewPlayer.setDataSource(recFile.getAbsolutePath());
                previewPlayer.prepare();
            }
            previewPlayer.seekTo((int) (frac * Math.max(1, recordedMs)));
            previewPlayer.start();
            recWave.setProgress(frac);
            if (previewTick == null) {
                previewTick = new Runnable() {
                    public void run() {
                        if (previewPlayer == null || !recPaused) return;
                        try {
                            if (previewPlayer.isPlaying()) {
                                recWave.setProgress(Math.min(1f, previewPlayer.getCurrentPosition() / (float) Math.max(1, recordedMs)));
                                ui.postDelayed(this, 60);
                            }
                        } catch (Exception ignored) { }
                    }
                };
            }
            ui.removeCallbacks(previewTick);
            ui.post(previewTick);
        } catch (Exception ignored) { }
    }

    private void stopPreview() {
        if (previewTick != null) ui.removeCallbacks(previewTick);
        try { if (previewPlayer != null) previewPlayer.release(); } catch (Exception ignored) { }
        previewPlayer = null;
        if (recWave != null) recWave.setProgress(-1);
    }

    private File finishRecording() {
        recording = false; recPaused = false;
        stopPreview();
        if (recTick != null) ui.removeCallbacks(recTick);
        File f = recFile;
        try { if (recorder != null) { recorder.resume(); recorder.stop(); } }
        catch (Exception e) { try { if (recorder != null) recorder.stop(); } catch (Exception ignored) { } }
        cleanupRecorder();
        return f;
    }

    private void cleanupRecorder() {
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) { }
        recorder = null;
    }

    private void resetRecUI() {
        if (recBar != null) recBar.setVisibility(View.GONE);
        if (bar != null) bar.setVisibility(View.VISIBLE);
    }

    private void cancelRecording() {
        File f = finishRecording();
        resetRecUI();
        if (f != null) f.delete();
    }

    private String encodeWave() {
        int buckets = 40, n = recAmps.size();
        byte[] out = new byte[buckets];
        if (n > 0) {
            float peak = 1f;
            for (int v : recAmps) peak = Math.max(peak, v);
            for (int b = 0; b < buckets; b++) {
                int from = (int) ((long) b * n / buckets), to = (int) ((long) (b + 1) * n / buckets);
                int mx = 0;
                for (int i = from; i < to && i < n; i++) mx = Math.max(mx, recAmps.get(i));
                out[b] = (byte) Math.min(255, Math.round(mx / peak * 255f));
            }
        }
        return android.util.Base64.encodeToString(out, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
    }

    private void stopAndSend() {
        long dur = recordedMs + (recPaused ? 0 : System.currentTimeMillis() - recStart);
        String wave = encodeWave();
        File rec = finishRecording();
        resetRecUI();
        if (rec == null) return;
        if (dur < 800) { rec.delete(); toast(T.recordTooShort()); return; }
        final String name = voiceName(dur, wave);
        new Thread(() -> {
            try {
                byte[] data = java.nio.file.Files.readAllBytes(rec.toPath());
                rec.delete();
                if (data.length == 0 || data.length > Proto.MAX_FILE) { runOnUiThread(() -> toast(T.tooLarge())); return; }
                String fid = Proto.newFileId();
                File f = new File(app.mediaDir(), fid + "_voice.aac");
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
                app.net.sendGroupFile(gid, f.getAbsolutePath(), name, "audio/aac", false, null);
            } catch (Throwable t) {
                runOnUiThread(() -> toast(T.readError()));
            }
        }).start();
    }

    private static float[] flatWave() {
        float[] a = new float[40];
        for (int i = 0; i < a.length; i++) a[i] = 0.45f + 0.25f * (float) Math.abs(Math.sin(i * 0.9));
        return a;
    }

    private String formatDur(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s / 60 + ":" + String.format(java.util.Locale.US, "%02d", s % 60);
    }

    private long durationOf(String path) {
        try {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            r.setDataSource(path);
            String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            r.release();
            return d == null ? 0 : Long.parseLong(d);
        } catch (Exception e) { return 0; }
    }

    private long playerDuration(String path) {
        long d = 0;
        try { if (player != null) d = player.getDuration(); } catch (Exception ignored) { }
        if (d <= 0) d = durationOf(path);
        return Math.max(1, d);
    }

    private void updatePos(long curMs) {
        if (playingPos != null) playingPos.setText(curMs > 0 ? formatDur(curMs) + " / " + formatDur(playingTotal) : formatDur(playingTotal));
    }

    private void startVoiceTick(final String path) {
        if (voiceTick != null) ui.removeCallbacks(voiceTick);
        voiceTick = new Runnable() {
            public void run() {
                if (player == null || playingWave == null) return;
                try {
                    if (player.isPlaying()) {
                        int cur = player.getCurrentPosition();
                        playingWave.setProgress(Math.min(1f, cur / (float) playerDuration(path)));
                        updatePos(cur);
                        ui.postDelayed(this, 60);
                    }
                } catch (Exception ignored) { }
            }
        };
        ui.post(voiceTick);
    }

    private boolean openPlayer(String path, Ico btn, WaveView wv, TextView pos, long total) {
        try {
            stopPlayer();
            player = new android.media.MediaPlayer();
            player.setDataSource(path);
            player.prepare();
            playingPath = path; playingBtn = btn; playingWave = wv; playingPos = pos; playingTotal = total;
            player.setOnCompletionListener(mp -> {
                if (playingWave != null) playingWave.setProgress(0f);
                if (playingBtn != null) playingBtn.setKind(Ico.PLAY);
                updatePos(0);
                stopPlayer();
            });
            return true;
        } catch (Exception e) { toast(T.playError()); stopPlayer(); return false; }
    }

    private void togglePlay(String path, Ico btn, WaveView wv, TextView pos, long total) {
        if (playingPath != null && playingPath.equals(path) && player != null) {
            try {
                if (player.isPlaying()) { player.pause(); btn.setKind(Ico.PLAY); }
                else { player.start(); btn.setKind(Ico.PAUSE); playingBtn = btn; startVoiceTick(path); }
            } catch (Exception ignored) { }
            return;
        }
        if (!openPlayer(path, btn, wv, pos, total)) return;
        try { player.start(); btn.setKind(Ico.PAUSE); startVoiceTick(path); } catch (Exception ignored) { }
    }

    private void seekVoice(String path, Ico btn, WaveView wv, TextView pos, long total, float frac) {
        boolean loaded = playingPath != null && playingPath.equals(path) && player != null;
        if (!loaded) { if (!openPlayer(path, btn, wv, pos, total)) return; }
        try {
            boolean wasPlaying = player.isPlaying();
            int to = (int) (frac * playerDuration(path));
            player.seekTo(to);
            playingWave = wv; playingPos = pos; playingTotal = total;
            wv.setProgress(frac); updatePos(to);
            if (wasPlaying) { if (!player.isPlaying()) player.start(); btn.setKind(Ico.PAUSE); startVoiceTick(path); }
            else btn.setKind(Ico.PLAY);
        } catch (Exception ignored) { }
    }

    private void stopPlayer() {
        if (voiceTick != null) ui.removeCallbacks(voiceTick);
        if (playingBtn != null) playingBtn.setKind(Ico.PLAY);
        try { if (player != null) { player.stop(); player.release(); } } catch (Exception ignored) { }
        player = null; playingPath = null; playingBtn = null; playingWave = null; playingPos = null;
    }

    private View voicePlayer(Db.Msg m, int textColor, int metaColor) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        boolean playingThis = playingPath != null && playingPath.equals(m.filePath);
        final String path = m.filePath;
        long dn = durFromName(m.fileName);
        final long totalMs = dn > 0 ? dn : durationOf(m.filePath);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        Ico btn = new Ico(this, playingThis ? Ico.PAUSE : Ico.PLAY);
        btn.color = textColor;
        topRow.addView(btn, new LinearLayout.LayoutParams(dp(36), dp(36)));
        WaveView wv = new WaveView(this);
        wv.barColor = metaColor; wv.playedColor = textColor; wv.showHandle = true;
        wv.setAmps(waveFromName(m.fileName) != null ? waveFromName(m.fileName) : flatWave());
        wv.setProgress(0f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(150), dp(30));
        tp.setMargins(dp(8), 0, dp(4), 0);
        topRow.addView(wv, tp);
        v.addView(topRow);

        final TextView pos = text(formatDur(totalMs), 12, metaColor, false);
        pos.setPadding(dp(44), dp(1), 0, 0);
        v.addView(pos);

        wv.seekListener = f -> seekVoice(path, btn, wv, pos, totalMs, f);
        if (playingThis) { playingWave = wv; playingPos = pos; playingTotal = totalMs; }
        btn.setOnClickListener(x -> togglePlay(path, btn, wv, pos, totalMs));
        return v;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_AUDIO && res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) startRecording();
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
        if (req == REQ_CROP_GROUP && res == RESULT_OK && data != null && data.getStringExtra("out") != null) {
            String out = data.getStringExtra("out");
            new Thread(() -> setGroupPicFromFile(out)).start();
            return;
        }
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        if (req == REQ_GROUP_PIC) {
            // Erst in den Zuschneide-Editor, dann speichern.
            new Thread(() -> {
                String src = copyToCache(u);
                runOnUiThread(() -> {
                    if (src == null) { toast(T.readError()); return; }
                    startActivityForResult(new Intent(this, CropActivity.class).putExtra("src", src), REQ_CROP_GROUP);
                });
            }).start();
            return;
        }
        boolean photo = req == REQ_PHOTO;
        new Thread(() -> prepareAndSend(u, photo)).start();
    }

    private void pickGroupPic() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE), REQ_GROUP_PIC);
        } catch (ActivityNotFoundException e) { toast(T.noApp()); }
    }

    /** Bild aus einem Content-Uri in eine Cache-Datei kopieren (für den Zuschneide-Editor). */
    private String copyToCache(Uri u) {
        try {
            byte[] data = readAll(u, Proto.MAX_FILE * 40);
            if (data == null) return null;
            File f = new File(getCacheDir(), "crop_src_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(data); }
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    private void setGroupPicFromFile(String path) {
        try {
            File src = new File(path);
            byte[] data = new byte[(int) src.length()];
            try (java.io.FileInputStream fi = new java.io.FileInputStream(src)) {
                int off = 0, n;
                while (off < data.length && (n = fi.read(data, off, data.length - off)) > 0) off += n;
            }
            byte[] jpg = compressGroupPic(data);
            if (jpg == null) { runOnUiThread(() -> toast(T.readError())); return; }
            File f = new File(app.mediaDir(), "groupav_" + gid + ".jpg");
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(jpg); }
            app.db.setGroupAvatar(gid, f.getAbsolutePath());
            app.net.pushGroup(gid);
            runOnUiThread(this::reload);
        } catch (Throwable t) {
            runOnUiThread(() -> toast(T.readError()));
        }
    }

    private void setGroupPicFrom(Uri u) {
        try {
            byte[] data = readAll(u, Proto.MAX_FILE * 25);
            if (data == null) { runOnUiThread(() -> toast(T.readError())); return; }
            byte[] jpg = compressGroupPic(data);
            if (jpg == null) { runOnUiThread(() -> toast(T.readError())); return; }
            File f = new File(app.mediaDir(), "groupav_" + gid + ".jpg");
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(jpg); }
            app.db.setGroupAvatar(gid, f.getAbsolutePath());
            app.net.pushGroup(gid);
            runOnUiThread(this::reload);
        } catch (Throwable t) {
            runOnUiThread(() -> toast(T.readError()));
        }
    }

    /** Kleines Gruppenbild (max ~320px), damit es klein genug zum Mitsenden ist. */
    private static byte[] compressGroupPic(byte[] data) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) return null;
            int maxSide = 320, sample = 1;
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
            int q = 80;
            byte[] out;
            do {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, q, bo);
                out = bo.toByteArray();
                q -= 12;
            } while (out.length > 60 * 1024 && q >= 30);
            b.recycle();
            return out;
        } catch (Throwable t) { return null; }
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
        LinearLayout avRow = new LinearLayout(this);
        avRow.setOrientation(LinearLayout.VERTICAL);
        avRow.setGravity(Gravity.CENTER_HORIZONTAL);
        avRow.setPadding(0, dp(2), 0, dp(10));
        Avatar ga = new Avatar(gid, g.name == null || g.name.isEmpty() ? "#" : g.name, g.avatar);
        ga.silhouette = false;
        if (iAmOwner || iAmAdmin) ga.setOnClickListener(v -> { dlg.dismiss(); pickGroupPic(); });
        avRow.addView(ga, new LinearLayout.LayoutParams(dp(88), dp(88)));
        if (iAmOwner || iAmAdmin) {
            TextView ch = text(T.groupChangePic(), 13, Pal.accent, false);
            ch.setPadding(0, dp(6), 0, 0);
            ch.setOnClickListener(v -> { dlg.dismiss(); pickGroupPic(); });
            avRow.addView(ch);
        }
        box.addView(avRow);
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
