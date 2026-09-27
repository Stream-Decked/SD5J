package dev.wolfieboy09.sd5j.core;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Splits a list into as many pages of keys as the deck can hold, and builds the page maps that
 * {@link DeckSurface#openFolder(List, String)} and {@link DeckSurface#replacePages(List)} take.
 *
 * <p>Back is always reserved, even with no button for it, because that is the key a folder page
 * gives to Back. Next and previous are reserved once you pass a button for them, and drawn only
 * when the list needs more than one page.
 */
@SuppressWarnings("unused")
public final class DeckPaginator<T> {
    private final DeckModel model;
    private final List<T> items;
    private final Function<T, DeckButton> renderer;

    private @Nullable DeckButton back;
    private @Nullable DeckButton next;
    private @Nullable DeckButton previous;

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

    /** The back button, or null to leave the level with no way out. The key stays reserved either way. */
    public DeckPaginator<T> back(@Nullable DeckButton button) {
        this.back = button;
        return this;
    }

    /** The next-page button, drawn only when the list is paged. */
    public DeckPaginator<T> next(@Nullable DeckButton button) {
        this.next = button;
        return this;
    }

    /** The previous-page button, drawn only when the list is paged. */
    public DeckPaginator<T> previous(@Nullable DeckButton button) {
        this.previous = button;
        return this;
    }

    /** True when the list does not fit on one page. */
    public boolean isPaged() {
        int per = model.keyCount() - navigationKeys().size();
        if (per < 1) return false;
        return items.size() > per;
    }

    /** The keys navigation holds, whether a button was supplied for them. */
    public List<Integer> navigationKeys() {
        List<Integer> keys = new ArrayList<>(3);
        keys.add(model.backKey());
        if (next != null) keys.add(model.nextKey());
        if (previous != null) keys.add(model.previousKey());
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

        List<Map<Integer, DeckButton>> built = new ArrayList<>(batches.size());
        for (List<T> batch : batches) {
            Map<Integer, DeckButton> page = new LinkedHashMap<>();
            if (back != null) page.put(model.backKey(), back);
            if (paged && next != null) page.put(model.nextKey(), next);
            if (paged && previous != null) page.put(model.previousKey(), previous);
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
