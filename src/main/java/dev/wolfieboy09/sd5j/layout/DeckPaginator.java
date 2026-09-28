package dev.wolfieboy09.sd5j.layout;

import dev.wolfieboy09.sd5j.button.DeckButton;
import dev.wolfieboy09.sd5j.deck.DeckModel;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Function;

/**
 * Splits a list into as many pages of keys as the deck can hold, and builds the page maps that
 * {@link DeckSurface#openFolder(List, String)} and {@link DeckSurface#replacePages(List)} take.
 *
 * <p>Back and next are added for you in {@link DeckNavStyle#DEFAULT}, recolourable with
 * {@link #nav(DeckNavStyle)}; next is drawn only once the list needs a second page. Previous is
 * not added, because it costs a content key, so ask for it with {@link #previous}. Pass
 * {@link #noNavigation()} to supply your own or none at all.
 *
 * <p>Back's key is reserved even with no button for it, because that is the key a folder page gives
 * to Back.
 */
@SuppressWarnings("unused")
public final class DeckPaginator<T> {
    private final DeckModel model;
    private final List<T> items;
    private final Function<T, DeckButton> renderer;

    private DeckNavStyle style = DeckNavStyle.DEFAULT;
    private Slot back = Slot.AUTO;
    private Slot next = Slot.AUTO;
    private Slot previous = Slot.OFF;

    /** One navigation slot: absent, drawn from the style, or a button the caller supplied. */
    private record Slot(boolean present, @Nullable DeckButton custom) {
        static final Slot OFF = new Slot(false, null);
        static final Slot AUTO = new Slot(true, null);

        static Slot of(@Nullable DeckButton button) {
            return button == null ? OFF : new Slot(true, button);
        }

        /** The button to draw here, or null when the slot is off. */
        @Nullable DeckButton resolve(DeckButton automatic) {
            if (!present) return null;
            return custom != null ? custom : automatic;
        }
    }

    private DeckPaginator(DeckModel model, List<T> items, Function<T, DeckButton> renderer) {
        if (model == null) throw new IllegalArgumentException("a model is required");
        if (items == null) throw new IllegalArgumentException("a list is required");
        if (renderer == null) throw new IllegalArgumentException("a renderer is required");
        this.model = model;
        this.items = items;
        this.renderer = renderer;
    }

    /** Starts a paginator over {@code items}, rendering each one with {@code renderer}. */
    public static <T> DeckPaginator<T> of(DeckModel model, List<T> items, Function<T, DeckButton> renderer) {
        return new DeckPaginator<>(model, items, renderer);
    }

    /**
     * Recolours the back, next and previous buttons this paginator draws for you. Applied when the
     * pages are built, so it does not matter whether you set it before or after asking for a
     * button. Buttons you supply yourself are left alone.
     */
    public DeckPaginator<T> nav(DeckNavStyle style) {
        this.style = style == null ? DeckNavStyle.DEFAULT : style;
        return this;
    }

    /** Recolours the automatic navigation buttons. See {@link #nav(DeckNavStyle)}. */
    public DeckPaginator<T> colors(int textArgb, int backgroundArgb) {
        return nav(new DeckNavStyle(textArgb, backgroundArgb));
    }

    /** The back button, or null to leave the level with no way out. The key stays reserved either way. */
    public DeckPaginator<T> back(@Nullable DeckButton button) {
        this.back = Slot.of(button);
        return this;
    }

    /** The next-page button, drawn only when the list is paged. */
    public DeckPaginator<T> next(@Nullable DeckButton button) {
        this.next = Slot.of(button);
        return this;
    }

    /** The previous-page button in the current style, drawn only when the list is paged. */
    public DeckPaginator<T> previous() {
        this.previous = Slot.AUTO;
        return this;
    }

    /** The previous-page button, drawn only when the list is paged. */
    public DeckPaginator<T> previous(@Nullable DeckButton button) {
        this.previous = Slot.of(button);
        return this;
    }

    /**
     * Adds a previous-page button, unless reserving its key would leave the deck with no room for
     * content. That only happens on a three-key pedal once back and next are also reserved.
     *
     * <p>Use this rather than {@link #previous()} when the deck is whatever the user happens to
     * have plugged in, and the layout would rather drop Previous than fail.
     *
     * @return true if the button was added
     */
    public boolean previousIfRoom() {
        if (navigationKeys().size() + 1 >= model.keyCount()) return false;
        this.previous = Slot.AUTO;
        return true;
    }

