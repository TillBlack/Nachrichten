package de.nachrichten.desktop;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/** Eine Nachricht als Sprechblase (selbst gezeichnet, wie in der Android-App). */
final class MsgView extends JComponent {

    interface Actions {
        void openImage(Store.Msg m);
        void saveFile(Store.Msg m);
        void playVoice(Store.Msg m);
        void menu(Store.Msg m, JComponent at, int x, int y);
        void react(Store.Msg m, JComponent at, int x, int y);
        void jumpTo(String msgId);
    }

    final Store.Msg m;
    private final Client client;
    private final boolean group, showSender, mine;
    private final Actions actions;
    private boolean highlight;

    // berechnetes Layout
    private int layoutWidth = -1, prefH;
    private Rectangle bubble = new Rectangle(), imgR, fileR, voiceR, reactR, quoteR, avatarR;
    private List<TextLayout> lines = new ArrayList<>();
    private BufferedImage thumb;
    private int textX, textY, senderY, quoteY, metaY;

    private static final Font TXT = Theme.font(14.5f, false), SMALL = Theme.font(11.5f, false), NAME = Theme.font(13f, true);
    private static final int PAD = 10, AV = 28;

    MsgView(Store.Msg m, Client client, boolean group, boolean showSender, Actions actions) {
        this.m = m;
        this.client = client;
        this.group = group;
        this.showSender = showSender;
        this.mine = m.outgoing;
        this.actions = actions;
        setOpaque(false);
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) { actions.menu(m, MsgView.this, e.getX(), e.getY()); return; }
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                if (reactR != null && reactR.contains(e.getPoint())) { actions.react(m, MsgView.this, e.getX(), e.getY()); return; }
                if (e.getClickCount() == 2 && bubble.contains(e.getPoint())) { actions.react(m, MsgView.this, e.getX(), e.getY()); return; }
                if (imgR != null && imgR.contains(e.getPoint())) { actions.openImage(m); return; }
                if (voiceR != null && voiceR.contains(e.getPoint())) { actions.playVoice(m); return; }
                if (fileR != null && fileR.contains(e.getPoint())) { actions.saveFile(m); return; }
                if (quoteR != null && quoteR.contains(e.getPoint()) && m.replyTo != null) actions.jumpTo(m.replyTo);
            }
            @Override public void mouseMoved(MouseEvent e) {
                boolean hand = (imgR != null && imgR.contains(e.getPoint())) || (voiceR != null && voiceR.contains(e.getPoint()))
                        || (fileR != null && fileR.contains(e.getPoint())) || (reactR != null && reactR.contains(e.getPoint()))
                        || (quoteR != null && quoteR.contains(e.getPoint()));
                setCursor(Cursor.getPredefinedCursor(hand ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    void flash() {
        highlight = true;
        repaint();
        javax.swing.Timer t = new javax.swing.Timer(1200, e -> { highlight = false; repaint(); });
        t.setRepeats(false);
        t.start();
    }

    // ================================================================== Layout

    /** Höhe für eine gegebene Breite berechnen. */
    int layoutFor(int width) {
        if (width == layoutWidth) return prefH;
        layoutWidth = width;
        Graphics2D g = Ui.scratch();
        int left = (group && !mine) ? AV + 8 : 0;
        int maxW = Math.min(560, (int) ((width - 24 - left) * 0.74));
        maxW = Math.max(160, maxW);
        int contentW = maxW - 2 * PAD;

        // Inhalt messen
        int w = 0, h = PAD - 2;
        String sender = (group && !mine && showSender) ? client.nameOf(m.sender) : null;
        if (sender != null) { senderY = h; h += 18; w = Math.max(w, (int) Theme.lineWidth(g, sender, NAME)); }

        quoteR = null;
        if (m.replyTo != null) {
            quoteY = h;
            quoteR = new Rectangle(0, h, contentW, 42);
            h += 46;
            w = Math.max(w, Math.min(contentW, 200));
        }

        imgR = fileR = voiceR = null;
        thumb = null;
        if (m.kind == Store.IMAGE) {
            thumb = m.filePath != null ? thumbOf(m.filePath, Math.min(contentW + 2 * PAD - 8, 300), 320) : null;
            int iw = thumb != null ? thumb.getWidth() : 220, ih = thumb != null ? thumb.getHeight() : 140;
            imgR = new Rectangle(0, h, iw, ih);
            h += ih + 4;
            w = Math.max(w, iw - 2 * PAD + 8);
        } else if (m.kind == Store.FILE && Client.isVoiceName(m.fileName)) {
            voiceR = new Rectangle(0, h, 230, 40);
            h += 42;
            w = Math.max(w, 230);
        } else if (m.kind == Store.FILE) {
            fileR = new Rectangle(0, h, Math.min(contentW, 260), 46);
            h += 50;
            w = Math.max(w, Math.min(contentW, 260));
        }

        lines = new ArrayList<>();
        if (m.kind == Store.TEXT && m.body != null && !m.body.isEmpty()) {
            lines = Theme.wrap(m.body, TXT, contentW, g.getFontRenderContext());
            textY = h;
            for (TextLayout l : lines) {
                w = Math.max(w, (int) Math.ceil(l.getVisibleAdvance()));
                h += (int) Math.ceil(l.getAscent() + l.getDescent() + l.getLeading()) + 1;
            }
        }

        // Zeit + Haken: in der letzten Textzeile, wenn Platz, sonst eigene Zeile
        String time = time(m.ts);
        int metaW = (int) Theme.lineWidth(g, time, SMALL) + (mine && !group ? 22 : 0) + 4;
        boolean inline = m.kind == Store.TEXT && !lines.isEmpty()
                && lines.get(lines.size() - 1).getVisibleAdvance() + metaW + 12 <= contentW;
        if (inline) {
            float lastW = lines.get(lines.size() - 1).getVisibleAdvance();
            w = Math.max(w, (int) lastW + metaW + 12);
            metaY = h - 16;
        } else {
            metaY = h;
            h += 16;
            w = Math.max(w, metaW);
        }
        h += PAD - 4;
        int bw = Math.min(maxW, w + 2 * PAD);
        if (imgR != null) bw = Math.max(bw, imgR.width + 8);

        int x = mine ? width - 12 - bw : 12 + left;
        bubble = new Rectangle(x, 2, bw, h);
        textX = x + PAD;
        // Bereiche auf absolute Koordinaten
        if (quoteR != null) quoteR = new Rectangle(x + PAD, 2 + quoteY, bw - 2 * PAD, 42);
        if (imgR != null) imgR = new Rectangle(x + 4, 2 + imgR.y, imgR.width, imgR.height);
        if (voiceR != null) voiceR = new Rectangle(x + PAD, 2 + voiceR.y, Math.min(230, bw - 2 * PAD), 40);
        if (fileR != null) fileR = new Rectangle(x + PAD, 2 + fileR.y, bw - 2 * PAD, 46);
        avatarR = (group && !mine && showSender) ? new Rectangle(12, 2, AV, AV) : null;

        int total = h + 6;
        reactR = null;
        LinkedHashMap<String, Integer> rs = client.db.reactions(m.id);
        if (!rs.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Map.Entry<String, Integer> e : rs.entrySet()) { sb.append(e.getKey()); n += e.getValue(); }
            String s = sb + (n > 1 ? " " + n : "");
            int rw = (int) Theme.lineWidth(g, s, Theme.font(13, false)) + 18;
            int rx = mine ? x + bw - rw - 8 : x + 8;
            reactR = new Rectangle(rx, 2 + h - 6, rw, 24);
            reactText = s;
            total = h + 26;
        }
        prefH = total;
        return prefH;
    }

    private String reactText = "";

    @Override public Dimension getPreferredSize() {
        int w = getParent() != null ? getParent().getWidth() : 600;
        return new Dimension(w, layoutFor(Math.max(200, w)));
    }

    // ================================================================== Zeichnen

    @Override protected void paintComponent(Graphics g0) {
        if (layoutWidth != getWidth()) layoutFor(getWidth());
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        Color bg = mine ? Theme.BUBBLE_OUT : Theme.BUBBLE_IN;
        Color fg = mine ? Theme.ON_OUT : Theme.TEXT;
        Color meta = mine ? Theme.alpha(Theme.ON_OUT, 170) : Theme.DIM;

        if (avatarR != null) {
            Store.Contact c = client.db.contact(m.sender);
            BufferedImage av = c != null && c.avatar != null ? imageOf(c.avatar) : null;
            Theme.paintAvatar(g, avatarR.x, avatarR.y, AV, av, client.nameOf(m.sender), m.sender);
        }

        g.setColor(highlight ? Theme.alpha(Theme.ACCENT, 90) : bg);
        g.fill(Theme.round(bubble.x, bubble.y, bubble.width, bubble.height, 14));

        int y0 = bubble.y;
        if (group && !mine && showSender) {
            FontMetrics fm = g.getFontMetrics(NAME);
            Theme.drawLine(g, client.nameOf(m.sender), NAME, textX, y0 + senderY + fm.getAscent(), bubble.width - 2 * PAD, Theme.avatarColor(m.sender).brighter());
        }

        if (quoteR != null) {
            g.setColor(Theme.alpha(Color.BLACK, 60));
            g.fill(Theme.round(quoteR.x, quoteR.y, quoteR.width, quoteR.height, 8));
            g.setColor(Theme.ACCENT);
            g.fill(Theme.round(quoteR.x, quoteR.y, 4, quoteR.height, 2));
            Store.Msg q = client.db.message(m.replyTo);
            String who = q == null ? "" : (q.outgoing ? "Du" : client.nameOf(q.sender != null ? q.sender : q.peer));
            String what = q == null ? "Nachricht nicht mehr vorhanden" : Client.previewText(q.kind, q.body, q.fileName);
            FontMetrics fm = g.getFontMetrics(SMALL);
            Theme.drawLine(g, who, Theme.font(12, true), quoteR.x + 10, quoteR.y + 4 + fm.getAscent(), quoteR.width - 16, Theme.ACCENT.brighter());
            Theme.drawLine(g, what, SMALL, quoteR.x + 10, quoteR.y + 22 + fm.getAscent(), quoteR.width - 16, meta);
        }

        if (imgR != null) {
            java.awt.Shape old = g.getClip();
            g.clip(Theme.round(imgR.x, imgR.y, imgR.width, imgR.height, 11));
            if (thumb != null) g.drawImage(thumb, imgR.x, imgR.y, null);
            else {
                g.setColor(Theme.alpha(Color.BLACK, 70));
                g.fillRect(imgR.x, imgR.y, imgR.width, imgR.height);
                Theme.drawLine(g, "Bild wird geladen …", SMALL, imgR.x + 14, imgR.y + imgR.height / 2f + 4, imgR.width - 20, meta);
            }
            g.setClip(old);
        }

        if (voiceR != null) paintVoice(g, fg, meta);
        if (fileR != null) paintFile(g, fg, meta);

        if (!lines.isEmpty()) {
            float y = y0 + textY;
            g.setColor(fg);
            for (TextLayout l : lines) { y += l.getAscent(); l.draw(g, textX, y); y += l.getDescent() + l.getLeading() + 1; }
        }

        // Zeit + Status unten rechts
        String time = time(m.ts);
        FontMetrics fm = g.getFontMetrics(SMALL);
        float tw = Theme.lineWidth(g, time, SMALL);
        float right = bubble.x + bubble.width - PAD + 2;
        float statusW = (mine && !group) ? 20 : 0;
        float baseY = y0 + metaY + fm.getAscent();
        if (imgR != null && m.kind == Store.IMAGE) {
            // auf dem Bild: dunkles Schild
            float bx = right - tw - statusW - 8, by = baseY - fm.getAscent() - 3;
            g.setColor(Theme.alpha(Color.BLACK, 110));
            g.fill(Theme.round(bx, by, tw + statusW + 12, fm.getHeight() + 4, 8));
        }
        Theme.drawLine(g, time, SMALL, right - tw - statusW, baseY, 0, meta);
        if (mine && !group) {
            float sx = right - 17, sy = baseY - 10;
            if (m.status == Store.PENDING) Theme.drawClock(g, sx + 2, sy, 11, meta);
            else Theme.drawTicks(g, sx, sy, 11, m.status >= Store.DELIVERED, m.status == Store.READ ? Theme.READ_BLUE : meta);
        }

        if (reactR != null) {
            g.setColor(Theme.CARD);
            g.fill(Theme.round(reactR.x, reactR.y, reactR.width, reactR.height, 12));
            g.setColor(Theme.LINE);
            g.setStroke(new BasicStroke(1.2f));
            g.draw(Theme.round(reactR.x + 0.5f, reactR.y + 0.5f, reactR.width - 1, reactR.height - 1, 12));
            FontMetrics rf = g.getFontMetrics(Theme.font(13, false));
            Theme.drawLine(g, reactText, Theme.font(13, false), reactR.x + 9, reactR.y + (reactR.height + rf.getAscent() - rf.getDescent()) / 2f, 0, Theme.TEXT);
        }
        g.dispose();
    }

    private void paintVoice(Graphics2D g, Color fg, Color meta) {
        Rectangle r = voiceR;
        g.setColor(mine ? Theme.alpha(Color.WHITE, 40) : Theme.alpha(Theme.ACCENT, 60));
        g.fillOval(r.x, r.y + 2, 36, 36);
        Graphics2D p = (Graphics2D) g.create();
        p.translate(r.x + 9, r.y + 11);
        p.setColor(fg);
        Theme.drawSym(p, Theme.Sym.PLAY, 18);
        p.dispose();
        float[] wave = Client.waveFromName(m.fileName);
        int bars = 34, bx = r.x + 46, bw = r.width - 50;
        float step = bw / (float) bars;
        g.setColor(Theme.alpha(fg, 200));
        for (int i = 0; i < bars; i++) {
            float v = 0.18f;
            if (wave != null && wave.length > 0) v = Math.max(0.12f, wave[Math.min(wave.length - 1, i * wave.length / bars)]);
            float bh = 4 + v * 22;
            g.fill(Theme.round(bx + i * step, r.y + 20 - bh / 2, Math.max(2f, step * 0.5f), bh, 1.2f));
        }
        long d = Client.durFromName(m.fileName);
        String label = m.filePath == null ? "wird geladen …" : (d > 0 ? Client.fmtShort(d) : "Sprachnachricht");
        FontMetrics fm = g.getFontMetrics(SMALL);
        Theme.drawLine(g, label, SMALL, bx, r.y + 40 + fm.getAscent() - 2, 0, meta);
    }

    private void paintFile(Graphics2D g, Color fg, Color meta) {
        Rectangle r = fileR;
        g.setColor(Theme.alpha(Color.BLACK, 55));
        g.fill(Theme.round(r.x, r.y, r.width, r.height, 10));
        Graphics2D p = (Graphics2D) g.create();
        p.translate(r.x + 8, r.y + 9);
        p.setColor(fg);
        Theme.drawSym(p, Theme.Sym.FILE, 28);
        p.dispose();
        FontMetrics fm = g.getFontMetrics(TXT);
        Theme.drawLine(g, m.fileName == null ? "Datei" : m.fileName, Theme.font(13.5f, true), r.x + 42, r.y + 6 + fm.getAscent(), r.width - 80, fg);
        String sub = m.filePath == null ? "wird geladen …" : size(m.fileSize) + " · Speichern";
        Theme.drawLine(g, sub, SMALL, r.x + 42, r.y + 26 + fm.getAscent() - 4, r.width - 50, meta);
        Graphics2D q = (Graphics2D) g.create();
        q.translate(r.x + r.width - 30, r.y + 12);
        q.setColor(meta);
        Theme.drawSym(q, Theme.Sym.DOWNLOAD, 22);
        q.dispose();
    }

    // ================================================================== Hilfen

    static String time(long ts) { return new SimpleDateFormat("HH:mm").format(new Date(ts)); }

    static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(java.util.Locale.ROOT, "%.1f MB", b / 1048576.0).replace('.', ',');
    }

    private static final Map<String, BufferedImage> CACHE = new LinkedHashMap<String, BufferedImage>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) { return size() > 120; }
    };

    static synchronized BufferedImage imageOf(String path) {
        if (path == null) return null;
        BufferedImage b = CACHE.get(path);
        if (b == null) {
            try { b = ImageIO.read(new File(path)); } catch (Exception ignored) { }
            if (b != null) CACHE.put(path, b);
        }
        return b;
    }

    static synchronized BufferedImage thumbOf(String path, int maxW, int maxH) {
        String key = path + "@" + maxW + "x" + maxH;
        BufferedImage t = CACHE.get(key);
        if (t != null) return t;
        BufferedImage src = imageOf(path);
        if (src == null) return null;
        double s = Math.min(1.0, Math.min(maxW / (double) src.getWidth(), maxH / (double) src.getHeight()));
        int w = Math.max(1, (int) (src.getWidth() * s)), h = Math.max(1, (int) (src.getHeight() * s));
        t = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = t.createGraphics();
        Theme.aa(g);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        CACHE.put(key, t);
        return t;
    }

    static synchronized void forget(String path) {
        CACHE.keySet().removeIf(k -> k.startsWith(path));
    }

    // ================================================================== Tages-Trenner

    /** „Heute“, „Gestern“ oder Datum – mittig als kleines Schild. */
    static final class DayChip extends JComponent {
        final String text;
        DayChip(String text) { this.text = text; setOpaque(false); }
        @Override public Dimension getPreferredSize() { return new Dimension(200, 36); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.aa(g);
            Font f = Theme.font(12.5f, true);
            float tw = Theme.lineWidth(g, text, f);
            float w = tw + 26, h = 24, x = (getWidth() - w) / 2f, y = 6;
            g.setColor(Theme.CARD);
            g.fill(Theme.round(x, y, w, h, 12));
            FontMetrics fm = g.getFontMetrics(f);
            Theme.drawLine(g, text, f, x + 13, y + (h + fm.getAscent() - fm.getDescent()) / 2f, 0, Theme.DIM);
            g.dispose();
        }
    }

    static String dayLabel(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        java.util.Calendar d = java.util.Calendar.getInstance();
        d.setTimeInMillis(ts);
        if (same(c, d)) return "Heute";
        c.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (same(c, d)) return "Gestern";
        return new SimpleDateFormat("dd.MM.yyyy").format(new Date(ts));
    }

    static String dayKey(long ts) { return new SimpleDateFormat("yyyyMMdd").format(new Date(ts)); }

    private static boolean same(java.util.Calendar a, java.util.Calendar b) {
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }
}
