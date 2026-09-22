package dev.wolfieboy09.sd5j.core.image;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import dev.wolfieboy09.sd5j.core.DeckModel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * A plain ARGB pixel buffer. Avoids {@code Graphics2D} so mods can scale, tint and stack
 * badges without touching the AWT toolkit. Mutable and not thread safe: build one, hand it
 * to the API, and stop touching it (the driver thread reads it).
 */
@SuppressWarnings("unused")
@CanIgnoreReturnValue
public final class DeckImage {
    private final int width;
    private final int height;
    private final int[] pixels; // 0xAARRGGBB, row major

    public DeckImage(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("bad size " + width + "x" + height);
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    private DeckImage(int width, int height, int[] pixels) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    public static DeckImage filled(int width, int height, int argb) {
        DeckImage img = new DeckImage(width, height);
        Arrays.fill(img.pixels, argb);
        return img;
    }

    public static DeckImage black(int width, int height) { return filled(width, height, 0xFF000000); }

    public static DeckImage wrap(int width, int height, int[] argbPixels) {
        if (argbPixels.length != width * height) throw new IllegalArgumentException("pixel count mismatch");
        return new DeckImage(width, height, argbPixels);
    }

    /** Decodes PNG/JPEG/GIF/BMP bytes. Safe to call off the render thread. */
    public static DeckImage decode(InputStream in) throws IOException {
        BufferedImage bi = ImageIO.read(in);
        if (bi == null) throw new IOException("unsupported or corrupt image data");
        return fromBufferedImage(bi);
    }

    public static DeckImage decode(byte[] data) throws IOException {
        return decode(new ByteArrayInputStream(data));
    }

    public static DeckImage fromBufferedImage(BufferedImage bi) {
        int w = bi.getWidth(), h = bi.getHeight();
        int[] px = new int[w * h];
        bi.getRGB(0, 0, w, h, px, 0, w);
        return new DeckImage(w, h, px);
    }

    public BufferedImage toBufferedImage() {
        BufferedImage bi = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        bi.setRGB(0, 0, width, height, pixels, 0, width);
        return bi;
    }

    public int width()   { return width; }
    public int height()  { return height; }
    /** Live backing array; index as {@code y * width + x}. */
    public int[] pixels(){ return pixels; }

    public int get(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0;
        return pixels[y * width + x];
    }

    public void set(int x, int y, int argb) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        pixels[y * width + x] = argb;
    }

    public DeckImage fill(int argb) {
        Arrays.fill(pixels, argb);
        return this;
    }

