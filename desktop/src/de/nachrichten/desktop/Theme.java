package de.nachrichten.desktop;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.Icon;

/** Farben, Schriften, gezeichnete Symbole und Text mit Emojis – passend zur dunklen Android-App. */
final class Theme {

    // Farben wie Pal (dunkel) in der Android-App
    static final Color BG = new Color(0x0F1112), CARD = new Color(0x1A1D1F), FIELD = new Color(0x23272A),
            LINE = new Color(0x2D3235), TEXT = new Color(0xEBEDEE), DIM = new Color(0x8B9296),
            ACCENT = new Color(0x3FA58C), ON_ACCENT = Color.WHITE, RED = new Color(0xE36B5F),
            BUBBLE_IN = new Color(0x1E2224), BUBBLE_OUT = new Color(0x1F4A3F), ON_OUT = new Color(0xE9F5F1),
            READ_BLUE = new Color(0x5BC0F8), SELECT = new Color(0x22282B), HOVER = new Color(0x1C2022);

    static final int[] AVATAR_COLORS = {0x4F7CAC, 0x3FA58C, 0xB07A3B, 0x8A5DA8, 0xA8565D, 0x5D8A4F, 0x4F9AA8, 0x9A7B4F};

    static Color avatarColor(String pub) { return new Color(AVATAR_COLORS[Math.abs(pub.hashCode() % AVATAR_COLORS.length)]); }

