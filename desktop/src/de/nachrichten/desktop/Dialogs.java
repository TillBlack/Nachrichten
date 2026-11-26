package de.nachrichten.desktop;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/** Alle Fenster der Windows-Version. */
final class Dialogs {

    // ================================================================== Kontakt hinzufügen

    static void addContact(MainWindow win, Client client) {
        Ui.Dlg d = new Ui.Dlg(win, "Kontakt hinzufügen", 460);
        Ui.TLabel info = Ui.para("Füge den Kontakt-Code der Person ein (npub1… oder nostr:npub1…). Sie findet ihn in der App unter ihrem QR-Code.", 13.5f, Theme.DIM);
        info.setWrapWidth(420);
        d.addRow(info);
        d.gap(10);
        Ui.EmojiText code = new Ui.EmojiText(true, 14, "npub1…");
        d.addRow(Ui.fieldBox(code));
        d.gap(8);
        Ui.EmojiText name = new Ui.EmojiText(false, 14, "Name (optional)");
        d.addRow(Ui.fieldBox(name));
        Ui.TLabel err = Ui.label(" ", 12.5f, Theme.RED, false);
        d.gap(6);
        d.addRow(err);
        Runnable ok = () -> {
            String[] c = Keys.parseContact(code.value());
            if (c == null) { err.setText("Das ist kein gültiger Kontakt-Code."); return; }
            if (c[0].equals(client.keys.pubHex())) { err.setText("Das ist dein eigener Code."); return; }
            String n = name.value().trim();
            if (n.isEmpty()) n = c[1];
            if (!client.db.addContact(c[0], n) && !n.isEmpty()) client.db.rename(c[0], n);
            d.close();
            win.refreshAll();
            win.openChat(c[0]);
        };
        code.onEnter = ok;
        name.onEnter = ok;
        d.buttons(new Ui.Btn("Abbrechen", false).onClick(e -> d.close()), new Ui.Btn("Hinzufügen", true).onClick(e -> ok.run()));
        SwingUtilities.invokeLater(code::requestFocusInWindow);
        d.showDlg();
    }

    // ================================================================== Gruppen

    static void newGroup(MainWindow win, Client client) {
        List<Store.Contact> cs = client.db.contactList();
        if (cs.isEmpty()) { Ui.info(win, "Neue Gruppe", "Füge zuerst Kontakte hinzu, dann kannst du eine Gruppe mit ihnen erstellen."); return; }
        Ui.Dlg d = new Ui.Dlg(win, "Neue Gruppe", 440);
        Ui.EmojiText name = new Ui.EmojiText(false, 15, "Gruppenname");
        d.addRow(Ui.fieldBox(name));
        d.gap(12);
        d.addRow(Ui.label("Mitglieder", 13, Theme.DIM, true));
        d.gap(6);
        Set<String> chosen = new LinkedHashSet<>();
        d.addRow(checkList(client, cs, chosen));
        Ui.TLabel err = Ui.label(" ", 12.5f, Theme.RED, false);
        d.gap(4);
        d.addRow(err);
        d.buttons(new Ui.Btn("Abbrechen", false).onClick(e -> d.close()), new Ui.Btn("Erstellen", true).onClick(e -> {
            String n = name.value().trim();
            if (n.isEmpty()) { err.setText("Bitte einen Gruppennamen eingeben."); return; }
            if (chosen.isEmpty()) { err.setText("Bitte mindestens ein Mitglied auswählen."); return; }
            String gid = client.createGroup(n, new ArrayList<>(chosen));
            d.close();
            win.refreshAll();
            win.openChat(gid);
        }));
        SwingUtilities.invokeLater(name::requestFocusInWindow);
        d.showDlg();
    }

    static void addMembers(MainWindow win, Client client, String gid) {
        List<Store.Contact> cs = new ArrayList<>();
        for (Store.Contact c : client.db.contactList()) if (!client.db.isMember(gid, c.pub)) cs.add(c);
        if (cs.isEmpty()) { Ui.info(win, "Mitglieder hinzufügen", "Alle deine Kontakte sind schon in dieser Gruppe."); return; }
        Ui.Dlg d = new Ui.Dlg(win, "Mitglieder hinzufügen", 420);
        Set<String> chosen = new LinkedHashSet<>();
        d.addRow(checkList(client, cs, chosen));
        d.buttons(new Ui.Btn("Abbrechen", false).onClick(e -> d.close()), new Ui.Btn("Hinzufügen", true).onClick(e -> {
            if (!chosen.isEmpty()) client.addGroupMembers(gid, new ArrayList<>(chosen));
            d.close();
        }));
        d.showDlg();
    }