    public DeckImage fillRect(int x, int y, int w, int h, int argb) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(width, x + w), y1 = Math.min(height, y + h);
        for (int yy = y0; yy < y1; yy++) {
            Arrays.fill(pixels, yy * width + x0, yy * width + x1, argb);
        }
        return this;
    }

    public DeckImage copy() {
        return new DeckImage(width, height, pixels.clone());
    }

    /** Source-over alpha blend of {@code src} at (x, y). */
    public DeckImage draw(DeckImage src, int x, int y) {
        for (int sy = 0; sy < src.height; sy++) {
            int dy = y + sy;
            if (dy < 0 || dy >= height) continue;
            for (int sx = 0; sx < src.width; sx++) {
                int dx = x + sx;
                if (dx < 0 || dx >= width) continue;
                pixels[dy * width + dx] = blend(pixels[dy * width + dx], src.pixels[sy * src.width + sx]);
            }
        }
        return this;
    }

    /** Multiplies every channel by the given 0xAARRGGBB tint. Useful for state coloring. */
    public DeckImage tint(int argb) {
        int ta = (argb >>> 24), tr = (argb >> 16) & 0xFF, tg = (argb >> 8) & 0xFF, tb = argb & 0xFF;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int a = ((p >>> 24) * ta) / 255;
            int r = (((p >> 16) & 0xFF) * tr) / 255;
            int g = (((p >> 8) & 0xFF) * tg) / 255;
            int b = ((p & 0xFF) * tb) / 255;
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return this;
    }

    /** Composites onto an opaque background, dropping the alpha channel. */
    public DeckImage flatten(int backgroundArgb) {
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xFF000000 | (blend(backgroundArgb, pixels[i]) & 0xFFFFFF);
        }
        return this;
    }

    /** Nearest-neighbor resize, keeping pixel art crisp. Returns {@code this} when already the requested size. */
    public DeckImage pixelResize(int newWidth, int newHeight) {
        if (newWidth == width && newHeight == height) return this;
        int[] out = new int[newWidth * newHeight];
        for (int y = 0; y < newHeight; y++) {
            int sy = y * height / newHeight;
            for (int x = 0; x < newWidth; x++) {
                int sx = x * width / newWidth;
                out[y * newWidth + x] = pixels[sy * width + sx];
            }
        }
        return new DeckImage(newWidth, newHeight, out);
    }

    /** Bilinear resize. Returns {@code this} when already the requested size. */
    public DeckImage resize(int newWidth, int newHeight) {
        if (newWidth == width && newHeight == height) return this;
        int[] out = new int[newWidth * newHeight];
        double xRatio = (double) width / newWidth;
        double yRatio = (double) height / newHeight;
        for (int y = 0; y < newHeight; y++) {
            double sy = (y + 0.5) * yRatio - 0.5;
            int y0 = (int) Math.floor(sy);
            double fy = sy - y0;
            int y1 = clamp(y0 + 1, 0, height - 1);
            y0 = clamp(y0, 0, height - 1);
            for (int x = 0; x < newWidth; x++) {
                double sx = (x + 0.5) * xRatio - 0.5;
                int x0 = (int) Math.floor(sx);
                double fx = sx - x0;
                int x1 = clamp(x0 + 1, 0, width - 1);
                x0 = clamp(x0, 0, width - 1);
                out[y * newWidth + x] = lerp2(
                        pixels[y0 * width + x0], pixels[y0 * width + x1],
                        pixels[y1 * width + x0], pixels[y1 * width + x1], fx, fy);
            }
        }
        return new DeckImage(newWidth, newHeight, out);
    }

    /** Scales to fit inside the box, preserving aspect ratio, and centres it on {@code background}. */
    public DeckImage fitInto(int boxWidth, int boxHeight, int background) {
        double scale = Math.min((double) boxWidth / width, (double) boxHeight / height);
        int w = Math.max(1, (int) Math.round(width * scale));
        int h = Math.max(1, (int) Math.round(height * scale));
        DeckImage out = filled(boxWidth, boxHeight, background);
        out.draw(resize(w, h), (boxWidth - w) / 2, (boxHeight - h) / 2);
        return out;
    }

    /** Like {@link #fitInto} but scales with nearest-neighbor interpolation, keeping pixel art crisp. */
    public DeckImage pixelFitInto(int boxWidth, int boxHeight, int background) {
        double scale = Math.min((double) boxWidth / width, (double) boxHeight / height);
        int w = Math.max(1, (int) Math.round(width * scale));
        int h = Math.max(1, (int) Math.round(height * scale));
        DeckImage out = filled(boxWidth, boxHeight, background);
        out.draw(pixelResize(w, h), (boxWidth - w) / 2, (boxHeight - h) / 2);
        return out;
    }

    /** Scales to cover the box, preserving aspect ratio, cropping the overflow. */
    public DeckImage coverInto(int boxWidth, int boxHeight) {
        double scale = Math.max((double) boxWidth / width, (double) boxHeight / height);
        int w = Math.max(1, (int) Math.round(width * scale));
        int h = Math.max(1, (int) Math.round(height * scale));
        DeckImage scaled = resize(w, h);
        DeckImage out = new DeckImage(boxWidth, boxHeight);
        out.draw(scaled, (boxWidth - w) / 2, (boxHeight - h) / 2);
        return out;
    }

    public DeckImage crop(int x, int y, int w, int h) {
        DeckImage out = new DeckImage(w, h);
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                out.pixels[yy * w + xx] = get(x + xx, y + yy);
            }
        }
        return out;
    }

    public DeckImage mirrorX() {
        int[] out = new int[pixels.length];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) out[row + x] = pixels[row + (width - 1 - x)];
        }
        return new DeckImage(width, height, out);
    }

    public DeckImage mirrorY() {
        int[] out = new int[pixels.length];
        for (int y = 0; y < height; y++) {
            System.arraycopy(pixels, (height - 1 - y) * width, out, y * width, width);
        }
        return new DeckImage(width, height, out);
    }

    /** Clockwise. */
    public DeckImage rotate90() {
        int[] out = new int[pixels.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out[x * height + (height - 1 - y)] = pixels[y * width + x];
            }
        }
        return new DeckImage(height, width, out);
    }

    public DeckImage rotate180() {
        int[] out = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) out[i] = pixels[pixels.length - 1 - i];
        return new DeckImage(width, height, out);
    }

    /** Counter-clockwise. */
    public DeckImage rotate270() {
        int[] out = new int[pixels.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out[(width - 1 - x) * height + y] = pixels[y * width + x];
            }
        }
        return new DeckImage(height, width, out);
    }

    public DeckImage rotate(DeckModel.Rot rot) {
        return switch (rot) {
            case R0 -> this;
            case R90 -> rotate90();
            case R180 -> rotate180();
            case R270 -> rotate270();
        };
    }

    // ---- helpers ----

    private static int blend(int dst, int src) {
        int sa = src >>> 24;
        if (sa == 255) return src;
        if (sa == 0) return dst;
        int da = dst >>> 24;
        int outA = sa + da * (255 - sa) / 255;
        if (outA == 0) return 0;
        int r = (((src >> 16) & 0xFF) * sa + ((dst >> 16) & 0xFF) * da * (255 - sa) / 255) / outA;
        int g = (((src >> 8) & 0xFF) * sa + ((dst >> 8) & 0xFF) * da * (255 - sa) / 255) / outA;
        int b = ((src & 0xFF) * sa + (dst & 0xFF) * da * (255 - sa) / 255) / outA;
        return (outA << 24) | (clamp(r, 0, 255) << 16) | (clamp(g, 0, 255) << 8) | clamp(b, 0, 255);
    }

    private static int lerp2(int c00, int c10, int c01, int c11, double fx, double fy) {
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            double top = ((c00 >>> shift) & 0xFF) * (1 - fx) + ((c10 >>> shift) & 0xFF) * fx;
            double bot = ((c01 >>> shift) & 0xFF) * (1 - fx) + ((c11 >>> shift) & 0xFF) * fx;
            int v = (int) Math.round(top * (1 - fy) + bot * fy);
            out |= clamp(v, 0, 255) << shift;
        }
        return out;
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (Math.min(v, hi)); }
}