    static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), a); }

    // ------------------------------------------------------------------ Schriften

    static final String TEXT_FAMILY, EMOJI_FAMILY;

    static {
        Set<String> fam = new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        String t = "Dialog";
        for (String f : new String[]{"Segoe UI", "Noto Sans", "DejaVu Sans", "Arial"}) if (fam.contains(f)) { t = f; break; }
        String e = null;
        for (String f : new String[]{"Segoe UI Emoji", "Noto Color Emoji", "Noto Emoji", "Apple Color Emoji", "Segoe UI Symbol", "Symbola"})
            if (fam.contains(f)) { e = f; break; }
        TEXT_FAMILY = t;
        EMOJI_FAMILY = e;
    }

    static Font font(float size, boolean bold) { return new Font(TEXT_FAMILY, bold ? Font.BOLD : Font.PLAIN, 1).deriveFont(size); }

    static Font emojiFont(float size) { return new Font(EMOJI_FAMILY != null ? EMOJI_FAMILY : "Dialog", Font.PLAIN, 1).deriveFont(size); }

    static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    // ------------------------------------------------------------------ Text mit Emojis

    /** Text als AttributedString: jedes Zeichen bekommt eine Schrift, die es auch darstellen kann (Emojis aus der Emoji-Schrift). */
    static AttributedString styled(String s, Font base) {
        if (s.isEmpty()) s = " ";
        AttributedString as = new AttributedString(s);
        as.addAttribute(TextAttribute.FONT, base);
        Font emo = EMOJI_FAMILY != null ? new Font(EMOJI_FAMILY, Font.PLAIN, 1).deriveFont(base.getSize2D()) : null;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int len = Character.charCount(cp);
            if (emo != null && needsEmojiFont(cp, base)) {
                int start = i;
                i += len;
                // zusammengesetzte Emojis (ZWJ, Variations-Selektor, Hautfarbe, Flaggen) zusammenhalten
                while (i < s.length()) {
                    int c2 = s.codePointAt(i);
                    if (c2 == 0x200D || c2 == 0xFE0F || (c2 >= 0x1F3FB && c2 <= 0x1F3FF) || needsEmojiFont(c2, base) || (c2 >= 0x1F1E6 && c2 <= 0x1F1FF)) {
                        i += Character.charCount(c2);
                    } else break;
                }
                as.addAttribute(TextAttribute.FONT, emo, start, i);
                continue;
            }
            i += len;
        }
        return as;
    }

    static boolean isEmojiCp(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF) || (cp >= 0x2B00 && cp <= 0x2BFF)
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF) || cp == 0x200D || cp == 0xFE0F || cp == 0x2764 || cp == 0x203C || cp == 0x2049;
    }

    private static boolean needsEmojiFont(int cp, Font base) {
        if (cp == 0x200D || cp == 0xFE0F) return true;
        if (isEmojiCp(cp)) return true;
        return cp > 0x2000 && !base.canDisplay(cp);
    }

    /** Zeilen eines Textes für eine maximale Breite (mit Zeilenumbrüchen aus dem Text). */
    static List<TextLayout> wrap(String text, Font font, float maxW, FontRenderContext frc) {
        List<TextLayout> out = new ArrayList<>();
        String[] paras = text.split("\n", -1);
        for (String p : paras) {
            if (p.isEmpty()) { out.add(new TextLayout(styled(" ", font).getIterator(), frc)); continue; }
            AttributedCharacterIterator it = styled(p, font).getIterator();
            LineBreakMeasurer m = new LineBreakMeasurer(it, frc);
            while (m.getPosition() < it.getEndIndex()) {
                TextLayout tl = m.nextLayout(Math.max(10f, maxW));
                if (tl == null) break;
                out.add(tl);
            }
        }
        return out;
    }

    /** Einzeiliger Text mit Emojis, notfalls mit „…“ gekürzt. Gibt die Breite zurück. */
    static float drawLine(Graphics2D g, String s, Font font, float x, float baseline, float maxW, Color c) {
        if (s == null) s = "";
        FontRenderContext frc = g.getFontRenderContext();
        String t = s;
        TextLayout tl = new TextLayout(styled(t, font).getIterator(), frc);
        if (maxW > 0 && tl.getAdvance() > maxW) {
            int lo = 0, hi = s.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                String cand = safeCut(s, mid) + "…";
                if (new TextLayout(styled(cand, font).getIterator(), frc).getAdvance() <= maxW) lo = mid; else hi = mid - 1;
            }
            t = safeCut(s, lo) + "…";
            tl = new TextLayout(styled(t, font).getIterator(), frc);
        }
        g.setColor(c);
        tl.draw(g, x, baseline);
        return tl.getAdvance();
    }

    static float lineWidth(Graphics2D g, String s, Font font) {
        if (s == null || s.isEmpty()) return 0;
        return new TextLayout(styled(s, font).getIterator(), g.getFontRenderContext()).getAdvance();
    }

    private static String safeCut(String s, int n) {
        if (n <= 0) return "";
        if (n >= s.length()) return s;
        if (Character.isHighSurrogate(s.charAt(n - 1))) n--;
        return s.substring(0, n);
    }

    // ------------------------------------------------------------------ Formen

    static RoundRectangle2D.Float round(float x, float y, float w, float h, float r) {
        return new RoundRectangle2D.Float(x, y, w, h, r * 2, r * 2);
    }

    /** Avatar: Bild rund zugeschnitten oder farbiger Kreis mit Anfangsbuchstaben. */
    static void paintAvatar(Graphics2D g, int x, int y, int size, java.awt.image.BufferedImage img, String name, String seed) {
        aa(g);
        if (img != null) {
            java.awt.Shape old = g.getClip();
            g.clip(new java.awt.geom.Ellipse2D.Float(x, y, size, size));
            int iw = img.getWidth(), ih = img.getHeight(), s = Math.min(iw, ih);
            g.drawImage(img, x, y, x + size, y + size, (iw - s) / 2, (ih - s) / 2, (iw - s) / 2 + s, (ih - s) / 2 + s, null);
            g.setClip(old);
            return;
        }
        g.setColor(avatarColor(seed == null ? "" : seed));
        g.fillOval(x, y, size, size);
        String n = name == null ? "" : name.trim();
        String initial = n.isEmpty() ? "?" : new String(Character.toChars(n.codePointAt(0))).toUpperCase();
        Font f = font(size * 0.42f, true);
        float w = lineWidth(g, initial, f);
        FontMetrics fm = g.getFontMetrics(f);
        drawLine(g, initial, f, x + (size - w) / 2f, y + (size + fm.getAscent() - fm.getDescent()) / 2f, 0, Color.WHITE);
    }

    // ------------------------------------------------------------------ gezeichnete Symbole

    enum Sym { PLUS, SEND, ATTACH, MORE, GEAR, GROUP, CLOSE, PLAY, FILE, SMILE, BACK, CHECK, COPY, PERSON, LOCK, REPLY, DOWNLOAD }

    static Icon icon(Sym s, int size, Color c) {
        return new Icon() {
            @Override public void paintIcon(Component comp, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                aa(g);
                g.translate(x, y);
                g.setColor(c);
                drawSym(g, s, size);
                g.dispose();
            }
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
        };
    }

    static void drawSym(Graphics2D g, Sym s, float n) {
        float w = Math.max(1.6f, n / 12f);
        g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float c = n / 2f;
        switch (s) {
            case PLUS:
                g.draw(new java.awt.geom.Line2D.Float(c, n * 0.2f, c, n * 0.8f));
                g.draw(new java.awt.geom.Line2D.Float(n * 0.2f, c, n * 0.8f, c));
                break;
            case SEND: {
                Path2D p = new Path2D.Float();
                p.moveTo(n * 0.16, n * 0.18); p.lineTo(n * 0.86, c); p.lineTo(n * 0.16, n * 0.82); p.lineTo(n * 0.28, c); p.closePath();
                g.fill(p);
                break;
            }
            case ATTACH: {
                Path2D p = new Path2D.Float();
                p.moveTo(n * 0.62, n * 0.30);
                p.lineTo(n * 0.36, n * 0.58);
                p.curveTo(n * 0.28, n * 0.66, n * 0.36, n * 0.78, n * 0.46, n * 0.70);
                p.lineTo(n * 0.74, n * 0.40);
                p.curveTo(n * 0.88, n * 0.26, n * 0.70, n * 0.06, n * 0.56, n * 0.20);
                p.lineTo(n * 0.26, n * 0.52);
                p.curveTo(n * 0.06, n * 0.74, n * 0.32, n * 1.00, n * 0.52, n * 0.80);
                p.lineTo(n * 0.78, n * 0.54);
                g.draw(p);
                break;
            }
            case MORE:
                for (int i = 0; i < 3; i++) g.fill(new java.awt.geom.Ellipse2D.Float(c - n * 0.07f, n * (0.22f + i * 0.25f) - n * 0.07f, n * 0.14f, n * 0.14f));
                break;
            case GEAR: {
                g.draw(new java.awt.geom.Ellipse2D.Float(c - n * 0.14f, c - n * 0.14f, n * 0.28f, n * 0.28f));
                Path2D p = new Path2D.Float();
                int teeth = 8;
                for (int i = 0; i < teeth * 2; i++) {
                    double a = Math.PI * i / teeth;
                    double r = (i % 2 == 0) ? n * 0.40 : n * 0.31;
                    double x = c + Math.cos(a) * r, y = c + Math.sin(a) * r;
                    if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                }
                p.closePath();
                g.draw(p);
                break;
            }
            case GROUP:
                g.draw(new java.awt.geom.Ellipse2D.Float(n * 0.22f, n * 0.18f, n * 0.26f, n * 0.26f));
                g.draw(new java.awt.geom.Arc2D.Float(n * 0.10f, n * 0.50f, n * 0.50f, n * 0.44f, 0, 180, java.awt.geom.Arc2D.OPEN));
                g.draw(new java.awt.geom.Ellipse2D.Float(n * 0.56f, n * 0.24f, n * 0.22f, n * 0.22f));
                g.draw(new java.awt.geom.Arc2D.Float(n * 0.52f, n * 0.54f, n * 0.38f, n * 0.36f, 0, 180, java.awt.geom.Arc2D.OPEN));
                break;
            case PERSON:
                g.draw(new java.awt.geom.Ellipse2D.Float(c - n * 0.15f, n * 0.16f, n * 0.30f, n * 0.30f));
                g.draw(new java.awt.geom.Arc2D.Float(n * 0.20f, n * 0.52f, n * 0.60f, n * 0.48f, 0, 180, java.awt.geom.Arc2D.OPEN));
                break;
            case CLOSE:
                g.draw(new java.awt.geom.Line2D.Float(n * 0.25f, n * 0.25f, n * 0.75f, n * 0.75f));
                g.draw(new java.awt.geom.Line2D.Float(n * 0.75f, n * 0.25f, n * 0.25f, n * 0.75f));
                break;
            case PLAY: {
                Path2D p = new Path2D.Float();
                p.moveTo(n * 0.32, n * 0.20); p.lineTo(n * 0.82, c); p.lineTo(n * 0.32, n * 0.80); p.closePath();
                g.fill(p);
                break;
            }
            case FILE: {
                Path2D p = new Path2D.Float();
                p.moveTo(n * 0.26, n * 0.12); p.lineTo(n * 0.58, n * 0.12); p.lineTo(n * 0.76, n * 0.30); p.lineTo(n * 0.76, n * 0.88);
                p.lineTo(n * 0.26, n * 0.88); p.closePath();
                g.draw(p);
                g.draw(new java.awt.geom.Line2D.Float(n * 0.58f, n * 0.12f, n * 0.58f, n * 0.30f));
                g.draw(new java.awt.geom.Line2D.Float(n * 0.58f, n * 0.30f, n * 0.76f, n * 0.30f));
                break;
            }
            case SMILE:
                g.draw(new java.awt.geom.Ellipse2D.Float(n * 0.14f, n * 0.14f, n * 0.72f, n * 0.72f));
                g.fill(new java.awt.geom.Ellipse2D.Float(n * 0.34f, n * 0.36f, n * 0.08f, n * 0.10f));
                g.fill(new java.awt.geom.Ellipse2D.Float(n * 0.58f, n * 0.36f, n * 0.08f, n * 0.10f));
                g.draw(new java.awt.geom.Arc2D.Float(n * 0.30f, n * 0.36f, n * 0.40f, n * 0.32f, 200, 140, java.awt.geom.Arc2D.OPEN));
                break;
            case BACK:
                g.draw(new java.awt.geom.Line2D.Float(n * 0.62f, n * 0.20f, n * 0.32f, c));
                g.draw(new java.awt.geom.Line2D.Float(n * 0.32f, c, n * 0.62f, n * 0.80f));
                break;
            case CHECK:
                g.draw(new java.awt.geom.Line2D.Float(n * 0.18f, n * 0.52f, n * 0.40f, n * 0.74f));
                g.draw(new java.awt.geom.Line2D.Float(n * 0.40f, n * 0.74f, n * 0.84f, n * 0.28f));
                break;
            case COPY:
                g.draw(round(n * 0.30f, n * 0.30f, n * 0.50f, n * 0.54f, n * 0.08f));
                g.draw(new java.awt.geom.Line2D.Float(n * 0.20f, n * 0.66f, n * 0.20f, n * 0.20f));
                g.draw(new java.awt.geom.Line2D.Float(n * 0.20f, n * 0.20f, n * 0.62f, n * 0.20f));
                break;
            case LOCK:
                g.draw(round(n * 0.22f, n * 0.44f, n * 0.56f, n * 0.42f, n * 0.07f));
                g.draw(new java.awt.geom.Arc2D.Float(n * 0.32f, n * 0.14f, n * 0.36f, n * 0.50f, 0, 180, java.awt.geom.Arc2D.OPEN));
                g.draw(new java.awt.geom.Line2D.Float(n * 0.32f, n * 0.39f, n * 0.32f, n * 0.46f));
                g.draw(new java.awt.geom.Line2D.Float(n * 0.68f, n * 0.39f, n * 0.68f, n * 0.46f));
                break;
            case REPLY: {
                Path2D p = new Path2D.Float();
                p.moveTo(n * 0.40, n * 0.24); p.lineTo(n * 0.16, n * 0.46); p.lineTo(n * 0.40, n * 0.68);
                g.draw(p);
                Path2D q = new Path2D.Float();
                q.moveTo(n * 0.18, n * 0.46); q.curveTo(n * 0.60, n * 0.46, n * 0.84, n * 0.54, n * 0.84, n * 0.82);
                g.draw(q);
                break;
            }
            case DOWNLOAD:
                g.draw(new java.awt.geom.Line2D.Float(c, n * 0.16f, c, n * 0.64f));
                g.draw(new java.awt.geom.Line2D.Float(n * 0.30f, n * 0.46f, c, n * 0.66f));
                g.draw(new java.awt.geom.Line2D.Float(n * 0.70f, n * 0.46f, c, n * 0.66f));
                g.draw(new java.awt.geom.Line2D.Float(n * 0.20f, n * 0.84f, n * 0.80f, n * 0.84f));
                break;
        }
    }

    /** Lese-Haken (einfach oder doppelt). */
    static void drawTicks(Graphics2D g, float x, float y, float h, boolean dbl, Color c) {
        g.setColor(c);
        g.setStroke(new BasicStroke(Math.max(1.4f, h / 8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D p = new Path2D.Float();
        p.moveTo(x, y + h * 0.55); p.lineTo(x + h * 0.32, y + h * 0.85); p.lineTo(x + h * 0.85, y + h * 0.2);
        g.draw(p);
        if (dbl) {
            Path2D q = new Path2D.Float();
            q.moveTo(x + h * 0.45, y + h * 0.85); q.lineTo(x + h * 0.98 + h * 0.1, y + h * 0.2);
            g.draw(q);
        }
    }

    /** Uhr-Symbol (wird gesendet). */
    static void drawClock(Graphics2D g, float x, float y, float h, Color c) {
        g.setColor(c);
        g.setStroke(new BasicStroke(Math.max(1.2f, h / 9f)));
        g.draw(new java.awt.geom.Ellipse2D.Float(x, y + h * 0.1f, h * 0.8f, h * 0.8f));
        g.draw(new java.awt.geom.Line2D.Float(x + h * 0.4f, y + h * 0.5f, x + h * 0.4f, y + h * 0.28f));
        g.draw(new java.awt.geom.Line2D.Float(x + h * 0.4f, y + h * 0.5f, x + h * 0.58f, y + h * 0.5f));
    }

    static void paintBg(Graphics g, Component c, Color col) {
        g.setColor(col);
        g.fillRect(0, 0, c.getWidth(), c.getHeight());
    }

    private Theme() {}
}
