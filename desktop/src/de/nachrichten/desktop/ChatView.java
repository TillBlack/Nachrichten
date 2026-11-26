package de.nachrichten.desktop;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;

/** Rechte Seite: geöffnete Unterhaltung (Kopfzeile, Nachrichten, Eingabe). */
final class ChatView extends JPanel implements MsgView.Actions {

    static final String[] REACTIONS = {"👍", "❤️", "😂", "😮", "😢", "🙏", "😅"};

    private final MainWindow win;
    private final Client client;
    private final Store db;
    String conv;
    private boolean group;

    private final AvatarComp headAv = new AvatarComp(40);
    private final Ui.TLabel title = Ui.label("", 16, Theme.TEXT, true);
    private final Ui.TLabel sub = Ui.label("", 12.5f, Theme.DIM, false);
    private final MsgList list = new MsgList();
    private final JScrollPane scroll;
    private final JPanel replyBar;
    private final Ui.TLabel replyWho = Ui.label("", 12.5f, Theme.ACCENT, true), replyWhat = Ui.label("", 12.5f, Theme.DIM, false);
    private String replyTo;
    private final Ui.EmojiText input = new Ui.EmojiText(true, 14.5f, "Nachricht");
    private final JScrollPane inputScroll;
    private final Map<String, MsgView> views = new HashMap<>();
    private long typingUntil;
    private final Timer subTimer;

