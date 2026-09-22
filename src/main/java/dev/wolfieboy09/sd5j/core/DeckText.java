package dev.wolfieboy09.sd5j.core;

import dev.wolfieboy09.sd5j.core.image.DeckImage;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws labels onto a {@link DeckImage}; the only class in core that touches AWT fonts.
 * The driver initializes AWT on its own thread at startup (see {@link #warmup()}), so on
 * macOS AWT is never claimed by the game's main thread.
 */
public final class DeckText {
    private DeckText() {}

    public static final Font DEFAULT_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 16);

    /**
     * Initializes the AWT classes on the calling thread. Called once by the driver thread
     * at startup so the first AWT touch never lands on the main thread.
     */
    public static void warmup() {
        label(16, 16, "w", 0xFFFFFFFF, 0xFF000000);
    }

    /** Centres a single line, shrinking the font until it fits. */
    public static DeckImage label(int width, int height, String text, int argb, int background) {
        return label(width, height, text, DEFAULT_FONT, argb, background, 4);
    }

    public static DeckImage label(int width, int height, String text, Font font,
                                  int argb, int background, int padding) {
        DeckImage image = DeckImage.filled(width, height, background);
        drawWrapped(image, text, font, argb, padding, 0, width, height);
        return image;
    }

    /**
     * Draws word-wrapped, centred text into a box on an existing image. The font shrinks until
     * the text fits the box.
     *
     * @param topOffset y of the box top, in image pixels
     * @param boxHeight height of the box
     */
    public static void drawWrapped(DeckImage target, String text, Font font, int argb,
                                   int padding, int topOffset, int boxWidth, int boxHeight) {
        if (text == null || text.isEmpty()) return;

        BufferedImage canvas = target.toBufferedImage();
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(argb, true));

            int available = Math.max(1, boxWidth - padding * 2);
            int availableHeight = Math.max(1, boxHeight - padding * 2);

            Font fit = font;
            List<String> lines = wrap(text, fit, g.getFontRenderContext(), available);
            while (fit.getSize() > 6
                    && (lines.size() * lineHeight(g, fit) > availableHeight
                    || widest(lines, fit, g.getFontRenderContext()) > available)) {
                fit = fit.deriveFont((float) fit.getSize() - 1f);
                lines = wrap(text, fit, g.getFontRenderContext(), available);
            }

            g.setFont(fit);
            int lineHeight = lineHeight(g, fit);
            int totalHeight = lineHeight * lines.size();
            int y = topOffset + (boxHeight - totalHeight) / 2 + g.getFontMetrics().getAscent();
            for (String line : lines) {
                int w = g.getFontMetrics().stringWidth(line);
                g.drawString(line, (boxWidth - w) / 2f, y);
                y += lineHeight;
            }
        } finally {
            g.dispose();
        }

        int[] px = new int[target.width() * target.height()];
        canvas.getRGB(0, 0, target.width(), target.height(), px, 0, target.width());
        System.arraycopy(px, 0, target.pixels(), 0, px.length);
    }

    private static int lineHeight(Graphics2D g, Font font) {
        return g.getFontMetrics(font).getHeight();
    }

    private static int widest(List<String> lines, Font font, FontRenderContext frc) {
        int max = 0;
        for (String line : lines) {
            Rectangle2D bounds = font.getStringBounds(line, frc);
            max = Math.max(max, (int) Math.ceil(bounds.getWidth()));
        }
        return max;
    }

    private static List<String> wrap(String text, Font font, FontRenderContext frc, int maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder current = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (font.getStringBounds(candidate, frc).getWidth() > maxWidth && !current.isEmpty()) {
                    lines.add(current.toString());
                    current = new StringBuilder(word);
                } else {
                    current = new StringBuilder(candidate);
                }
            }
            lines.add(current.toString());
        }
        return lines;
    }
}