    static void groupInfo(MainWindow win, Client client, String gid) {
        Store.Group g = client.db.group(gid);
        if (g == null) return;
        Ui.Dlg d = new Ui.Dlg(win, "Gruppeninfo", 420);
        JPanel top = Ui.row();
        ChatView.AvatarComp av = new ChatView.AvatarComp(56);
        av.set(gid, g.name, g.avatar);
        top.add(av);
        top.add(Box.createHorizontalStrut(14));
        JPanel t = new JPanel();
        t.setOpaque(false);
        t.setLayout(new BoxLayout(t, BoxLayout.Y_AXIS));
        t.add(Ui.label(g.name, 17, Theme.TEXT, true));
        t.add(Ui.label(g.members.size() + " Mitglieder", 13, Theme.DIM, false));
        top.add(t);
        d.addRow(top);
        d.gap(14);
        JPanel list = new JPanel();
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        for (String p : g.members) {
            JPanel r = Ui.row();
            r.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
            ChatView.AvatarComp a = new ChatView.AvatarComp(34);
            Store.Contact c = client.db.contact(p);
            a.set(p, client.nameOf(p), c == null ? null : c.avatar);
            r.add(a);
            r.add(Box.createHorizontalStrut(10));
            String n = p.equals(client.keys.pubHex()) ? "Du" : client.nameOf(p);
            r.add(Ui.label(n, 14, Theme.TEXT, false));
            r.add(Box.createHorizontalGlue());
            if (g.admins.contains(p) || p.equals(g.owner)) r.add(Ui.label("Admin", 12, Theme.ACCENT, true));
            list.add(r);
        }
        JScrollPane sp = new JScrollPane(list);
        Ui.styleScroll(sp);
        sp.setPreferredSize(new Dimension(380, Math.min(320, g.members.size() * 44 + 6)));
        d.addRow(sp);
        d.buttons(new Ui.Btn("Schließen", true).onClick(e -> d.close()));
        d.showDlg();
    }

    /** Liste von Kontakten zum Ankreuzen. */
    private static JComponent checkList(Client client, List<Store.Contact> cs, Set<String> chosen) {
        JPanel list = new JPanel();
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        cs.sort((a, b) -> client.nameOf(a.pub).compareToIgnoreCase(client.nameOf(b.pub)));
        for (Store.Contact c : cs) list.add(new CheckRow(client, c, chosen));
        JScrollPane sp = new JScrollPane(list);
        Ui.styleScroll(sp);
        sp.setPreferredSize(new Dimension(390, Math.min(300, cs.size() * 46 + 6)));
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        return sp;
    }

    static final class CheckRow extends JComponent {
        private final Store.Contact c;
        private final Client client;
        private final Set<String> chosen;
        private boolean hover;

