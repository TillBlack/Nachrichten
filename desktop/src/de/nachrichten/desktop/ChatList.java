package de.nachrichten.desktop;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/** Linke Seite: Kopf mit Knöpfen und die Liste aller Chats und Gruppen. */
final class ChatList extends JPanel {

    private final MainWindow win;
    private final Client client;
    private final JPanel rows = new JPanel();
    private final Ui.TLabel status = Ui.label("Verbinde …", 12, Theme.DIM, false);
    private final JPanel banner = new JPanel(new BorderLayout());
    private String selected;

    ChatList(MainWindow win, Client client) {
        super(new BorderLayout());
        this.win = win;
        this.client = client;
        setBackground(Theme.CARD);
        setPreferredSize(new Dimension(340, 600));

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(BorderFactory.createEmptyBorder(14, 18, 8, 10));
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.add(Ui.label("Nachrichten", 21, Theme.TEXT, true));
        titles.add(status);
        head.add(titles, BorderLayout.CENTER);
        JPanel btns = new JPanel();
        btns.setOpaque(false);
        btns.setLayout(new BoxLayout(btns, BoxLayout.X_AXIS));
        btns.add(new Ui.IconBtn(Theme.Sym.PLUS, 36, Theme.ACCENT, false, "Kontakt hinzufügen").onClick(e -> Dialogs.addContact(win, client)));
        btns.add(new Ui.IconBtn(Theme.Sym.GROUP, 36, Theme.TEXT, false, "Neue Gruppe").onClick(e -> Dialogs.newGroup(win, client)));
        btns.add(new Ui.IconBtn(Theme.Sym.GEAR, 36, Theme.TEXT, false, "Einstellungen").onClick(e -> Dialogs.settings(win, client)));
        head.add(btns, BorderLayout.EAST);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(head);
        banner.setOpaque(false);
        banner.setVisible(false);
        top.add(banner);
        add(top, BorderLayout.NORTH);

        rows.setOpaque(false);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(rows, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(holder, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        Ui.styleScroll(sp);
        add(sp, BorderLayout.CENTER);
    }

    void setStatus(int connected, int total) {
        if (connected == 0) { status.setText("Keine Verbindung – verbinde …"); status.setColor(Theme.RED); }
        else { status.setText("Verbunden (" + connected + " von " + total + " Servern)"); status.setColor(Theme.DIM); }
    }

    void setSelected(String conv) { selected = conv; rows.repaint(); }

    /** Hinweis oben (z. B. neue Version). */
    void showBanner(String text, String button, Runnable action) {
        banner.removeAll();
        JPanel b = new JPanel(new BorderLayout(8, 0)) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                g.setColor(Theme.alpha(Theme.ACCENT, 40));
                g.fill(Theme.round(0, 0, getWidth(), getHeight(), 10));
                g.setColor(Theme.ACCENT);
                g.draw(Theme.round(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 10));
                g.dispose();
            }
        };
        b.setOpaque(false);
        b.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 8));
        Ui.TLabel l = Ui.para(text, 13, Theme.TEXT);
        l.setWrapWidth(170);
        b.add(l, BorderLayout.CENTER);
        b.add(new Ui.Btn(button, true).onClick(e -> action.run()), BorderLayout.EAST);
        JPanel pad = new JPanel(new BorderLayout());
        pad.setOpaque(false);
        pad.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        pad.add(b);
        banner.add(pad);
        banner.setVisible(true);
        revalidate();
    }

    void refresh() {
        rows.removeAll();
        List<Object> all = client.db.conversations();
        if (all.isEmpty()) {
            Ui.TLabel e = Ui.para("Noch keine Chats. Füge über das + oben einen Kontakt hinzu.", 13.5f, Theme.DIM);
            e.setWrapWidth(280);
            JPanel p = new JPanel(new BorderLayout());
            p.setOpaque(false);
            p.setBorder(BorderFactory.createEmptyBorder(24, 22, 0, 22));
            p.add(e);
            rows.add(p);
        }
        for (Object o : all) rows.add(new Row(o));
        rows.add(Box.createVerticalStrut(8));
        rows.revalidate();
        rows.repaint();
    }

    final class Row extends JComponent {
        private final String id, name, preview, time, avatar;
        private final int unread;
        private boolean hover;

        Row(Object o) {
            String pv;
            Store.Msg last;
            if (o instanceof Store.Group) {
                Store.Group g = (Store.Group) o;
                id = g.gid;
                name = g.name;
                avatar = g.avatar;
                unread = g.unread;
                last = client.db.lastMessage(id);
                pv = last == null ? "Gruppe" : (last.outgoing ? "Du: " : client.nameOf(last.sender) + ": ") + Client.previewText(last.kind, last.body, last.fileName);
            } else {
                Store.Contact c = (Store.Contact) o;
                id = c.pub;
                name = client.nameOf(c.pub);
                avatar = c.avatar;
                unread = c.unread;
                last = client.db.lastMessage(id);
                pv = last == null ? "Neuer Kontakt" : (last.outgoing ? "Du: " : "") + Client.previewText(last.kind, last.body, last.fileName);
            }
            preview = pv;
            time = last == null ? "" : timeLabel(last.ts);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setAlignmentX(LEFT_ALIGNMENT);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { win.openChat(id); }
            });
        }

        @Override public Dimension getPreferredSize() { return new Dimension(320, 70); }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, 70); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.aa(g);
            int w = getWidth(), h = getHeight();
            if (id.equals(selected)) { g.setColor(Theme.SELECT); g.fill(Theme.round(8, 2, w - 16, h - 4, 10)); }
            else if (hover) { g.setColor(Theme.HOVER); g.fill(Theme.round(8, 2, w - 16, h - 4, 10)); }
            Theme.paintAvatar(g, 18, (h - 46) / 2, 46, avatar == null ? null : MsgView.imageOf(avatar), name, id);
            if (client.isOnline(id)) {
                g.setColor(Theme.CARD);
                g.fillOval(18 + 33, (h - 46) / 2 + 33, 15, 15);
                g.setColor(Theme.ACCENT);
                g.fillOval(18 + 35, (h - 46) / 2 + 35, 11, 11);
            }
            Font nf = Theme.font(15, true), pf = Theme.font(13, false), tf = Theme.font(11.5f, false);
            FontMetrics fm = g.getFontMetrics(nf);
            float tw = Theme.lineWidth(g, time, tf);
            int x = 76;
            Theme.drawLine(g, name, nf, x, 14 + fm.getAscent(), w - x - tw - 26, Theme.TEXT);
            Theme.drawLine(g, time, tf, w - 18 - tw, 16 + fm.getAscent() - 2, 0, unread > 0 ? Theme.ACCENT : Theme.DIM);
            float badgeW = 0;
            if (unread > 0) {
                String u = unread > 99 ? "99+" : String.valueOf(unread);
                badgeW = Math.max(20, Theme.lineWidth(g, u, Theme.font(11.5f, true)) + 12);
                float bx = w - 18 - badgeW, by = 39;
                g.setColor(Theme.ACCENT);
                g.fill(Theme.round(bx, by, badgeW, 20, 10));
                FontMetrics bf = g.getFontMetrics(Theme.font(11.5f, true));
                float uw = Theme.lineWidth(g, u, Theme.font(11.5f, true));
                Theme.drawLine(g, u, Theme.font(11.5f, true), bx + (badgeW - uw) / 2f, by + (20 + bf.getAscent() - bf.getDescent()) / 2f, 0, Color.WHITE);
            }
            FontMetrics pm = g.getFontMetrics(pf);
            Theme.drawLine(g, preview, pf, x, 38 + pm.getAscent(), w - x - badgeW - 30, Theme.DIM);
            g.dispose();
        }
    }

    static String timeLabel(long ts) {
        String day = MsgView.dayLabel(ts);
        if (day.equals("Heute")) return MsgView.time(ts);
        if (day.equals("Gestern")) return "Gestern";
        return new java.text.SimpleDateFormat("dd.MM.yy").format(new java.util.Date(ts));
    }
}
