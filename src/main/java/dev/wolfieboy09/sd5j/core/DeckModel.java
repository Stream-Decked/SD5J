package dev.wolfieboy09.sd5j.core;

import org.jetbrains.annotations.Nullable;

/**
 * Every Stream Deck variant the library knows how to render for, described purely by its
 * geometry and screen formats. The bridge reports the bound device as a display name at
 * {@code deckConnect}; a display name with no constant here makes the client ignore the deck.
 * Image facts derive from python-elgato-streamdeck / elgato-streamdeck (rust).
 *
 * <p>This is a class, not an enum, so the bridge can also report a device whose type the
 * plugin does not model (virtual decks, new hardware). {@link #generic} builds such a deck
 * from its live grid geometry with untransformed images, so a virtual deck renders
 * right-way-up and navigation reservations use the real columns.</p>
 */
@SuppressWarnings("unused")
public final class DeckModel {
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

    // ---- BMP era: bottom-up 24 bit bitmaps, panels physically mounted at an offset ----
    // Mini family: panels sit 90deg off-axis. Confirmed on a physical Stream Deck Mini:
    // no rotation, mirrored on Y only. R270 (with or without mirrors) came out rotated
    // or mirrored, so do not "fix" this back to a rotation without checking hardware.
    public static final DeckModel ORIGINAL = new DeckModel(
            "Stream Deck", 5, 3, ImageSpec.bmp(72, 72, Rot.R0, true, true), null, 0, 0);
    public static final DeckModel MINI = new DeckModel(
            "Stream Deck Mini", 3, 2, ImageSpec.bmp(80, 80, Rot.R0, false, true), null, 0, 0);
    public static final DeckModel MINI_MK2 = new DeckModel(
            "Stream Deck Mini MK.2", 3, 2, ImageSpec.bmp(80, 80, Rot.R0, false, true), null, 0, 0);
    public static final DeckModel MINI_DISCORD = new DeckModel(
            "Stream Deck Mini Discord", 3, 2, ImageSpec.bmp(80, 80, Rot.R0, false, true), null, 0, 0);
    public static final DeckModel MINI_MK2_MODULE = new DeckModel(
            "Stream Deck Mini Module", 3, 2, ImageSpec.bmp(80, 80, Rot.R0, false, true), null, 0, 0);

    // ---- JPEG era: baseline JPEG keys ----
    public static final DeckModel ORIGINAL_V2 = new DeckModel(
            "Stream Deck V2", 5, 3, ImageSpec.jpeg(72, 72, Rot.R0, true, true), null, 0, 0);
    public static final DeckModel MK2 = new DeckModel(
            "Stream Deck MK.2", 5, 3, ImageSpec.jpeg(72, 72, Rot.R0, true, true), null, 0, 0);
    public static final DeckModel MK2_SCISSOR = new DeckModel(
            "Stream Deck MK.2 Scissor", 5, 3, ImageSpec.jpeg(72, 72, Rot.R0, true, true), null, 0, 0);
    public static final DeckModel MK2_MODULE = new DeckModel(
            "Stream Deck MK.2 Module", 5, 3, ImageSpec.jpeg(72, 72, Rot.R0, true, true), null, 0, 0);
    public static final DeckModel XL = new DeckModel(
            "Stream Deck XL", 8, 4, ImageSpec.jpeg(96, 96, Rot.R0, true, true), null, 0, 0);
    public static final DeckModel XL_V2 = new DeckModel(
            "Stream Deck XL V2", 8, 4, ImageSpec.jpeg(96, 96, Rot.R0, true, true), null, 0, 0);
    public static final DeckModel XL_V2_MODULE = new DeckModel(
            "Stream Deck XL Module", 8, 4, ImageSpec.jpeg(96, 96, Rot.R0, true, true), null, 0, 0);

    /** 8 keys, 4 encoders and a 800x100 touch strip. */
    public static final DeckModel PLUS = new DeckModel(
            "Stream Deck +", 4, 2, ImageSpec.jpeg(120, 120, Rot.R0, false, false),
            ImageSpec.jpeg(800, 100, Rot.R0, false, false), 4, 0);
    public static final DeckModel PLUS_XL = new DeckModel(
            "Stream Deck + XL", 9, 4, ImageSpec.jpeg(120, 120, Rot.R270, false, false),
            ImageSpec.jpeg(100, 1200, Rot.R270, false, false), 6, 0);
    /** 8 keys, 2 capacitive touch points and a 248x58 info screen. */
    public static final DeckModel NEO = new DeckModel(
            "Stream Deck Neo", 4, 2, ImageSpec.jpeg(96, 96, Rot.R0, true, true),
            ImageSpec.jpeg(248, 58, Rot.R180, false, false), 0, 2);

    /** No screens at all. */
    public static final DeckModel PEDAL = new DeckModel(
            "Stream Deck Pedal", 3, 1, null, null, 0, 0);

    private static final DeckModel[] KNOWN = {
            ORIGINAL, MINI, MINI_MK2, MINI_DISCORD, MINI_MK2_MODULE,
            ORIGINAL_V2, MK2, MK2_SCISSOR, MK2_MODULE, XL, XL_V2, XL_V2_MODULE,
            PLUS, PLUS_XL, NEO, PEDAL,
    };

    private final String displayName;
    private final int columns;
    private final int rows;
    private final ImageSpec keyImage;
    private final ImageSpec screenImage;
    private final int encoderCount;
    private final int touchpointCount;

    private DeckModel(String displayName, int columns, int rows, ImageSpec keyImage, ImageSpec screenImage,
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
        for (DeckModel m : KNOWN) {
            if (m.displayName.equalsIgnoreCase(displayName)) return m;
        }
        return null;
    }

    /** A deck of unknown hardware: live grid geometry and raw, untransformed key images. */
    public static DeckModel generic(String displayName, int columns, int rows) {
        if (displayName == null || displayName.isBlank()) displayName = "Stream Deck";
        if (columns < 1) columns = 5;
        if (rows < 1) rows = 3;
        return new DeckModel(displayName, columns, rows,
                ImageSpec.bmp(72, 72, Rot.R0, false, false), null, 0, 0);
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