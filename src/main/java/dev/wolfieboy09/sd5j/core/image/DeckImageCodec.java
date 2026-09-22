package dev.wolfieboy09.sd5j.core.image;

import dev.wolfieboy09.sd5j.core.DeckModel;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Converts {@link DeckImage}s into the byte blobs the firmware accepts.
 *
 * <p>Two things happen here. First the geometry fix-up: every panel is mounted at some
 * orientation, so images get mirrored and rotated to match. Then the container: gen 1
 * hardware takes a bottom-up 24 bit BMP, gen 2 takes baseline JPEG.</p>
 */
public final class DeckImageCodec {
    private DeckImageCodec() {}

    /** JPEG quality used for key and screen images. 0.9 looks clean at these sizes. */
    public static volatile float jpegQuality = 0.9f;

    private static final Map<DeckModel, byte[]> BLANK_KEY_CACHE = new EnumMap<>(DeckModel.class);

    /** Encodes an image for a key of this device. Resizes and reorients as needed. */
    public static byte[] encodeKey(DeckModel model, DeckImage image) {
        DeckModel.ImageSpec spec = model.keyImage();
        if (spec == null) throw new UnsupportedOperationException(model + " has no key screens");
        return encode(spec, image);
    }

    /** Encodes a full-size image for the LCD strip / info screen. */
    public static byte[] encodeScreen(DeckModel model, DeckImage image) {
        DeckModel.ImageSpec spec = model.screenImage();
        if (spec == null) throw new UnsupportedOperationException(model + " has no screen");
        return encode(spec, image);
    }

    /**
     * Encodes an arbitrary rectangle for a partial screen write. Unlike the full-screen path
     * the image keeps its own size; only the container format and orientation come from the
     * device.
     */
    public static byte[] encodeScreenRegion(DeckModel model, DeckImage image) {
        DeckModel.ImageSpec spec = model.screenImage();
        if (spec == null) throw new UnsupportedOperationException(model + " has no screen");
        return encode(new DeckModel.ImageSpec(spec.mode(), image.width(), image.height(),
                spec.rotation(), spec.mirrorX(), spec.mirrorY()), image);
    }

    /** All-black key image, cached per model. */
    public static byte[] blankKey(DeckModel model) {
        return BLANK_KEY_CACHE.computeIfAbsent(model, m ->
                encodeKey(m, DeckImage.black(m.keyImage().width(), m.keyImage().height())));
    }

    private static byte[] encode(DeckModel.ImageSpec spec, DeckImage image) {
        DeckImage img = image;
        if (img.width() != spec.width() || img.height() != spec.height()) {
            img = img.fitInto(spec.width(), spec.height(), 0xFF000000);
        }
        // Flatten first: neither BMP24 nor JPEG carries alpha, and we want a defined result
        // rather than whatever the encoder decides to do with the alpha channel.
        img = img.copy().flatten(0xFF000000);

        if (spec.mirrorX()) img = img.mirrorX();
        if (spec.mirrorY()) img = img.mirrorY();
        img = img.rotate(spec.rotation());

        try {
            return switch (spec.mode()) {
                case BMP -> writeBmp24(img);
                case JPEG -> writeJpeg(img);
            };
        } catch (IOException e) {
            throw new IllegalStateException("failed to encode deck image", e);
        }
    }

    // ---- containers ----

    /**
     * Minimal 24 bit BITMAPINFOHEADER bitmap, BGR, rows padded to 4 bytes.
     * Hand-rolled rather than via ImageIO so the bytes are identical on every JDK.
     *
     * <p>Rows are written top-down even though the header declares a positive height (which by
     * strict BMP spec means bottom-up). This gen 1 firmware is a minimal parser that does not
     * appear to invert rows based on the header's height sign; it just reads the pixel payload
     * in file order. A spec-correct bottom-up BMP displays vertically flipped on real hardware.
     */
    static byte[] writeBmp24(DeckImage img) {
        int w = img.width(), h = img.height();
        int rowStride = (w * 3 + 3) & ~3;
        int pixelBytes = rowStride * h;
        int fileSize = 54 + pixelBytes;
        byte[] out = new byte[fileSize];

        out[0] = 'B'; out[1] = 'M';
        putInt32(out, 2, fileSize);
        putInt32(out, 10, 54);          // pixel data offset
        putInt32(out, 14, 40);          // BITMAPINFOHEADER size
        putInt32(out, 18, w);
        putInt32(out, 22, h);           // declared bottom-up per spec; payload is actually top-down, see above
        putInt16(out, 26, 1);           // planes
        putInt16(out, 28, 24);          // bits per pixel
        putInt32(out, 30, 0);           // BI_RGB
        putInt32(out, 34, pixelBytes);
        putInt32(out, 38, 3780);        // ~96 DPI
        putInt32(out, 42, 3780);

        int[] px = img.pixels();
        for (int y = 0; y < h; y++) {
            int src = y * w;
            int dst = 54 + y * rowStride;
            for (int x = 0; x < w; x++) {
                int p = px[src + x];
                out[dst++] = (byte) (p & 0xFF);         // B
                out[dst++] = (byte) ((p >> 8) & 0xFF);  // G
                out[dst++] = (byte) ((p >> 16) & 0xFF); // R
            }
        }
        return out;
    }

    static byte[] writeJpeg(DeckImage img) throws IOException {
        BufferedImage bi = new BufferedImage(img.width(), img.height(), BufferedImage.TYPE_3BYTE_BGR);
        bi.setRGB(0, 0, img.width(), img.height(), img.pixels(), 0, img.width());

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("no JPEG writer available");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
             MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(jpegQuality);
            }
            writer.write(null, new IIOImage(bi, null, null), param);
            ios.flush();
            return bos.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static void putInt32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void putInt16(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }
}