package de.nachrichten.desktop;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextLayout;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/** Bausteine der Oberfläche (Text mit Emojis, Knöpfe, Schalter, Eingabefelder, Fenster). */
final class Ui {

    // ================================================================== Text

    /** Text mit Emoji-Unterstützung; einzeilig (mit „…“) oder umbrechend. */
    static class TLabel extends JComponent {
        private String text;
        private Font font;
        private Color color;
        private final boolean wrap;
        private int wrapWidth = 360;

        TLabel(String text, float size, Color color, boolean bold, boolean wrap) {
            this.text = text == null ? "" : text;
            this.font = Theme.font(size, bold);
            this.color = color;
            this.wrap = wrap;
            setOpaque(false);
            setAlignmentX(LEFT_ALIGNMENT);
        }

        void setText(String t) { text = t == null ? "" : t; revalidate(); repaint(); }
        String getText() { return text; }
        void setColor(Color c) { color = c; repaint(); }
        void setWrapWidth(int w) { wrapWidth = w; revalidate(); }

        @Override public Dimension getPreferredSize() {
            Graphics2D g = scratch();
            java.awt.FontMetrics fm = g.getFontMetrics(font);
            if (!wrap) return new Dimension((int) Math.ceil(Theme.lineWidth(g, text, font)) + 2, fm.getHeight() + 2);
            int w = getWidth() > 20 ? getWidth() : wrapWidth;
            List<TextLayout> ls = Theme.wrap(text, font, w, g.getFontRenderContext());
            float h = 0;
            for (TextLayout l : ls) h += l.getAscent() + l.getDescent() + l.getLeading() + 1;
            return new Dimension(wrapWidth, (int) Math.ceil(h) + 2);
        }

        @Override public Dimension getMaximumSize() {
            Dimension p = getPreferredSize();
            return new Dimension(wrap ? Integer.MAX_VALUE : p.width, p.height);
        }

        @Override public Dimension getMinimumSize() { return new Dimension(10, getPreferredSize().height); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.aa(g);
            if (!wrap) {
                java.awt.FontMetrics fm = g.getFontMetrics(font);
                Theme.drawLine(g, text, font, 0, 1 + fm.getAscent(), getWidth(), color);
            } else {
                List<TextLayout> ls = Theme.wrap(text, font, Math.max(20, getWidth()), g.getFontRenderContext());
                float y = 1;
                g.setColor(color);
                for (TextLayout l : ls) { y += l.getAscent(); l.draw(g, 0, y); y += l.getDescent() + l.getLeading() + 1; }
                // Höhe nachziehen, wenn sich die Breite geändert hat
                int need = (int) Math.ceil(y) + 2;
                if (Math.abs(need - getHeight()) > 1) SwingUtilities.invokeLater(() -> { wrapWidth = getWidth(); revalidate(); });
            }
            g.dispose();
        }
    }

    private static Graphics2D scratchG;

    static synchronized Graphics2D scratch() {
        if (scratchG == null) {
            scratchG = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics();
            Theme.aa(scratchG);
        }
        return scratchG;
    }

    static TLabel label(String t, float size, Color c, boolean bold) { return new TLabel(t, size, c, bold, false); }
    static TLabel para(String t, float size, Color c) { return new TLabel(t, size, c, false, true); }

    // ================================================================== Knöpfe

    /** Abgerundeter Knopf: gefüllt (Akzent) oder mit grünem Rand; federt beim Drücken (wie in der Android-App). */
    static class Btn extends JComponent {
        private String text;
        private final boolean primary;
        private Color fg;
        private boolean hover, down;
        private float scale = 1f;
        private Timer anim;
        private ActionListener action;
        private boolean enabledState = true;