        CheckRow(Client client, Store.Contact c, Set<String> chosen) {
            this.client = client;
            this.c = c;
            this.chosen = chosen;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setAlignmentX(LEFT_ALIGNMENT);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mouseClicked(MouseEvent e) { if (!chosen.remove(c.pub)) chosen.add(c.pub); repaint(); }
            });
        }

        @Override public Dimension getPreferredSize() { return new Dimension(380, 46); }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, 46); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.aa(g);
            if (hover) { g.setColor(Theme.HOVER); g.fill(Theme.round(0, 0, getWidth(), getHeight(), 8)); }
            Theme.paintAvatar(g, 6, 6, 34, c.avatar == null ? null : MsgView.imageOf(c.avatar), client.nameOf(c.pub), c.pub);
            java.awt.FontMetrics fm = g.getFontMetrics(Theme.font(14, false));
            Theme.drawLine(g, client.nameOf(c.pub), Theme.font(14, false), 52, (getHeight() + fm.getAscent() - fm.getDescent()) / 2f, getWidth() - 100, Theme.TEXT);
            boolean on = chosen.contains(c.pub);
            int cx = getWidth() - 34, cy = 12;
            if (on) {
                g.setColor(Theme.ACCENT);
                g.fillOval(cx, cy, 22, 22);
                g.translate(cx + 3, cy + 3);
                g.setColor(Color.WHITE);
                Theme.drawSym(g, Theme.Sym.CHECK, 16);
            } else {
                g.setColor(Theme.ACCENT);
                g.setStroke(new java.awt.BasicStroke(1.6f));
                g.drawOval(cx, cy, 22, 22);
            }
            g.dispose();
        }
    }

    // ================================================================== Kontaktinfo

    static void contactInfo(MainWindow win, Client client, String pub) {
        Store.Contact c = client.db.contact(pub);
        Ui.Dlg d = new Ui.Dlg(win, "Kontaktinfo", 440);
        JPanel top = Ui.row();
        ChatView.AvatarComp av = new ChatView.AvatarComp(64);
        av.set(pub, client.nameOf(pub), c == null ? null : c.avatar);
        top.add(av);
        top.add(Box.createHorizontalStrut(14));
        JPanel t = new JPanel();
        t.setOpaque(false);
        t.setLayout(new BoxLayout(t, BoxLayout.Y_AXIS));
        t.add(Ui.label(client.nameOf(pub), 17, Theme.TEXT, true));
        String sub = client.subName(pub);
        if (sub != null) t.add(Ui.label("nennt sich „" + sub + "“", 13, Theme.DIM, false));
        t.add(Ui.label(client.isOnline(pub) ? "online" : "Ende-zu-Ende-verschlüsselt", 13, client.isOnline(pub) ? Theme.ACCENT : Theme.DIM, false));
        top.add(t);
        d.addRow(top);
        d.gap(16);
        d.addRow(Ui.label("Kontakt-Code", 13, Theme.DIM, true));
        d.gap(4);
        String npub = de.nachrichten.crypto.Bech32.encode("npub", de.nachrichten.crypto.Event.unhex(pub));
        Ui.TLabel code = Ui.para(npub, 13, Theme.TEXT);
        code.setWrapWidth(400);
        d.addRow(code);
        d.buttons(new Ui.Btn("Code kopieren", false).onClick(e -> copy(npub)), new Ui.Btn("Schließen", true).onClick(e -> d.close()));
        d.showDlg();
    }

    static void copy(String s) { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null); }

    // ================================================================== Einstellungen

    static void settings(MainWindow win, Client client) {
        Store db = client.db;
        Ui.Dlg d = new Ui.Dlg(win, "Einstellungen", 500);
        d.addRow(Ui.label("Version " + Version.NAME + " (Windows)", 12.5f, Theme.DIM, false));
        d.gap(12);

        // ---- Profil
        d.addRow(Ui.label("Profil", 14, Theme.ACCENT, true));
        d.gap(8);
        JPanel prof = Ui.row();
        ChatView.AvatarComp av = new ChatView.AvatarComp(64);
        String[] avatarPath = {db.pref("avatar", null)};
        av.set(client.keys.pubHex(), client.myName(), avatarPath[0]);
        prof.add(av);
        prof.add(Box.createHorizontalStrut(12));
        JPanel pb = new JPanel();
        pb.setOpaque(false);
        pb.setLayout(new BoxLayout(pb, BoxLayout.Y_AXIS));
        Ui.Btn pick = new Ui.Btn("Bild wählen", false).onClick(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Profilbild wählen");
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Bilder", "jpg", "jpeg", "png", "gif", "bmp"));
            if (fc.showOpenDialog(d) != JFileChooser.APPROVE_OPTION) return;
            String p = squareAvatar(fc.getSelectedFile(), db.mediaDir);
            if (p == null) { Ui.info(d, "Profilbild", "Das Bild konnte nicht gelesen werden."); return; }
            avatarPath[0] = p;
            av.set(client.keys.pubHex(), client.myName(), p);
        });
        pb.add(pick);
        pb.add(Box.createVerticalStrut(6));
        pb.add(new Ui.Btn("Bild entfernen", false).onClick(e -> { avatarPath[0] = null; av.set(client.keys.pubHex(), client.myName(), null); }));
        prof.add(pb);
        d.addRow(prof);
        d.gap(10);
        Ui.EmojiText name = new Ui.EmojiText(false, 14, "Name");
        name.setText(db.pref("name", ""));
        d.addRow(Ui.fieldBox(name));
        d.gap(6);
        Ui.EmojiText pron = new Ui.EmojiText(false, 14, "Pronomen (optional)");
        pron.setText(db.pref("pronouns", ""));
        d.addRow(Ui.fieldBox(pron));
        d.gap(6);
        Ui.EmojiText bio = new Ui.EmojiText(true, 14, "Bio (optional)");
        bio.setText(db.pref("bio", ""));
        bio.onEnter = () -> bio.replaceSelection("\n");
        d.addRow(Ui.fieldBox(bio));
        d.gap(4);
        Ui.TLabel note = Ui.para("Nutzt du dasselbe Konto auch auf dem Handy, ersetzt das Speichern hier Profilbild, Bio und Pronomen bei deinen Kontakten. Ändere dein Profil dann am besten nur an einem Gerät.", 12, Theme.DIM);
        note.setWrapWidth(460);
        d.addRow(note);
        d.gap(6);
        d.addRow(Ui.row(new Ui.Btn("Profil speichern", true).onClick(e -> {
            String n = name.value().trim();
            if (n.length() > 40) n = n.substring(0, 40);
            db.setPref("name", n);
            db.setPref("pronouns", trim(pron.value().trim(), 40));
            db.setPref("bio", trim(bio.value().trim(), 500));
            if (avatarPath[0] == null) db.prefs.remove("avatar"); else db.setPref("avatar", avatarPath[0]);
            client.profileChanged();
            win.refreshAll();
            Ui.info(d, "Profil", "Gespeichert und an deine Kontakte geschickt.");
        })));
        d.gap(16);

        // ---- Eigener Code
        d.addRow(Ui.label("Dein Kontakt-Code", 14, Theme.ACCENT, true));
        d.gap(4);
        Ui.TLabel myCode = Ui.para(client.keys.npub(), 13, Theme.TEXT);
        myCode.setWrapWidth(460);
        d.addRow(myCode);
        d.gap(6);
        d.addRow(Ui.row(new Ui.Btn("Code kopieren", false).onClick(e -> copy(client.keys.contactCode(client.myName()))),
                Box.createHorizontalStrut(8),
                new Ui.Btn("Sicherungsschlüssel anzeigen", false).onClick(e -> showBackupKey(d, client))));
        d.gap(16);

        // ---- Privatsphäre
        d.addRow(Ui.label("Privatsphäre", 14, Theme.ACCENT, true));
        d.gap(4);
        d.addRow(toggleRow("Lesebestätigungen senden", db.prefBool("readReceipts", true), v -> db.setPref("readReceipts", v)));
        d.addRow(toggleRow("Anzeigen, wenn ich schreibe", db.prefBool("typing", true), v -> db.setPref("typing", v)));
        d.addRow(toggleRow("Online-Status zeigen", db.prefBool("showOnline", true), v -> db.setPref("showOnline", v)));
        d.gap(12);

        // ---- Fenster
        d.addRow(Ui.label("Fenster", 14, Theme.ACCENT, true));
        d.gap(4);
        d.addRow(toggleRow("Beim Schließen im Hintergrund weiterlaufen (Infobereich)", db.prefBool("tray", true), v -> db.setPref("tray", v)));
        d.addRow(toggleRow("Benachrichtigungen anzeigen", db.prefBool("notify", true), v -> db.setPref("notify", v)));
        d.gap(12);

        // ---- Verbindung
        d.addRow(Ui.label("Verbindung", 14, Theme.ACCENT, true));
        d.gap(4);
        d.addRow(Ui.label(client.connectedCount() + " von " + client.relays().size() + " Servern verbunden", 13, Theme.DIM, false));
        d.gap(16);

        // ---- Konto entfernen
        d.addRow(Ui.row(new Ui.Btn("Konto von diesem PC entfernen", false).onClick(e -> {
            if (Ui.confirm(d, "Konto entfernen", "Schlüssel, Kontakte und Nachrichten werden von diesem PC gelöscht. Ohne Sicherungsschlüssel ist das Konto danach verloren. Fortfahren?", "Entfernen")) {
                d.close();
                win.removeAccount();
            }
        })));
        d.buttons(new Ui.Btn("Schließen", true).onClick(e -> d.close()));

        // langes Fenster: in einen Bildlauf packen
        JPanel content = d.body;
        JScrollPane sp = new JScrollPane(content);
        Ui.styleScroll(sp);
        sp.setPreferredSize(new Dimension(480, 600));
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(sp);
        ((JPanel) d.getContentPane()).add(wrap, BorderLayout.CENTER);
        d.showDlg();
    }

    private static String trim(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }

    private static JComponent toggleRow(String text, boolean on, java.util.function.Consumer<Boolean> set) {
        JPanel r = new JPanel(new BorderLayout(10, 0));
        r.setOpaque(false);
        r.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        r.add(Ui.label(text, 14, Theme.TEXT, false), BorderLayout.CENTER);
        Ui.Toggle t = new Ui.Toggle(on);
        t.onChange(e -> set.accept(t.on));
        r.add(t, BorderLayout.EAST);
        r.setAlignmentX(Component.LEFT_ALIGNMENT);
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        return r;
    }

    private static void showBackupKey(Window owner, Client client) {
        if (!Ui.confirm(owner, "Sicherungsschlüssel", "Mit diesem Schlüssel kann jeder dein Konto übernehmen und alle deine Nachrichten lesen. Zeig ihn niemandem und gib ihn nirgends ein außer in dieser App. Trotzdem anzeigen?", "Anzeigen")) return;
        Ui.Dlg d = new Ui.Dlg(owner, "Sicherungsschlüssel", 460);
        Ui.TLabel k = Ui.para(client.keys.nsec(), 13.5f, Theme.TEXT);
        k.setWrapWidth(420);
        d.addRow(k);
        d.gap(6);
        Ui.TLabel n = Ui.para("Damit kannst du dein Konto auf einem anderen Gerät wiederherstellen (am Handy: „Mit Sicherungsschlüssel wiederherstellen“).", 12.5f, Theme.DIM);
        n.setWrapWidth(420);
        d.addRow(n);
        d.buttons(new Ui.Btn("Kopieren", false).onClick(e -> copy(client.keys.nsec())), new Ui.Btn("Schließen", true).onClick(e -> d.close()));
        d.showDlg();
    }

    /** Quadratischen Ausschnitt (Mitte) als kleines JPEG speichern. */
    static String squareAvatar(File src, File mediaDir) {
        try {
            BufferedImage img = ImageIO.read(src);
            if (img == null) return null;
            int s = Math.min(img.getWidth(), img.getHeight());
            BufferedImage out = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            Theme.aa(g);
            g.drawImage(img, 0, 0, 256, 256, (img.getWidth() - s) / 2, (img.getHeight() - s) / 2, (img.getWidth() - s) / 2 + s, (img.getHeight() - s) / 2 + s, null);
            g.dispose();
            byte[] jpg = ChatView.Attachment.jpeg(out, 0.85f);
            if (jpg.length > 55000) jpg = ChatView.Attachment.jpeg(out, 0.6f);
            File f = new File(mediaDir, "me_" + System.currentTimeMillis() + ".jpg");
            Files.write(f.toPath(), jpg);
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================== Bildansicht

    static void imageViewer(Window owner, String path, String name) {
        BufferedImage img = MsgView.imageOf(path);
        if (img == null) return;
        Ui.Dlg d = new Ui.Dlg(owner, name == null ? "Bild" : name, 300);
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        double s = Math.min(1.0, Math.min(scr.width * 0.75 / img.getWidth(), scr.height * 0.7 / img.getHeight()));
        int w = (int) (img.getWidth() * s), h = (int) (img.getHeight() * s);
        JComponent pic = new JComponent() {
            @Override public Dimension getPreferredSize() { return new Dimension(w, h); }
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                g.clip(Theme.round(0, 0, w, h, 10));
                g.drawImage(img, 0, 0, w, h, null);
                g.dispose();
            }
        };
        pic.setAlignmentX(Component.LEFT_ALIGNMENT);
        d.addRow(pic);
        d.buttons(new Ui.Btn("Speichern unter …", false).onClick(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File(name == null ? "Bild.jpg" : name));
            if (fc.showSaveDialog(d) == JFileChooser.APPROVE_OPTION) {
                try { Files.copy(new File(path).toPath(), fc.getSelectedFile().toPath(), StandardCopyOption.REPLACE_EXISTING); }
                catch (Exception ex) { Ui.info(d, "Fehler", "Das Bild konnte nicht gespeichert werden."); }
            }
        }), new Ui.Btn("Öffnen", false).onClick(e -> {
            try { Desktop.getDesktop().open(new File(path)); } catch (Exception ignored) { }
        }), new Ui.Btn("Schließen", true).onClick(e -> d.close()));
        d.showDlg();
    }

    private Dialogs() {}
}
