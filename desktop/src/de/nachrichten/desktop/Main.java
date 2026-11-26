package de.nachrichten.desktop;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Start der Windows-Version. */
public final class Main {

    private static FileLock lock;

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "lcd");
        System.setProperty("swing.aatext", "true");
        File dir = Store.defaultDir();
        dir.mkdirs();
        // nur eine Instanz gleichzeitig (sonst würden zwei Fenster dieselben Dateien schreiben)
        try {
            RandomAccessFile raf = new RandomAccessFile(new File(dir, ".lock"), "rw");
            lock = raf.getChannel().tryLock();
        } catch (Exception ignored) { }
        if (lock == null) {
            javax.swing.JOptionPane.showMessageDialog(null, "Nachrichten läuft bereits (siehe Symbol unten rechts in der Taskleiste).",
                    "Nachrichten", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        UIManager.put("ToolTip.background", Theme.CARD);
        UIManager.put("ToolTip.foreground", Theme.TEXT);
        UIManager.put("ToolTip.border", javax.swing.BorderFactory.createLineBorder(Theme.ACCENT));
        UIManager.put("PopupMenu.border", javax.swing.BorderFactory.createEmptyBorder());
        Store db = new Store(dir);
        Keys keys = new Keys(dir);
        SwingUtilities.invokeLater(() -> {
            MainWindow w = new MainWindow(db, keys);
            w.setVisible(true);
        });
    }

    private Main() {}
}
