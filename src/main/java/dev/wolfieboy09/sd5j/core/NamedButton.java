package dev.wolfieboy09.sd5j.core;

import dev.wolfieboy09.sd5j.core.image.DeckImage;
import org.jetbrains.annotations.Nullable;

/**
 * A mutable button reachable by a name: keep the instance from {@link DeckSurface#putButton}
 * and swap its icon or press action at any time; the host surface redraws the key. Names are
 * scoped to the page the button lives on.
 */
public final class NamedButton implements DeckButton {
    private final String name;
    @Nullable private DeckImage icon;
    @Nullable private Runnable onPress;
    @Nullable private String caption;
    private int captionArgb = 0xFFFFFFFF;
    @Nullable private DeckSurface surface;
    private int key = -1;

    /** A button with a fixed icon and a press action, like {@link DeckButton#of}. */
    public static NamedButton of(String name, @Nullable DeckImage icon, @Nullable Runnable onPress) {
        return new NamedButton(name, icon, onPress);
    }

    public NamedButton(String name, @Nullable DeckImage icon, @Nullable Runnable onPress) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("a button name is required");
        this.name = name;
        this.icon = icon;
        this.onPress = onPress;
    }

    /** The name this button is placed under. */
    public String name() { return name; }

    /** The icon currently shown, or null when the button is iconless. */
    @Nullable
    public DeckImage icon() { return icon; }

    /**
     * Swaps the icon and redraws the key if this button is currently showing. A null icon
     * clears it, which is the sensible reading of a texture lookup that came back empty.
     */
    public void setIcon(@Nullable DeckImage newIcon) {
        this.icon = newIcon;
        redraw();
    }

    /** The caption shown under the icon, or null when the button is icon-only. */
    @Nullable
    public String caption() { return caption; }

    /**
     * Sets the caption drawn along the bottom of the key, below the icon, and redraws. Null or
     * blank drops the strip and gives the whole key to the icon. Independent of the icon, so a
     * button can keep its caption while the icon is swapped, and the reverse.
     */
    public NamedButton setCaption(@Nullable String caption) {
        this.caption = (caption == null || caption.isBlank()) ? null : caption;
        redraw();
        return this;
    }

    /** Sets the caption and its text colour. */
    public NamedButton setCaption(@Nullable String caption, int captionArgb) {
        this.captionArgb = captionArgb;
        return setCaption(caption);
    }

    /** Replaces the press action. */
    public void setAction(@Nullable Runnable onPress) { this.onPress = onPress; }

    /** Runs the press action, if any. */
    public void press() {
        Runnable action = onPress;
        if (action != null) action.run();
    }

    @Override
    public DeckImage render(int width, int height) {
        return DeckText.iconWithCaption(icon, caption, width, height, captionArgb);
    }

    @Override
    public void onDown(DeckSurface surface, int key) { press(); }

    private void redraw() {
        DeckSurface host = surface;
        if (host != null && key >= 0) host.redraw(key);
    }

    void attach(DeckSurface surface, int key) {
        this.surface = surface;
        this.key = key;
    }

    void detach() {
        this.surface = null;
        this.key = -1;
    }
}