    ChatView(MainWindow win, Client client) {
        super(new BorderLayout());
        this.win = win;
        this.client = client;
        this.db = client.db;
        setBackground(Theme.BG);

        // ---------------- Kopfzeile
        JPanel head = new JPanel(new BorderLayout(12, 0)) {
            @Override protected void paintComponent(Graphics g) {
                Theme.paintBg(g, this, Theme.CARD);
                g.setColor(Theme.LINE);
                g.fillRect(0, getHeight() - 1, getWidth(), 1);
            }
        };
        head.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 10));
        head.add(headAv, BorderLayout.WEST);
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.add(Box.createVerticalGlue());
        titles.add(title);
        titles.add(sub);
        titles.add(Box.createVerticalGlue());
        head.add(titles, BorderLayout.CENTER);
        Ui.IconBtn more = new Ui.IconBtn(Theme.Sym.MORE, 36, Theme.TEXT, false, "Mehr");
        more.onClick(e -> headerMenu(more));
        head.add(more, BorderLayout.EAST);
        MouseAdapter openInfo = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (conv != null) { if (group) Dialogs.groupInfo(win, client, conv); else Dialogs.contactInfo(win, client, conv); } }
        };
        titles.addMouseListener(openInfo);
        headAv.addMouseListener(openInfo);
        titles.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        add(head, BorderLayout.NORTH);

        // ---------------- Nachrichten
        scroll = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        Ui.styleScroll(scroll);
        scroll.getViewport().setOpaque(true);
        scroll.getViewport().setBackground(Theme.BG);
        add(scroll, BorderLayout.CENTER);

        // ---------------- Eingabe
        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setBackground(Theme.CARD);
        bottom.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.LINE));

        replyBar = new JPanel(new BorderLayout(10, 0)) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                g.setColor(Theme.FIELD);
                g.fill(Theme.round(12, 8, getWidth() - 24, getHeight() - 10, 10));
                g.setColor(Theme.ACCENT);
                g.fill(Theme.round(12, 8, 4, getHeight() - 10, 2));
                g.dispose();
            }
        };
        replyBar.setOpaque(false);
        replyBar.setBorder(BorderFactory.createEmptyBorder(12, 26, 4, 16));
        JPanel rt = new JPanel();
        rt.setOpaque(false);
        rt.setLayout(new BoxLayout(rt, BoxLayout.Y_AXIS));
        rt.add(replyWho);
        rt.add(replyWhat);
        replyBar.add(rt, BorderLayout.CENTER);
        replyBar.add(new Ui.IconBtn(Theme.Sym.CLOSE, 28, Theme.DIM, false, "Antwort abbrechen").onClick(e -> setReply(null)), BorderLayout.EAST);
        replyBar.setVisible(false);
        bottom.add(replyBar);

        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 12));
        Ui.IconBtn attach = new Ui.IconBtn(Theme.Sym.ATTACH, 40, Theme.DIM, false, "Bild oder Datei senden");
        attach.onClick(e -> chooseAttachment());
        JPanel attWrap = new JPanel(new BorderLayout());
        attWrap.setOpaque(false);
        attWrap.add(attach, BorderLayout.SOUTH);
        bar.add(attWrap, BorderLayout.WEST);

        inputScroll = new JScrollPane(input, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
            @Override public Dimension getPreferredSize() {
                Dimension d = input.getPreferredSize();
                return new Dimension(100, Math.max(40, Math.min(140, d.height + 2)));
            }
        };
        Ui.styleScroll(inputScroll);
        bar.add(Ui.fieldBox(inputScroll), BorderLayout.CENTER);
        Ui.IconBtn send = new Ui.IconBtn(Theme.Sym.SEND, 40, Theme.ON_ACCENT, true, "Senden (Enter)");
        send.onClick(e -> sendText());
        JPanel sendWrap = new JPanel(new BorderLayout());
        sendWrap.setOpaque(false);
        sendWrap.add(send, BorderLayout.SOUTH);
        bar.add(sendWrap, BorderLayout.EAST);
        bottom.add(bar);
        add(bottom, BorderLayout.SOUTH);

        input.onEnter = this::sendText;
        input.onChange = () -> {
            if (conv != null && input.value().trim().length() >= 2) client.typing(conv);
            inputScroll.revalidate();
            revalidate();
        };
        // Bild aus der Zwischenablage einfügen (Strg + V)
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_V, java.awt.event.InputEvent.CTRL_DOWN_MASK), "pasteSmart");
        input.getActionMap().put("pasteSmart", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { pasteSmart(); }
        });

        // Dateien hineinziehen
        TransferHandler th = new TransferHandler() {
            @Override public boolean canImport(TransferSupport s) { return conv != null && s.isDataFlavorSupported(DataFlavor.javaFileListFlavor); }
            @Override public boolean importData(TransferSupport s) {
                try {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    SwingUtilities.invokeLater(() -> sendFiles(files));
                    return true;
                } catch (Exception e) { return false; }
            }
        };
        setTransferHandler(th);
        list.setTransferHandler(th);

        subTimer = new Timer(1500, e -> updateSub());
        subTimer.start();
    }

    // ================================================================== Öffnen / Neu laden

    void open(String c) {
        if (c != null && c.equals(conv)) return;
        conv = c;
        group = db.group(c) != null;
        setReply(null);
        input.setText("");
        list.loaded = 300;
        updateHeader();
        reload(false);
        db.setUnread(c, 0);
        client.chatSeen(c);
        SwingUtilities.invokeLater(input::requestFocusInWindow);
    }

    void close() {
        conv = null;
    }

    void updateHeader() {
        if (conv == null) return;
        if (group) {
            Store.Group g = db.group(conv);
            if (g == null) return;
            title.setText(g.name);
            headAv.set(conv, g.name, g.avatar);
        } else {
            Store.Contact c = db.contact(conv);
            title.setText(client.nameOf(conv));
            headAv.set(conv, client.nameOf(conv), c == null ? null : c.avatar);
        }
        updateSub();
    }

    private void updateSub() {
        if (conv == null) return;
        if (group) {
            Store.Group g = db.group(conv);
            sub.setText(g == null ? "" : g.members.size() + " Mitglieder");
            sub.setColor(Theme.DIM);
            return;
        }
        if (System.currentTimeMillis() < typingUntil) { sub.setText("schreibt …"); sub.setColor(Theme.ACCENT); }
        else if (client.isOnline(conv)) { sub.setText("online"); sub.setColor(Theme.ACCENT); }
        else { sub.setText("Ende-zu-Ende-verschlüsselt"); sub.setColor(Theme.DIM); }
    }

    void typing(String peer) {
        if (!peer.equals(conv)) return;
        typingUntil = System.currentTimeMillis() + 4500;
        updateSub();
    }

    void reload(boolean keep) {
        if (conv == null) return;
        boolean atBottom = isAtBottom();
        int oldValue = scroll.getVerticalScrollBar().getValue();
        list.removeAll();
        views.clear();
        List<Store.Msg> msgs = db.messages(conv);
        int from = Math.max(0, msgs.size() - list.loaded);
        if (from > 0) {
            Ui.Btn older = new Ui.Btn("Ältere Nachrichten laden", false).onClick(e -> { list.loaded += 300; reload(false); });
            JPanel w = new JPanel();
            w.setOpaque(false);
            w.add(older);
            list.add(w);
        }
        String lastDay = null, lastSender = null;
        long lastTs = 0;
        for (int i = from; i < msgs.size(); i++) {
            Store.Msg m = msgs.get(i);
            String day = MsgView.dayKey(m.ts);
            if (!day.equals(lastDay)) { list.add(new MsgView.DayChip(MsgView.dayLabel(m.ts))); lastDay = day; lastSender = null; }
            boolean showSender = group && !m.outgoing && (!java.util.Objects.equals(m.sender, lastSender) || m.ts - lastTs > 10 * 60 * 1000);
            MsgView v = new MsgView(m, client, group, showSender, this);
            views.put(m.id, v);
            list.add(v);
            lastSender = m.outgoing ? null : m.sender;
            lastTs = m.ts;
        }
        list.revalidate();
        list.repaint();
        SwingUtilities.invokeLater(() -> {
            if (!keep || atBottom) scrollToBottom();
            else scroll.getVerticalScrollBar().setValue(oldValue);
        });
    }

    private boolean isAtBottom() {
        javax.swing.JScrollBar b = scroll.getVerticalScrollBar();
        return b.getValue() + b.getVisibleAmount() >= b.getMaximum() - 40;
    }

    private void scrollToBottom() {
        list.validate();
        javax.swing.JScrollBar b = scroll.getVerticalScrollBar();
        b.setValue(b.getMaximum());
        SwingUtilities.invokeLater(() -> b.setValue(b.getMaximum()));
    }

    // ================================================================== Senden

    private void sendText() {
        if (conv == null) return;
        String t = input.value().trim();
        if (t.isEmpty()) return;
        if (group) client.sendGroup(conv, t, replyTo); else client.send(conv, t, replyTo);
        input.setText("");
        setReply(null);
    }

    private void chooseAttachment() {
        if (conv == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Bild oder Datei senden");
        fc.setMultiSelectionEnabled(true);
        if (fc.showOpenDialog(win) != JFileChooser.APPROVE_OPTION) return;
        File[] fs = fc.getSelectedFiles();
        List<File> files = new ArrayList<>();
        if (fs != null && fs.length > 0) for (File f : fs) files.add(f); else if (fc.getSelectedFile() != null) files.add(fc.getSelectedFile());
        sendFiles(files);
    }

    void sendFiles(List<File> files) {
        if (conv == null || files == null || files.isEmpty()) return;
        String target = conv;
        String rt = replyTo;
        setReply(null);
        new Thread(() -> {
            for (File f : files) {
                Attachment a = Attachment.prepare(f);
                if (a.error != null) { SwingUtilities.invokeLater(() -> Ui.info(win, "Nicht gesendet", a.error)); continue; }
                client.sendAttachment(target, a.data, a.name, a.mime, a.image, rt);
            }
        }, "anhang").start();
    }

    private void pasteSmart() {
        try {
            Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (t != null && t.isDataFlavorSupported(DataFlavor.imageFlavor) && !t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                Image img = (Image) t.getTransferData(DataFlavor.imageFlavor);
                if (conv != null && Ui.confirm(win, "Bild senden?", "Das Bild aus der Zwischenablage an diesen Chat senden?", "Senden")) {
                    String target = conv;
                    new Thread(() -> {
                        Attachment a = Attachment.fromImage(img, "Bild.jpg");
                        if (a.error == null) client.sendAttachment(target, a.data, a.name, a.mime, true, null);
                    }).start();
                }
                return;
            }
            if (t != null && t.isDataFlavorSupported(DataFlavor.javaFileListFlavor) && !t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                sendFiles(files);
                return;
            }
        } catch (Exception ignored) { }
        input.paste();
    }

    private void setReply(String id) {
        replyTo = id;
        if (id == null) { replyBar.setVisible(false); revalidate(); return; }
        Store.Msg m = db.message(id);
        if (m == null) { replyTo = null; replyBar.setVisible(false); return; }
        replyWho.setText(m.outgoing ? "Du" : client.nameOf(m.sender != null ? m.sender : m.peer));
        replyWhat.setText(Client.previewText(m.kind, m.body, m.fileName));
        replyBar.setVisible(true);
        revalidate();
        input.requestFocusInWindow();
    }

    // ================================================================== Aktionen aus der Sprechblase

    @Override public void openImage(Store.Msg m) {
        if (m.filePath == null) return;
        Dialogs.imageViewer(win, m.filePath, m.fileName);
    }

    @Override public void saveFile(Store.Msg m) {
        if (m.filePath == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Speichern unter");
        fc.setSelectedFile(new File(m.fileName == null ? "Datei" : (Client.isVoiceName(m.fileName) ? "Sprachnachricht.aac" : m.fileName)));
        if (fc.showSaveDialog(win) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.copy(new File(m.filePath).toPath(), fc.getSelectedFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Ui.info(win, "Fehler", "Die Datei konnte nicht gespeichert werden.");
        }
    }

    @Override public void playVoice(Store.Msg m) {
        if (m.filePath == null) return;
        try {
            // Sprachnachrichten sind AAC – Windows spielt sie mit dem Standard-Player ab
            File tmp = new File(System.getProperty("java.io.tmpdir"), "Sprachnachricht_" + m.id.substring(0, 8) + ".aac");
            Files.copy(new File(m.filePath).toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Desktop.getDesktop().open(tmp);
        } catch (Exception e) {
            Ui.info(win, "Abspielen nicht möglich", "Windows hat kein Programm für AAC-Audio gefunden. Du kannst die Sprachnachricht über Rechtsklick → Speichern sichern.");
        }
    }

    @Override public void jumpTo(String msgId) {
        MsgView v = views.get(msgId);
        if (v == null) return;
        list.scrollRectToVisible(new Rectangle(0, v.getY() - 40, 10, v.getHeight() + 80));
        v.flash();
    }

    @Override public void react(Store.Msg m, JComponent at, int x, int y) {
        Pop p = new Pop();
        p.reactions(m);
        p.show(at, x, y);
    }

    @Override public void menu(Store.Msg m, JComponent at, int x, int y) {
        Pop p = new Pop();
        p.reactions(m);
        p.item("Antworten", Theme.TEXT, () -> setReply(m.id));
        if (m.kind == Store.TEXT) p.item("Text kopieren", Theme.TEXT, () ->
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(m.body), null));
        if (m.kind != Store.TEXT && m.filePath != null) p.item("Speichern unter …", Theme.TEXT, () -> saveFile(m));
        p.item("Für mich löschen", Theme.RED, () -> {
            if (Ui.confirm(win, "Nachricht löschen", "Diese Nachricht nur auf diesem PC löschen?", "Löschen")) client.deleteForMe(conv, m.id);
        });
        if (m.outgoing && !group) p.item("Für alle löschen", Theme.RED, () -> {
            if (Ui.confirm(win, "Für alle löschen", "Diese Nachricht für alle löschen?", "Für alle löschen")) client.deleteForEveryone(conv, m.id);
        });
        p.show(at, x, y);
    }

    private void headerMenu(Component at) {
        if (conv == null) return;
        Pop p = new Pop();
        if (group) {
            p.item("Gruppeninfo", Theme.TEXT, () -> Dialogs.groupInfo(win, client, conv));
            if (db.isAdmin(conv, client.keys.pubHex())) {
                p.item("Mitglieder hinzufügen", Theme.TEXT, () -> Dialogs.addMembers(win, client, conv));
                p.item("Gruppe umbenennen", Theme.TEXT, () -> {
                    String n = Ui.ask(win, "Gruppe umbenennen", "Gruppenname", db.group(conv).name, "Speichern");
                    if (n != null && !n.isEmpty()) client.renameGroup(conv, n);
                });
            }
            p.item("Gruppe verlassen", Theme.RED, () -> {
                if (Ui.confirm(win, "Gruppe verlassen", "Die Gruppe auf diesem PC verlassen? Die Nachrichten werden hier gelöscht.", "Verlassen")) {
                    String g = conv;
                    win.closeChat();
                    client.leaveGroup(g);
                }
            });
        } else {
            p.item("Kontaktinfo", Theme.TEXT, () -> Dialogs.contactInfo(win, client, conv));
            p.item("Umbenennen", Theme.TEXT, () -> {
                String n = Ui.ask(win, "Kontakt umbenennen", "Name", client.nameOf(conv), "Speichern");
                if (n != null) { db.rename(conv, n); win.refreshAll(); }
            });
            p.item("Kontakt löschen", Theme.RED, () -> {
                if (Ui.confirm(win, "Kontakt löschen", "Kontakt und alle Nachrichten auf diesem PC löschen?", "Löschen")) {
                    String c = conv;
                    win.closeChat();
                    db.deleteContact(c);
                    win.refreshAll();
                }
            });
        }
        p.show(at, 0, at.getHeight());
    }

    // ================================================================== Popup-Menü (dunkel, mit Emojis)

    final class Pop extends JPopupMenu {
        private final JPanel box = new JPanel();

        Pop() {
            setBorder(BorderFactory.createEmptyBorder());
            setOpaque(false);
            setBackground(new Color(0, 0, 0, 0));
            box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
            box.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            JPanel card = new JPanel(new BorderLayout()) {
                @Override protected void paintComponent(Graphics g0) {
                    Graphics2D g = (Graphics2D) g0.create();
                    Theme.aa(g);
                    g.setColor(Theme.CARD);
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(Theme.ACCENT);
                    g.setStroke(new java.awt.BasicStroke(1.4f));
                    g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                    g.dispose();
                }
            };
            card.add(box);
            add(card);
        }

        void reactions(Store.Msg m) {
            String mine = db.myReaction(m.id, client.keys.pubHex());
            JPanel r = new JPanel();
            r.setOpaque(false);
            r.setLayout(new BoxLayout(r, BoxLayout.X_AXIS));
            for (String em : REACTIONS) {
                boolean sel = em.equals(mine);
                JComponent c = new JComponent() {
                    boolean hover;
                    {
                        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                        addMouseListener(new MouseAdapter() {
                            @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                            @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                            @Override public void mouseReleased(MouseEvent e) {
                                setVisible(false);
                                Pop.this.setVisible(false);
                                client.sendReaction(conv, m.id, sel ? "" : em);
                            }
                        });
                    }
                    @Override public Dimension getPreferredSize() { return new Dimension(40, 40); }
                    @Override public Dimension getMaximumSize() { return getPreferredSize(); }
                    @Override protected void paintComponent(Graphics g0) {
                        Graphics2D g = (Graphics2D) g0.create();
                        Theme.aa(g);
                        if (sel || hover) { g.setColor(sel ? Theme.alpha(Theme.ACCENT, 90) : Theme.alpha(Color.WHITE, 25)); g.fillOval(2, 2, 36, 36); }
                        Font f = Theme.emojiFont(21);
                        float w = Theme.lineWidth(g, em, f);
                        FontMetrics fm = g.getFontMetrics(f);
                        Theme.drawLine(g, em, f, (40 - w) / 2f, (40 + fm.getAscent() - fm.getDescent()) / 2f, 0, Theme.TEXT);
                        g.dispose();
                    }
                };
                r.add(c);
            }
            r.setAlignmentX(LEFT_ALIGNMENT);
            box.add(r);
        }

        void item(String text, Color color, Runnable run) {
            if (box.getComponentCount() > 0 && !(box.getComponent(box.getComponentCount() - 1) instanceof MenuRow)) box.add(Box.createVerticalStrut(4));
            MenuRow row = new MenuRow(text, color, () -> { setVisible(false); SwingUtilities.invokeLater(run); });
            box.add(row);
        }
    }

    static final class MenuRow extends JComponent {
        private final String text;
        private final Color color;
        private boolean hover;

        MenuRow(String text, Color color, Runnable run) {
            this.text = text;
            this.color = color;
            setAlignmentX(LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mouseReleased(MouseEvent e) { run.run(); }
            });
        }

        @Override public Dimension getPreferredSize() {
            return new Dimension(Math.max(220, (int) Theme.lineWidth(Ui.scratch(), text, Theme.font(14, false)) + 36), 34);
        }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, 34); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.aa(g);
            if (hover) { g.setColor(Theme.alpha(Color.WHITE, 18)); g.fill(Theme.round(0, 0, getWidth(), getHeight(), 6)); }
            Font f = Theme.font(14, false);
            FontMetrics fm = g.getFontMetrics(f);
            Theme.drawLine(g, text, f, 12, (getHeight() + fm.getAscent() - fm.getDescent()) / 2f, getWidth() - 20, color);
            g.dispose();
        }
    }

    // ================================================================== Nachrichtenliste

    /** Stapelt Nachrichten untereinander über die volle Breite (Breite = Fenster). */
    static final class MsgList extends JPanel implements Scrollable {
        int loaded = 300;

        MsgList() {
            setOpaque(true);
            setBackground(Theme.BG);
            setLayout(new LayoutManager() {
                @Override public void addLayoutComponent(String n, Component c) { }
                @Override public void removeLayoutComponent(Component c) { }
                @Override public Dimension preferredLayoutSize(Container p) {
                    int w = Math.max(300, p.getWidth());
                    int h = 10;
                    for (Component c : p.getComponents()) h += heightOf(c, w);
                    return new Dimension(w, h + 10);
                }
                @Override public Dimension minimumLayoutSize(Container p) { return new Dimension(200, 50); }
                @Override public void layoutContainer(Container p) {
                    int w = p.getWidth(), y = 10;
                    for (Component c : p.getComponents()) {
                        int h = heightOf(c, w);
                        c.setBounds(0, y, w, h);
                        y += h;
                    }
                }
            });
        }

        private static int heightOf(Component c, int w) {
            if (c instanceof MsgView) return ((MsgView) c).layoutFor(w);
            return c.getPreferredSize().height;
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - 40; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getParent().getHeight() > getPreferredSize().height;
        }
    }

    // ================================================================== Anhänge vorbereiten

    /** Bild verkleinern (max. 1600 px, JPEG) bzw. Datei prüfen (max. 1 MB wie am Handy). */
    static final class Attachment {
        byte[] data;
        String name, mime, error;
        boolean image;

        static Attachment prepare(File f) {
            Attachment a = new Attachment();
            String n = f.getName();
            String lower = n.toLowerCase();
            try {
                if (lower.matches(".*\\.(jpe?g|png|gif|bmp)$")) {
                    BufferedImage img = ImageIO.read(f);
                    if (img != null) {
                        Attachment r = fromImage(img, n.replaceAll("\\.[^.]+$", "") + ".jpg");
                        if (r.error == null) return r;
                    }
                }
                long len = f.length();
                if (len > de.nachrichten.crypto.Proto.MAX_FILE) {
                    a.error = "„" + n + "“ ist zu groß. Dateien dürfen höchstens 1 MB groß sein (Bilder werden automatisch verkleinert).";
                    return a;
                }
                a.data = Files.readAllBytes(f.toPath());
                a.name = n;
                String mime = Files.probeContentType(f.toPath());
                a.mime = mime == null ? "application/octet-stream" : mime;
                a.image = false;
                return a;
            } catch (Exception e) {
                a.error = "„" + n + "“ konnte nicht gelesen werden.";
                return a;
            }
        }

        static Attachment fromImage(Image src, String name) {
            Attachment a = new Attachment();
            try {
                int w = src.getWidth(null), h = src.getHeight(null);
                if (w <= 0 || h <= 0) { a.error = "Das Bild ist leer."; return a; }
                int max = 1600;
                while (true) {
                    double s = Math.min(1.0, max / (double) Math.max(w, h));
                    int nw = Math.max(1, (int) (w * s)), nh = Math.max(1, (int) (h * s));
                    BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = out.createGraphics();
                    g.setColor(Color.WHITE);
                    g.fillRect(0, 0, nw, nh);
                    Theme.aa(g);
                    g.drawImage(src, 0, 0, nw, nh, null);
                    g.dispose();
                    for (float q : new float[]{0.85f, 0.75f, 0.62f}) {
                        byte[] jpg = jpeg(out, q);
                        if (jpg.length <= de.nachrichten.crypto.Proto.MAX_FILE) {
                            a.data = jpg;
                            a.name = name;
                            a.mime = "image/jpeg";
                            a.image = true;
                            return a;
                        }
                    }
                    max = (int) (max * 0.75);
                    if (max < 300) { a.error = "Das Bild ist zu groß."; return a; }
                }
            } catch (Exception e) {
                a.error = "Das Bild konnte nicht verarbeitet werden.";
                return a;
            }
        }

        static byte[] jpeg(BufferedImage img, float q) throws Exception {
            ImageWriter w = ImageIO.getImageWritersByFormatName("jpg").next();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(bo)) {
                w.setOutput(ios);
                ImageWriteParam p = w.getDefaultWriteParam();
                p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                p.setCompressionQuality(q);
                w.write(null, new IIOImage(img, null, null), p);
            } finally {
                w.dispose();
            }
            return bo.toByteArray();
        }
    }

    // ================================================================== Avatar

    static final class AvatarComp extends JComponent {
        private final int size;
        private String seed = "", name = "", path;

        AvatarComp(int size) { this.size = size; setOpaque(false); }

        void set(String seed, String name, String path) { this.seed = seed; this.name = name; this.path = path; repaint(); }

        @Override public Dimension getPreferredSize() { return new Dimension(size, size); }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            int y = (getHeight() - size) / 2;
            Theme.paintAvatar(g, 0, y, size, path == null ? null : MsgView.imageOf(path), name, seed);
            g.dispose();
        }
    }

    static int center(int total, int part) { return (total - part) / 2; }
    static final int LEFT = SwingConstants.LEFT;
}