        Btn(String text, boolean primary) {
            this.text = text;
            this.primary = primary;
            this.fg = primary ? Theme.ON_ACCENT : Theme.TEXT;
            setAlignmentX(LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; down = false; animateTo(1f); }
                @Override public void mousePressed(MouseEvent e) { if (!enabledState) return; down = true; animateTo(0.94f); }
                @Override public void mouseReleased(MouseEvent e) {
                    boolean inside = contains(e.getPoint());
                    boolean was = down;
                    down = false;
                    animateTo(1f);
                    if (was && inside && enabledState && action != null && SwingUtilities.isLeftMouseButton(e)) action.actionPerformed(null);
                }
            });
            getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "press");
            getActionMap().put("press", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { if (enabledState && action != null) action.actionPerformed(null); } });
        }

        Btn onClick(ActionListener a) { action = a; return this; }
        void setText(String t) { text = t; revalidate(); repaint(); }
        void setActive(boolean e) { enabledState = e; setCursor(Cursor.getPredefinedCursor(e ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR)); repaint(); }
        void setFg(Color c) { fg = c; repaint(); }

        private void animateTo(float target) {
            if (anim != null) anim.stop();
            final float start = scale;
            final long t0 = System.currentTimeMillis();
            final int dur = target < 1f ? 90 : 240;
            anim = new Timer(12, e -> {
                float p = Math.min(1f, (System.currentTimeMillis() - t0) / (float) dur);
                float k = target < 1f ? (float) (1 - Math.pow(1 - p, 2)) : overshoot(p);
                scale = start + (target - start) * k;
                repaint();
                if (p >= 1f) { scale = target; ((Timer) e.getSource()).stop(); }
            });
            anim.start();
        }

        private static float overshoot(float t) { float s = 2.6f; t -= 1; return t * t * ((s + 1) * t + s) + 1; }

        @Override public Dimension getPreferredSize() {
            float w = Theme.lineWidth(scratch(), text, Theme.font(14, true));
            return new Dimension((int) w + 36, 38);
        }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.aa(g);
            float w = getWidth(), h = getHeight();
            g.translate(w / 2, h / 2);
            g.scale(scale, scale);
            g.translate(-w / 2, -h / 2);
            float alpha = enabledState ? 1f : 0.45f;
            g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha));
            java.awt.geom.RoundRectangle2D r = Theme.round(1, 1, w - 2, h - 2, 10);
            if (primary) {
                g.setColor(hover ? Theme.ACCENT.brighter() : Theme.ACCENT);
                g.fill(r);
            } else {
                if (hover) { g.setColor(Theme.alpha(Theme.ACCENT, 28)); g.fill(r); }
                g.setColor(Theme.ACCENT);
                g.setStroke(new BasicStroke(1.6f));
                g.draw(r);
            }
            Font f = Theme.font(14, true);
            float tw = Theme.lineWidth(g, text, f);
            java.awt.FontMetrics fm = g.getFontMetrics(f);
            Theme.drawLine(g, text, f, (w - tw) / 2f, (h + fm.getAscent() - fm.getDescent()) / 2f, 0, fg);
            g.dispose();
        }
    }

    /** Runder Symbol-Knopf (Hover-Kreis, federt beim Drücken). */
    static class IconBtn extends JComponent {
        private Theme.Sym sym;
        private Color color;
        private final boolean filled;
        private boolean hover;
        private float scale = 1f;
        private ActionListener action;
        private final int size;

        IconBtn(Theme.Sym sym, int size, Color color, boolean filled, String tip) {
            this.sym = sym;
            this.size = size;
            this.color = color;
            this.filled = filled;
            setToolTipText(tip);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; scale = 1f; repaint(); }
                @Override public void mousePressed(MouseEvent e) { scale = 0.88f; repaint(); }
                @Override public void mouseReleased(MouseEvent e) {
                    scale = 1f;
                    repaint();
                    if (contains(e.getPoint()) && action != null && SwingUtilities.isLeftMouseButton(e)) action.actionPerformed(null);
                }
            });
        }

        IconBtn onClick(ActionListener a) { action = a; return this; }
        void setSym(Theme.Sym s) { sym = s; repaint(); }

        @Override public Dimension getPreferredSize() { return new Dimension(size, size); }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.aa(g);
            float w = getWidth(), h = getHeight();
            g.translate(w / 2, h / 2);
            g.scale(scale, scale);
            g.translate(-w / 2, -h / 2);
            if (filled) {
                g.setColor(hover ? Theme.ACCENT.brighter() : Theme.ACCENT);
                g.fillOval(0, 0, (int) w, (int) h);
            } else if (hover) {
                g.setColor(Theme.alpha(Color.WHITE, 22));
                g.fillOval(0, 0, (int) w, (int) h);
            }
            float s = Math.min(w, h) * 0.56f;
            g.translate((w - s) / 2f, (h - s) / 2f);
            g.setColor(filled ? Theme.ON_ACCENT : color);
            Theme.drawSym(g, sym, s);
            g.dispose();
        }
    }

    /** Ein/Aus-Schalter wie in der Android-App. */
    static class Toggle extends JComponent {
        boolean on;
        private float pos;
        private ActionListener action;

        Toggle(boolean on) {
            this.on = on;
            this.pos = on ? 1 : 0;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { setOn(!Toggle.this.on); if (action != null) action.actionPerformed(null); }
            });
        }

        Toggle onChange(ActionListener a) { action = a; return this; }

        void setOn(boolean v) {
            on = v;
            Timer t = new Timer(12, null);
            long t0 = System.currentTimeMillis();
            float from = pos;
            t.addActionListener(e -> {
                float p = Math.min(1f, (System.currentTimeMillis() - t0) / 160f);
                pos = from + ((on ? 1 : 0) - from) * (float) (1 - Math.pow(1 - p, 3));
                repaint();
                if (p >= 1f) t.stop();
            });
            t.start();
        }

        @Override public Dimension getPreferredSize() { return new Dimension(46, 26); }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.aa(g);
            int w = getWidth(), h = getHeight();
            Color track = blend(Theme.LINE, Theme.ACCENT, pos);
            g.setColor(track);
            g.fill(Theme.round(1, 3, w - 2, h - 6, (h - 6) / 2f));
            float d = h - 8;
            float x = 4 + (w - 8 - d) * pos;
            g.setColor(Color.WHITE);
            g.fillOval((int) x, 4, (int) d, (int) d);
            g.dispose();
        }

        private static Color blend(Color a, Color b, float t) {
            return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                    (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
        }
    }

    // ================================================================== Eingabe mit Emojis

    /**
     * Eingabefeld, das Emojis darstellen kann (Windows: Win + . öffnet die Emoji-Auswahl).
     * Einzeilig: Enter löst onEnter aus. Mehrzeilig: Enter sendet, Umschalt + Enter = neue Zeile.
     */
    static class EmojiText extends JTextPane {
        private final boolean multi;
        private final String placeholder;
        private boolean restyling;
        Runnable onEnter;
        Runnable onChange;

        EmojiText(boolean multi, float size, String placeholder) {
            this.multi = multi;
            this.placeholder = placeholder;
            setFont(Theme.font(size, false));
            setForeground(Theme.TEXT);
            setCaretColor(Theme.TEXT);
            setSelectionColor(Theme.alpha(Theme.ACCENT, 120));
            setSelectedTextColor(Color.WHITE);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            ((AbstractDocument) getDocument()).setDocumentFilter(new DocumentFilter() {
                @Override public void insertString(FilterBypass fb, int off, String s, AttributeSet a) throws BadLocationException {
                    super.insertString(fb, off, clean(s), a);
                }
                @Override public void replace(FilterBypass fb, int off, int len, String s, AttributeSet a) throws BadLocationException {
                    super.replace(fb, off, len, s == null ? null : clean(s), a);
                }
            });
            getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { changed(); }
                @Override public void removeUpdate(DocumentEvent e) { changed(); }
                @Override public void changedUpdate(DocumentEvent e) { }
            });
            getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "enter");
            getActionMap().put("enter", new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) { if (onEnter != null) onEnter.run(); }
            });
            if (multi) {
                getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK), "newline");
                getActionMap().put("newline", new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) { replaceSelection("\n"); }
                });
            }
        }

        private String clean(String s) {
            if (s == null) return "";
            s = s.replace("\r\n", "\n").replace('\r', '\n');
            return multi ? s : s.replace('\n', ' ');
        }

        private void changed() {
            if (onChange != null) SwingUtilities.invokeLater(onChange);
            if (!restyling) SwingUtilities.invokeLater(this::restyle);
        }

        /** Emojis bekommen die Emoji-Schrift, alles andere die normale Schrift. */
        private void restyle() {
            if (Theme.EMOJI_FAMILY == null) return;
            restyling = true;
            try {
                StyledDocument d = getStyledDocument();
                String s = d.getText(0, d.getLength());
                SimpleAttributeSet normal = new SimpleAttributeSet();
                StyleConstants.setFontFamily(normal, Theme.TEXT_FAMILY);
                SimpleAttributeSet emo = new SimpleAttributeSet();
                StyleConstants.setFontFamily(emo, Theme.EMOJI_FAMILY);
                d.setCharacterAttributes(0, s.length(), normal, false);
                Font base = getFont();
                int i = 0;
                while (i < s.length()) {
                    int cp = s.codePointAt(i);
                    int len = Character.charCount(cp);
                    if (Theme.isEmojiCp(cp) || (cp > 0x2000 && !base.canDisplay(cp))) d.setCharacterAttributes(i, len, emo, false);
                    i += len;
                }
            } catch (Exception ignored) {
            } finally {
                restyling = false;
            }
        }

        String value() { return getText().replace("\r", ""); }

        @Override public boolean getScrollableTracksViewportWidth() { return true; }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.aa(g);
            g.dispose();
            super.paintComponent(g0);
            if (getDocument().getLength() == 0 && placeholder != null) {
                Graphics2D p = (Graphics2D) g0.create();
                Theme.aa(p);
                Insets in = getInsets();
                java.awt.FontMetrics fm = p.getFontMetrics(getFont());
                Theme.drawLine(p, placeholder, getFont(), in.left + 2, in.top + fm.getAscent(), getWidth() - in.left - in.right, Theme.DIM);
                p.dispose();
            }
        }
    }

    /** Eingabefeld in einem abgerundeten Kasten. */
    static JComponent fieldBox(JComponent inner) {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.aa(g);
                g.setColor(Theme.FIELD);
                g.fill(Theme.round(0, 0, getWidth(), getHeight(), 10));
                boolean focus = inner.isFocusOwner() || (inner instanceof JScrollPane && ((JScrollPane) inner).getViewport().getView().isFocusOwner());
                g.setColor(focus ? Theme.ACCENT : Theme.LINE);
                g.setStroke(new BasicStroke(focus ? 1.6f : 1f));
                g.draw(Theme.round(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 10));
                g.dispose();
            }
        };
        p.setOpaque(false);
        p.add(inner);
        Component focusTarget = inner instanceof JScrollPane ? ((JScrollPane) inner).getViewport().getView() : inner;
        focusTarget.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) { p.repaint(); }
            @Override public void focusLost(java.awt.event.FocusEvent e) { p.repaint(); }
        });
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    // ================================================================== Bildlaufleisten

    static void styleScroll(JScrollPane sp) {
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setViewportBorder(null);
        sp.getViewport().setOpaque(false);
        sp.setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        sp.getVerticalScrollBar().setUI(new ThinBar());
        sp.getHorizontalScrollBar().setUI(new ThinBar());
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(10, 10));
        sp.getVerticalScrollBar().setOpaque(false);
    }

    static final class ThinBar extends BasicScrollBarUI {
        @Override protected void configureScrollBarColors() { thumbColor = new Color(0x3A4044); trackColor = new Color(0, 0, 0, 0); }
        @Override protected javax.swing.JButton createDecreaseButton(int o) { return zero(); }
        @Override protected javax.swing.JButton createIncreaseButton(int o) { return zero(); }
        private javax.swing.JButton zero() { javax.swing.JButton b = new javax.swing.JButton(); b.setPreferredSize(new Dimension(0, 0)); return b; }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { }
        @Override protected void paintThumb(Graphics g0, JComponent c, Rectangle r) {
            if (r.isEmpty()) return;
            Graphics2D g = (Graphics2D) g0.create();
            Theme.aa(g);
            g.setColor(isThumbRollover() ? new Color(0x50575C) : thumbColor);
            g.fill(Theme.round(r.x + 2, r.y + 2, r.width - 4, r.height - 4, (r.width - 4) / 2f));
            g.dispose();
        }
    }

    // ================================================================== Fenster (Dialoge)

    /**
     * Fenster im App-Stil: abgerundet, grüner Rand, eigener Titel; ploppt weich auf und blendet weich aus.
     * Schließen: X, Esc oder Abbrechen.
     */
    static class Dlg extends JDialog {
        final JPanel body;
        private boolean closing;

        Dlg(Window owner, String title, int width) {
            super(owner, ModalityType.APPLICATION_MODAL);
            setUndecorated(true);
            setBackground(new Color(0, 0, 0, 0));
            JPanel card = new JPanel(new BorderLayout()) {
                @Override protected void paintComponent(Graphics g0) {
                    Graphics2D g = (Graphics2D) g0.create();
                    Theme.aa(g);
                    g.setColor(Theme.CARD);
                    g.fill(Theme.round(0, 0, getWidth(), getHeight(), 16));
                    g.setColor(Theme.ACCENT);
                    g.setStroke(new BasicStroke(1.6f));
                    g.draw(Theme.round(0.8f, 0.8f, getWidth() - 1.6f, getHeight() - 1.6f, 16));
                    g.dispose();
                }
            };
            card.setOpaque(false);
            card.setBorder(BorderFactory.createEmptyBorder(16, 20, 18, 20));
            JPanel head = new JPanel(new BorderLayout());
            head.setOpaque(false);
            head.add(label(title, 18, Theme.TEXT, true), BorderLayout.CENTER);
            IconBtn x = new IconBtn(Theme.Sym.CLOSE, 30, Theme.DIM, false, "Schließen").onClick(e -> close());
            head.add(x, BorderLayout.EAST);
            head.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
            card.add(head, BorderLayout.NORTH);
            body = new JPanel();
            body.setOpaque(false);
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            card.add(body, BorderLayout.CENTER);
            setContentPane(card);
            // Fenster am Titel verschieben
            MouseAdapter drag = new MouseAdapter() {
                Point start;
                @Override public void mousePressed(MouseEvent e) { start = e.getPoint(); }
                @Override public void mouseDragged(MouseEvent e) {
                    Point p = getLocation();
                    setLocation(p.x + e.getX() - start.x, p.y + e.getY() - start.y);
                }
            };
            head.addMouseListener(drag);
            head.addMouseMotionListener(drag);
            getRootPane().registerKeyboardAction(e -> close(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
            setMinimumSize(new Dimension(width, 80));
            this.width = width;
        }

        private final int width;

        void addRow(Component c) {
            if (c instanceof JComponent) ((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(c);
        }

        void gap(int h) { body.add(Box.createVerticalStrut(h)); }

        /** Knopfzeile rechtsbündig. */
        void buttons(Component... bs) {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            p.setOpaque(false);
            for (Component b : bs) p.add(b);
            p.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(Box.createVerticalStrut(14));
            body.add(p);
        }

        void showDlg() {
            pack();
            setSize(Math.max(width, getWidth()), getHeight());
            setLocationRelativeTo(getOwner());
            // weich aufploppen (Deckkraft; der Rahmen ist durchsichtig)
            try { setOpacity(0f); } catch (Exception ignored) { }
            Timer t = new Timer(12, null);
            long t0 = System.currentTimeMillis();
            t.addActionListener(e -> {
                float p = Math.min(1f, (System.currentTimeMillis() - t0) / 170f);
                try { setOpacity(p); } catch (Exception ignored) { }
                if (p >= 1f) t.stop();
            });
            t.start();
            setVisible(true);
        }

        void close() {
            if (closing) return;
            closing = true;
            Timer t = new Timer(12, null);
            long t0 = System.currentTimeMillis();
            t.addActionListener(e -> {
                float p = Math.min(1f, (System.currentTimeMillis() - t0) / 130f);
                try { setOpacity(1f - p); } catch (Exception ignored) { }
                if (p >= 1f) { t.stop(); dispose(); }
            });
            t.start();
        }
    }

    /** Einfache Ja/Nein-Abfrage. */
    static boolean confirm(Window owner, String title, String text, String yes) {
        boolean[] r = {false};
        Dlg d = new Dlg(owner, title, 420);
        TLabel l = para(text, 14, Theme.DIM);
        l.setWrapWidth(380);
        d.addRow(l);
        Btn no = new Btn("Abbrechen", false).onClick(e -> d.close());
        Btn ok = new Btn(yes, true).onClick(e -> { r[0] = true; d.close(); });
        d.buttons(no, ok);
        d.showDlg();
        return r[0];
    }

    static void info(Window owner, String title, String text) {
        Dlg d = new Dlg(owner, title, 420);
        TLabel l = para(text, 14, Theme.DIM);
        l.setWrapWidth(380);
        d.addRow(l);
        d.buttons(new Btn("OK", true).onClick(e -> d.close()));
        d.showDlg();
    }

    /** Text abfragen. null = abgebrochen. */
    static String ask(Window owner, String title, String hint, String initial, String okText) {
        String[] r = {null};
        Dlg d = new Dlg(owner, title, 420);
        EmojiText f = new EmojiText(false, 15, hint);
        if (initial != null) f.setText(initial);
        d.addRow(fieldBox(f));
        Runnable ok = () -> { r[0] = f.value().trim(); d.close(); };
        f.onEnter = ok;
        d.buttons(new Btn("Abbrechen", false).onClick(e -> d.close()), new Btn(okText, true).onClick(e -> ok.run()));
        SwingUtilities.invokeLater(f::requestFocusInWindow);
        d.showDlg();
        return r[0];
    }

    static JPanel row(Component... cs) {
        JPanel p = new JPanel() {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
            @Override public Dimension getMinimumSize() { return new Dimension(10, getPreferredSize().height); }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setOpaque(false);
        for (Component c : cs) p.add(c);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    static Icon emptyIcon(int w, int h) {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) { }
            @Override public int getIconWidth() { return w; }
            @Override public int getIconHeight() { return h; }
        };
    }

    private Ui() {}
}
