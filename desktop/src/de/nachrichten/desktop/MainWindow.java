package de.nachrichten.desktop;

import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** Hauptfenster: Anmeldung beim ersten Start, danach Chatliste links und Unterhaltung rechts. */
final class MainWindow extends JFrame implements Client.Listener {

    static final String RELEASE_PAGE = "https://github.com/TillBlack/Nachrichten/releases/tag/windows";
    static final String VERSION_URL = "https://github.com/TillBlack/Nachrichten/releases/download/windows/win-version.json";

    private final Store db;
    private final Keys keys;
    private Client client;
    private ChatList chatList;
    private ChatView chatView;
    private final JPanel right = new JPanel(new CardLayout());
    private TrayIcon tray;
    private boolean started;
    private final List<Image> icons = new ArrayList<>();

    MainWindow(Store db, Keys keys) {
        super("Nachrichten");
        this.db = db;
        this.keys = keys;
        try {
            BufferedImage base = javax.imageio.ImageIO.read(MainWindow.class.getResourceAsStream("/icon.png"));
            for (int s : new int[]{16, 24, 32, 48, 64, 128, 256}) icons.add(base.getScaledInstance(s, s, Image.SCALE_SMOOTH));
            setIconImages(icons);
        } catch (Exception ignored) { }
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(820, 540));
        setSize(1120, 740);
        setLocationRelativeTo(null);
        getContentPane().setBackground(Theme.BG);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { onClose(); }
            @Override public void windowActivated(WindowEvent e) { focusChanged(true); }
            @Override public void windowDeactivated(WindowEvent e) { focusChanged(false); }
            @Override public void windowIconified(WindowEvent e) { focusChanged(false); }
        });
        if (keys.has()) startMain(); else showWelcome();
    }

    // ================================================================== erster Start

    private void showWelcome() {
        JPanel p = new JPanel(new java.awt.GridBagLayout());
        p.setBackground(Theme.BG);
        JPanel box = new JPanel() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                g.setColor(Theme.CARD);
                g.fill(Theme.round(0, 0, getWidth(), getHeight(), 18));
                g.setColor(Theme.ACCENT);
                g.setStroke(new java.awt.BasicStroke(1.6f));
                g.draw(Theme.round(0.8f, 0.8f, getWidth() - 1.6f, getHeight() - 1.6f, 18));
                g.dispose();
            }
        };
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createEmptyBorder(28, 30, 26, 30));
        box.add(Ui.label("Willkommen bei Nachrichten", 24, Theme.TEXT, true));
        box.add(Box.createVerticalStrut(10));
        Ui.TLabel t = Ui.para("Deine Nachrichten sind Ende-zu-Ende-verschlüsselt. Es gibt kein Konto mit Telefonnummer oder E-Mail – deine Identität ist ein geheimer Schlüssel, der auf diesem PC liegt.", 14, Theme.DIM);
        t.setWrapWidth(440);
        box.add(t);
        box.add(Box.createVerticalStrut(22));
        box.add(Ui.label("Neu hier?", 14, Theme.ACCENT, true));
        box.add(Box.createVerticalStrut(6));
        Ui.EmojiText name = new Ui.EmojiText(false, 15, "Dein Name");
        box.add(Ui.fieldBox(name));
        box.add(Box.createVerticalStrut(8));
        box.add(Ui.row(new Ui.Btn("Neues Konto erstellen", true).onClick(e -> {
            String n = name.value().trim();
            if (n.length() > 40) n = n.substring(0, 40);
            keys.create();
            db.setPref("name", n);
            startMain();
        })));
        box.add(Box.createVerticalStrut(22));
        box.add(Ui.label("Konto vom Handy hier nutzen", 14, Theme.ACCENT, true));
        box.add(Box.createVerticalStrut(6));
        Ui.TLabel h = Ui.para("In der Handy-App unter Einstellungen → „Sicherungsschlüssel anzeigen“. Dann schreibst du am PC und am Handy mit demselben Konto.", 13, Theme.DIM);
        h.setWrapWidth(440);
        box.add(h);
        box.add(Box.createVerticalStrut(8));
        Ui.EmojiText key = new Ui.EmojiText(false, 14, "Sicherungsschlüssel (nsec1…)");
        box.add(Ui.fieldBox(key));
        Ui.TLabel err = Ui.label(" ", 12.5f, Theme.RED, false);
        box.add(Box.createVerticalStrut(4));
        box.add(err);
        box.add(Box.createVerticalStrut(4));
        Runnable restore = () -> {
            if (!keys.restore(key.value())) { err.setText("Das ist kein gültiger Sicherungsschlüssel."); return; }
            startMain();
        };
        key.onEnter = restore;
        box.add(Ui.row(new Ui.Btn("Konto übernehmen", false).onClick(e -> restore.run())));
        box.setPreferredSize(new Dimension(500, box.getPreferredSize().height));
        p.add(box);
        setContentPane(p);
        revalidate();
        repaint();
    }

    // ================================================================== Hauptansicht

    private void startMain() {
        if (started) return;
        started = true;
        client = new Client(db, keys);
        client.addListener(this);
        chatList = new ChatList(this, client);
        chatView = new ChatView(this, client);

        JPanel empty = new JPanel(new java.awt.GridBagLayout());
        empty.setBackground(Theme.BG);
        JPanel e = new JPanel();
        e.setOpaque(false);
        e.setLayout(new BoxLayout(e, BoxLayout.Y_AXIS));
        JComponent lock = new JComponent() {
            @Override public Dimension getPreferredSize() { return new Dimension(64, 64); }
            @Override public Dimension getMaximumSize() { return getPreferredSize(); }
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                g.setColor(Theme.alpha(Theme.ACCENT, 50));
                g.fillOval(0, 0, 64, 64);
                g.translate(14, 14);
                g.setColor(Theme.ACCENT);
                Theme.drawSym(g, Theme.Sym.LOCK, 36);
                g.dispose();
            }
        };
        lock.setAlignmentX(0.5f);
        e.add(lock);
        e.add(Box.createVerticalStrut(14));
        Ui.TLabel t1 = Ui.label("Nachrichten für Windows", 19, Theme.TEXT, true);
        t1.setAlignmentX(0.5f);
        e.add(t1);
        e.add(Box.createVerticalStrut(6));
        Ui.TLabel t2 = Ui.label("Wähle links einen Chat aus. Alles ist Ende-zu-Ende-verschlüsselt.", 13.5f, Theme.DIM, false);
        t2.setAlignmentX(0.5f);
        e.add(t2);
        empty.add(e);

        right.removeAll();
        right.add(empty, "empty");
        right.add(chatView, "chat");
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BG);
        JPanel sep = new JPanel();
        sep.setBackground(Theme.LINE);
        sep.setPreferredSize(new Dimension(1, 1));
        JPanel left = new JPanel(new BorderLayout());
        left.add(chatList);
        left.add(sep, BorderLayout.EAST);
        root.add(left, BorderLayout.WEST);
        root.add(right, BorderLayout.CENTER);
        setContentPane(root);
        chatList.refresh();
        revalidate();
        repaint();
        setupTray();
        client.start();
        updateLock();
        updateTitle();
        checkUpdate();
    }

    void openChat(String conv) {
        if (chatView == null) return;
        ((CardLayout) right.getLayout()).show(right, "chat");
        chatView.open(conv);
        chatList.setSelected(conv);
        client.openChat = isActive() ? conv : null;
        chatList.refresh();
        updateTitle();
    }

    void closeChat() {
        if (chatView == null) return;
        chatView.close();
        client.openChat = null;
        chatList.setSelected(null);
        ((CardLayout) right.getLayout()).show(right, "empty");
    }

    void refreshAll() {
        if (chatList == null) return;
        chatList.refresh();
        if (chatView.conv != null) { chatView.updateHeader(); chatView.reload(true); }
        updateTitle();
    }

    private void focusChanged(boolean active) {
        if (client == null) return;
        client.uiActive = active;
        client.openChat = active && chatView != null ? chatView.conv : null;
        if (active && chatView != null && chatView.conv != null) {
            db.setUnread(chatView.conv, 0);
            client.chatSeen(chatView.conv);
            chatList.refresh();
            updateTitle();
        }
    }

    private void updateTitle() {
        int n = db.totalUnread();
        setTitle(n > 0 ? "(" + n + ") Nachrichten" : "Nachrichten");
        if (tray != null) tray.setToolTip(n > 0 ? "Nachrichten – " + n + " ungelesen" : "Nachrichten");
    }

    // ================================================================== Client-Ereignisse (aus dem Netz-Thread)

    @Override public void onChanged(String conv) {
        SwingUtilities.invokeLater(() -> {
            if (chatList == null) return;
            chatList.refresh();
            if (chatView.conv != null) {
                if (db.contact(chatView.conv) == null && db.group(chatView.conv) == null) { closeChat(); return; }
                if (conv == null || conv.equals(chatView.conv)) { chatView.updateHeader(); chatView.reload(true); }
                if (conv != null && conv.equals(chatView.conv) && isActive()) { db.setUnread(conv, 0); client.chatSeen(conv); }
            }
            updateTitle();
        });
    }

    @Override public void onTyping(String peer) { SwingUtilities.invokeLater(() -> { if (chatView != null) chatView.typing(peer); }); }

    @Override public void onPresence(String peer) { SwingUtilities.invokeLater(() -> { if (chatList != null) chatList.repaint(); }); }

    @Override public void onStatus(int connected, int total) { SwingUtilities.invokeLater(() -> { if (chatList != null) chatList.setStatus(connected, total); }); }

    @Override public void onNotify(String conv, String title, String text) {
        SwingUtilities.invokeLater(() -> {
            if (!db.prefBool("notify", true)) return;
            if (isActive() && conv.equals(chatView.conv)) return;
            lastNotified = conv;
            if (tray != null) tray.displayMessage(title, text.length() > 160 ? text.substring(0, 160) + "…" : text, TrayIcon.MessageType.NONE);
            else if (!isActive()) toFront();
        });
    }

    private String lastNotified;

    @Override public void onLockChanged() { SwingUtilities.invokeLater(this::updateLock); }

    // ================================================================== Sperre

    private void updateLock() {
        if (client == null) return;
        if (!client.amBanned()) { getGlassPane().setVisible(false); return; }
        JPanel g = new JPanel(new java.awt.GridBagLayout()) {
            @Override protected void paintComponent(Graphics g0) { g0.setColor(new Color(10, 11, 12, 245)); g0.fillRect(0, 0, getWidth(), getHeight()); }
        };
        g.setOpaque(false);
        g.addMouseListener(new MouseAdapter() { });
        g.addKeyListener(new java.awt.event.KeyAdapter() { });
        JPanel b = new JPanel();
        b.setOpaque(false);
        b.setLayout(new BoxLayout(b, BoxLayout.Y_AXIS));
        Ui.TLabel t = Ui.label("Du wurdest gesperrt", 26, Theme.TEXT, true);
        t.setAlignmentX(0.5f);
        b.add(t);
        b.add(Box.createVerticalStrut(10));
        long until = client.myBanUntil();
        String when = until == -1 ? "Die Sperre ist dauerhaft." : "Die Sperre endet am " + new java.text.SimpleDateFormat("dd.MM.yyyy 'um' HH:mm").format(new java.util.Date(until)) + ".";
        Ui.TLabel w = Ui.label(when, 15, Theme.ACCENT, true);
        w.setAlignmentX(0.5f);
        b.add(w);
        if (client.deviceBanned()) {
            b.add(Box.createVerticalStrut(8));
            Ui.TLabel dv = Ui.label("Dieses Gerät ist gesperrt – auch ein neues Konto darauf ist gesperrt.", 13, Theme.DIM, false);
            dv.setAlignmentX(0.5f);
            b.add(dv);
        }
        b.add(Box.createVerticalStrut(8));
        Ui.TLabel h = Ui.label("Einspruch kannst du über die Handy-App einlegen.", 13, Theme.DIM, false);
        h.setAlignmentX(0.5f);
        b.add(h);
        g.add(b);
        setGlassPane(g);
        g.setVisible(true);
    }

    // ================================================================== Infobereich (Tray)

    private void setupTray() {
        if (!SystemTray.isSupported() || icons.isEmpty()) return;
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Öffnen");
            open.addActionListener(e -> showWindow());
            MenuItem quit = new MenuItem("Beenden");
            quit.addActionListener(e -> quit());
            menu.add(open);
            menu.addSeparator();
            menu.add(quit);
            tray = new TrayIcon(icons.get(1), "Nachrichten", menu);
            tray.setImageAutoSize(true);
            tray.addActionListener(e -> showWindow());
            tray.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { if (e.getButton() == MouseEvent.BUTTON1) showWindow(); }
            });
            SystemTray.getSystemTray().add(tray);
        } catch (AWTException | RuntimeException e) {
            tray = null;
        }
    }

    private void showWindow() {
        SwingUtilities.invokeLater(() -> {
            setVisible(true);
            setExtendedState(getExtendedState() & ~Frame.ICONIFIED);
            toFront();
            requestFocus();
            if (lastNotified != null && (db.contact(lastNotified) != null || db.group(lastNotified) != null)) openChat(lastNotified);
            lastNotified = null;
        });
    }

    private void onClose() {
        if (client != null && tray != null && db.prefBool("tray", true)) {
            setVisible(false);
            client.uiActive = false;
            client.openChat = null;
            if (!db.prefBool("trayHintShown", false)) {
                db.setPref("trayHintShown", true);
                tray.displayMessage("Nachrichten läuft weiter", "Du bekommst weiter Nachrichten. Beenden: Rechtsklick auf das Symbol unten rechts → Beenden.", TrayIcon.MessageType.INFO);
            }
            return;
        }
        quit();
    }

    void quit() {
        if (client != null) client.stop();
        db.saveNow();
        if (tray != null) SystemTray.getSystemTray().remove(tray);
        dispose();
        System.exit(0);
    }

    /** Konto und alle Daten von diesem PC entfernen, dann neu starten (Willkommen). */
    void removeAccount() {
        if (client != null) client.stop();
        keys.forget();
        try {
            java.nio.file.Files.walk(db.dir.toPath()).sorted(java.util.Comparator.reverseOrder())
                    .filter(p -> !p.equals(db.dir.toPath())).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) { }
        if (tray != null) SystemTray.getSystemTray().remove(tray);
        dispose();
        System.exit(0);
    }

    // ================================================================== Update-Hinweis

    private void checkUpdate() {
        Thread t = new Thread(() -> {
            try {
                HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(VERSION_URL)).timeout(Duration.ofSeconds(20)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) return;
                org.json.JSONObject j = new org.json.JSONObject(r.body());
                int code = j.optInt("versionCode", 0);
                String name = j.optString("versionName", "");
                if (code > Version.CODE) SwingUtilities.invokeLater(() -> chatList.showBanner("Neue Version " + name + " verfügbar", "Laden", () -> {
                    try { Desktop.getDesktop().browse(URI.create(RELEASE_PAGE)); } catch (Exception ignored) { }
                }));
            } catch (Exception ignored) { }
        }, "update");
        t.setDaemon(true);
        t.start();
    }
}
