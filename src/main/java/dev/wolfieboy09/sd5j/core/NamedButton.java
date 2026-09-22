package dev.wolfieboy09.sd5j.core;

import dev.wolfieboy09.sd5j.core.image.DeckImage;
import org.jetbrains.annotations.Nullable;

/**
 * A mutable button reachable by a name. Unlike the stateless {@link DeckButton} factories, an
 * addon keeps the instance returned by {@link DeckSurface#putButton} and can swap its icon or
 * press action at any time; the hosting surface redraws the key automatically.
 *
 * <p>Names are scoped to the page the button lives on, so the same name may exist on
 * different pages, folders, or decks.</p>
 */
public final class NamedButton implements DeckButton {
    private final String name;
    @Nullable private DeckImage icon;
    @Nullable private Runnable onPress;
    @Nullable private DeckSurface surface;
    private int key = -1;

    /** A button with a fixed icon and a press action, like {@link DeckButton#of}. */
    public static NamedButton of(String name, DeckImage icon, @Nullable Runnable onPress) {
        return new NamedButton(name, icon, onPress);
    }

    public NamedButton(String name, DeckImage icon, @Nullable Runnable onPress) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("a button name is required");
        if (icon == null) throw new IllegalArgumentException("an icon is required for " + name);
        this.name = name;
        this.icon = icon;
        this.onPress = onPress;
    }

    /** The name this button is placed under. */
    public String name() { return name; }

    /** The icon currently shown. */
    @Nullable
    public DeckImage icon() { return icon; }

    /** Swaps the icon and redraws the key if this button is currently showing. */
    public void setIcon(DeckImage newIcon) {
        if (newIcon == null) throw new IllegalArgumentException("icon for " + name + " cannot be null");
        this.icon = newIcon;
        redraw();
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
        DeckImage current = icon;
        return current.fitInto(width, height, 0xFF000000);
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