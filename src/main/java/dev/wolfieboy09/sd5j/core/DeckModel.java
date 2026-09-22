package dev.wolfieboy09.sd5j.core;

import org.jetbrains.annotations.Nullable;

/**
 * Every Stream Deck variant the library knows how to render for, described purely by its
 * geometry and screen formats.
 *
 * <p>Identity used to come from USB ids and the image transport generation; both are gone.
 * In the remote bridge the app reports which device a client is bound to at {@code deckConnect}
 * as a display name, so this enum now only exists to give the codec its pixel formats. If Elgato
 * ships new hardware, adding a constant here (or mapping the new name via
 * {@link #fromDisplayName}) is normally all that is needed.</p>
 *
 * <p>Image facts here are derived from the reverse-engineering work in python-elgato-streamdeck
 * / elgato-streamdeck (rust).</p>
 */
@SuppressWarnings("unused")
public enum DeckModel {
    // ---- BMP era: bottom-up 24 bit bitmaps, panels physically mounted at an offset ----
    // Mini family: panels mounted 90deg off-axis; counter-clockwise (R270) with no
    // mirroring renders right-way-up on real hardware (matches python-elgato-streamdeck).
    // CW90 left the image upside-down. Do not add a mirror flag on top of the rotation.
    ORIGINAL       ("Stream Deck",              5, 3, ImageSpec.bmp (72,  72, Rot.R0,   true,  true ), null, 0, 0),
    MINI           ("Stream Deck Mini",         3, 2, ImageSpec.bmp (80,  80, Rot.R270, false, false), null, 0, 0),
    MINI_MK2       ("Stream Deck Mini MK.2",    3, 2, ImageSpec.bmp (80,  80, Rot.R270, false, false), null, 0, 0),
    MINI_DISCORD   ("Stream Deck Mini Discord", 3, 2, ImageSpec.bmp (80,  80, Rot.R270, false, false), null, 0, 0),
    MINI_MK2_MODULE("Stream Deck Mini Module",  3, 2, ImageSpec.bmp (80,  80, Rot.R270, false, false), null, 0, 0),

    // ---- JPEG era: baseline JPEG keys ----
    ORIGINAL_V2    ("Stream Deck V2",           5, 3, ImageSpec.jpeg(72,  72, Rot.R0,   true,  true ), null, 0, 0),
    MK2            ("Stream Deck MK.2",         5, 3, ImageSpec.jpeg(72,  72, Rot.R0,   true,  true ), null, 0, 0),
    MK2_SCISSOR    ("Stream Deck MK.2 Scissor", 5, 3, ImageSpec.jpeg(72,  72, Rot.R0,   true,  true ), null, 0, 0),
    MK2_MODULE     ("Stream Deck MK.2 Module",  5, 3, ImageSpec.jpeg(72,  72, Rot.R0,   true,  true ), null, 0, 0),
    XL             ("Stream Deck XL",           8, 4, ImageSpec.jpeg(96,  96, Rot.R0,   true,  true ), null, 0, 0),
    XL_V2          ("Stream Deck XL V2",        8, 4, ImageSpec.jpeg(96,  96, Rot.R0,   true,  true ), null, 0, 0),
    XL_V2_MODULE   ("Stream Deck XL Module",    8, 4, ImageSpec.jpeg(96,  96, Rot.R0,   true,  true ), null, 0, 0),

    /** 8 keys, 4 encoders and a 800x100 touch strip. */
    PLUS           ("Stream Deck +",            4, 2, ImageSpec.jpeg(120, 120, Rot.R0,  false, false),
            ImageSpec.jpeg(800, 100, Rot.R0, false, false), 4, 0),
    PLUS_XL        ("Stream Deck + XL",         9, 4, ImageSpec.jpeg(120, 120, Rot.R270, false, false),
            ImageSpec.jpeg(100, 1200, Rot.R270, false, false), 6, 0),
    /** 8 keys, 2 capacitive touch points and a 248x58 info screen. */
    NEO            ("Stream Deck Neo",          4, 2, ImageSpec.jpeg(96,  96, Rot.R0,   true,  true ),
            ImageSpec.jpeg(248, 58, Rot.R180, false, false), 0, 2),

    /** No screens at all. */
    PEDAL          ("Stream Deck Pedal",        3, 1, null, null, 0, 0);

    /** Clockwise rotation applied to an image before it is sent to the device. */
    public enum Rot { R0, R90, R180, R270 }

    public enum ImageMode { BMP, JPEG }

    /**
     * How a panel expects its pixels: size, container format and the mirror/rotate dance the
     * firmware requires (the panels are physically mounted in various orientations).
     */
    public record ImageSpec(ImageMode mode, int width, int height, Rot rotation,
                            boolean mirrorX, boolean mirrorY) {
        static ImageSpec bmp(int w, int h, Rot r, boolean mx, boolean my) {
            return new ImageSpec(ImageMode.BMP, w, h, r, mx, my);
        }
        static ImageSpec jpeg(int w, int h, Rot r, boolean mx, boolean my) {
            return new ImageSpec(ImageMode.JPEG, w, h, r, mx, my);
        }
    }

    private final String displayName;
    private final int columns;
    private final int rows;
    private final ImageSpec keyImage;
    private final ImageSpec screenImage;
    private final int encoderCount;
    private final int touchpointCount;

    DeckModel(String displayName, int columns, int rows, ImageSpec keyImage, ImageSpec screenImage,
              int encoderCount, int touchpointCount) {
        this.displayName = displayName;
        this.columns = columns;
        this.rows = rows;
        this.keyImage = keyImage;
        this.screenImage = screenImage;
        this.encoderCount = encoderCount;
        this.touchpointCount = touchpointCount;
    }

    /** Maps a device name as reported by the app over the bridge, or null if unknown. */
    public static @Nullable DeckModel fromDisplayName(String displayName) {
        if (displayName == null) return null;
        for (DeckModel m : values()) {
            if (m.displayName.equalsIgnoreCase(displayName)) return m;
        }
        return null;
    }

    public String displayName()    { return displayName; }
    public int columns()           { return columns; }
    public int rows()              { return rows; }
    public int keyCount()          { return columns * rows; }
    public int encoderCount()      { return encoderCount; }
    public int touchpointCount()   { return touchpointCount; }

    /** Pixel format the keys want, or null on hardware without key screens (Pedal). */
    public ImageSpec keyImage()    { return keyImage; }
    /** Pixel format of the LCD strip / info screen, or null if there is none. */
    public ImageSpec screenImage() { return screenImage; }

    public boolean hasKeyScreens() { return keyImage != null; }
    public boolean hasScreen()     { return screenImage != null; }

    public int keyIndex(int column, int row) { return row * columns + column; }
    public int columnOf(int keyIndex)        { return keyIndex % columns; }
    public int rowOf(int keyIndex)           { return keyIndex / columns; }

    @Override public String toString() { return displayName; }
}