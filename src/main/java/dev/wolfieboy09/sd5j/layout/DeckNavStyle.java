package dev.wolfieboy09.sd5j.layout;

import dev.wolfieboy09.sd5j.button.DeckButton;

/**
 * The label colours of the navigation buttons the library draws for you, so that the back, next
 * and previous buttons of a folder match the rest of a layout instead of each layout picking its
 * own.
 *
 * <p>Two ARGB values: the text, then the solid background behind it.
 *
 * <pre>{@code
 * DeckPaginator.of(surface.model(), spells, this::spellButton)
 *         .nav(new DeckNavStyle(0xFF101010, 0xFF303030))
 *         .refresh(surface);
 * }</pre>
 *
 * @param text       ARGB colour of the button's label
 * @param background ARGB colour behind the label
 */
public record DeckNavStyle(int text, int background) {
    /** White on near-black: the look the library uses when a layout says nothing. */
    public static final DeckNavStyle DEFAULT = new DeckNavStyle(0xFFFFFFFF, 0xFF202020);

    /** A {@link DeckButton#back()} in this style. */
    public DeckButton back() {
        return DeckButton.back("Back", text, background);
    }

    /** A {@link DeckButton#nextPage()} in this style. */
    public DeckButton next() {
        return DeckButton.nextPage("Next", text, background);
    }

    /** A {@link DeckButton#previousPage()} in this style. */
    public DeckButton previous() {
        return DeckButton.previousPage("Prev", text, background);
    }
}