    /** Drops the automatic back and next, for a page whose navigation someone else supplies. */
    public DeckPaginator<T> noNavigation() {
        this.back = Slot.OFF;
        this.next = Slot.OFF;
        this.previous = Slot.OFF;
        return this;
    }

    /** True when the list does not fit on one page. */
    public boolean isPaged() {
        int per = model.keyCount() - navigationKeys().size();
        if (per < 1) return false;
        return items.size() > per;
    }

    /** The keys navigation holds, whether or not a button is drawn on them. */
    public List<Integer> navigationKeys() {
        List<Integer> keys = new ArrayList<>(3);
        keys.add(model.backKey());
        if (next.present()) keys.add(model.nextKey());
        if (previous.present()) keys.add(model.previousKey());
        return keys;
    }

    /** Keys left for content, in ascending order. See {@link DeckSurface#contentKeys()} for the live equivalent. */
    public List<Integer> contentKeys() {
        Set<Integer> reserved = new LinkedHashSet<>(navigationKeys());
        List<Integer> keys = new ArrayList<>(model.keyCount());
        for (int key = 0; key < model.keyCount(); key++) {
            if (!reserved.contains(key)) keys.add(key);
        }
        return keys;
    }

    /**
     * How many items fit on each page.
     *
     * @throws IllegalStateException if navigation leaves no key free
     */
    public int capacity() {
        int free = model.keyCount() - navigationKeys().size();
        if (free < 1) {
            throw new IllegalStateException("navigation leaves no free key on a " + model
                    + "; pass fewer navigation buttons or use a deck with more keys");
        }
        return free;
    }

    /** How many pages this list needs. Always at least 1, even when empty. */
    public int pageCount() {
        int per = capacity();
        return Math.max(1, (items.size() + per - 1) / per);
    }

    /** The list cut into pages, each no longer than {@link #capacity()}. */
    public List<List<T>> pages() {
        int per = capacity();
        List<List<T>> batches = new ArrayList<>(Math.max(1, (items.size() + per - 1) / per));
        for (int from = 0; from < items.size(); from += per) {
            batches.add(List.copyOf(items.subList(from, Math.min(from + per, items.size()))));
        }
        if (batches.isEmpty()) batches.add(List.of());
        return batches;
    }

    /**
     * The pages as key-to-button maps, ready for {@link DeckSurface#openFolder(List, String)} or
     * {@link DeckSurface#replacePages(List)}. Renderers run here, once per item shown.
     */
    public List<Map<Integer, DeckButton>> build() {
        List<List<T>> batches = pages();
        List<Integer> slots = contentKeys();
        boolean paged = batches.size() > 1;

        DeckButton backButton = back.resolve(style.back());
        DeckButton nextButton = next.resolve(style.next());
        DeckButton previousButton = previous.resolve(style.previous());

        List<Map<Integer, DeckButton>> built = new ArrayList<>(batches.size());
        for (List<T> batch : batches) {
            Map<Integer, DeckButton> page = new LinkedHashMap<>();
            if (backButton != null) page.put(model.backKey(), backButton);
            if (paged && nextButton != null) page.put(model.nextKey(), nextButton);
            if (paged && previousButton != null) page.put(model.previousKey(), previousButton);
            for (int i = 0; i < batch.size() && i < slots.size(); i++) {
                page.put(slots.get(i), renderer.apply(batch.get(i)));
            }
            built.add(page);
        }
        return built;
    }

    /** Builds these pages and opens them as a new folder, showing the first. */
    public void openOn(DeckSurface surface, @Nullable String id) {
        surface.openFolder(build(), id);
    }

    /**
     * Builds these pages and swaps them into the level showing now, keeping the player on the
     * same page index where it still exists. See {@link DeckSurface#replacePages(List)}.
     */
    public void refresh(DeckSurface surface) {
        surface.replacePages(build());
    }

    @Override public String toString() {
        int per = capacity();
        return "DeckPaginator[" + items.size() + " items, " + pageCount() + " page(s) of " + per + " on " + model + "]";
    }
}
