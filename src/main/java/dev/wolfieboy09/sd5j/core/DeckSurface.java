package dev.wolfieboy09.sd5j.core;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import dev.wolfieboy09.sd5j.core.image.DeckImage;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A deck's keys as assignable slots with a page stack on top. Owning the panel this way keeps
 * redraws cheap: only changed slots are re-encoded and uploaded; unassigned keys draw black.
 * Safe to call from the game thread; rendering and uploading happen on the driver thread.
 *
 * <p>Each key slot is also addressable by a name on the page currently showing, so addons can
 * add or remove buttons at runtime and mutate their icons without tracking key indices.</p>
 */
@SuppressWarnings("unused")
@CanIgnoreReturnValue
public final class DeckSurface {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeckSurface.class);

    private final StreamDeckManager manager;
    private final String deckId;
    private final DeckModel model;
    private final AtomicReferenceArray<DeckButton> buttons;

    public DeckSurface(StreamDeckManager manager, StreamDeckManager.DeckInfo info) {
        this(manager, info.id(), info.model());
    }

    public DeckSurface(StreamDeckManager manager, String deckId, DeckModel model) {
        this.manager = manager;
        this.deckId = deckId;
        this.model = model;
        this.buttons = new AtomicReferenceArray<>(model.keyCount());
    }

    public String deckId()   { return deckId; }
    public DeckModel model() { return model; }
    public int keyCount()    { return model.keyCount(); }

    public @Nullable DeckButton button(int key) {
        return (key < 0 || key >= buttons.length()) ? null : buttons.get(key);
    }

    /** Assigns a button and redraws that key. Pass null to blank it. */
    public void setButton(int key, DeckButton button) {
        if (key < 0 || key >= buttons.length()) throw new IllegalArgumentException("no key " + key);
        currentPage().set(key, button);
        buttons.set(key, button);
        redraw(key);
    }

    /**
     * Assigns a button by grid position. Columns past the deck's width wrap onto the next row,
     * so a layout written for a bigger deck still keeps its buttons. A position that wraps off
     * the bottom of the deck is dropped with a warning, never thrown.
     */
    public void setButton(int column, int row, DeckButton button) {
        int width = model.columns();
        if (column >= width) {
            LOGGER.warn("Stream Deck {} ({}) has no column {}; wrapping ({}, {}) onto column {} row {}",
                    deckId, model, column, column, row, column % width, row + column / width);
            row += column / width;
            column %= width;
        }
        if (column < 0 || row < 0 || row >= model.rows()) {
            LOGGER.warn("Stream Deck {} ({}) has no button at column {} row {}; dropping it", deckId, model, column, row);
            return;
        }
        setButton(model.keyIndex(column, row), button);
    }

    // ------------------------------------------------------------------
    // Named buttons
    // ------------------------------------------------------------------
    //
    // A name addresses a single key on the page currently showing. The same name may exist on
    // different pages, folders, or decks. Auto-placement skips the keys reserved for the
    // folder's back/next/previous navigation.

    /**
     * Creates a button and adds it to the current page under a name, on the first free key
     * that is not reserved for navigation. Returns the button so its icon and action can be
     * mutated at any time; mutations redraw the key automatically.
     *
     * @throws IllegalArgumentException if the page already has a button under this name
     * @throws IllegalStateException    if every key on the page is taken
     */
    public NamedButton putButton(String name, DeckImage icon, @Nullable Runnable onPress) {
        return putButton(new NamedButton(name, icon, onPress));
    }

    /**
     * Places an existing {@link NamedButton} on the current page under its own name, on the
     * first free key that is not reserved for navigation. The same instance may be placed on
     * several pages or decks; mutating it redraws wherever it currently shows.
     *
     * @throws IllegalArgumentException if the page already has a button under this name
     * @throws IllegalStateException    if every key on the page is taken
     */
    public NamedButton putButton(NamedButton button) {
        if (button == null) throw new IllegalArgumentException("a button is required");
        Page page = currentPage();
        if (page.findNamed(button.name()) != null) {
            throw new IllegalArgumentException("button \"" + button.name() + "\" already exists on this page");
        }
        int key = page.firstFreeKey();
        if (key < 0) throw new IllegalStateException("no free key on this page for \"" + button.name() + "\"");
        page.set(key, button);
        buttons.set(key, button);
        redraw(key);
        return button;
    }

    /** The button placed under this name on the current page, or null. */
    @Nullable
    public NamedButton button(String name) {
        return currentPage().findNamed(name);
    }

    /** Names of the buttons on the current page, in the order they were added. */
    public List<String> buttonNames() {
        return currentPage().namedKeys();
    }

    /**
     * Removes the button under this name from the current page and blanks its key.
     *
     * @return true if such a button existed
     */
    public boolean removeButton(String name) {
        Page page = currentPage();
        NamedButton button = page.findNamed(name);
        if (button == null) return false;
        int key = page.namedKey(name);
        page.set(key, null);
        buttons.set(key, null);
        redraw(key);
        return true;
    }

    /** Alias of {@link #removeButton(String)}. */
    public boolean clearButton(String name) { return removeButton(name); }

    /** Swaps the icon of the named button on the current page, if one exists. */
    public void setButtonIcon(String name, DeckImage icon) {
        NamedButton button = button(name);
        if (button != null) button.setIcon(icon);
    }

    /** Replaces the press action of the named button on the current page, if one exists. */
    public void setButtonAction(String name, @Nullable Runnable onPress) {
        NamedButton button = button(name);
        if (button != null) button.setAction(onPress);
    }

    public void clearButton(int key) { setButton(key, null); }

    public void clearAll() {
        Page page = currentPage();
        for (int i = 0; i < buttons.length(); i++) {
            page.set(i, null);
            buttons.set(i, null);
        }
        redrawAll();
    }

    /** Re-renders one key. Call after a button's appearance changes. */
    public void redraw(int key) {
        DeckButton button = buttons.get(key);
        manager.submit(deckId, deck -> {
            if (button == null) {
                deck.clearKey(key);
            } else {
                DeckModel.ImageSpec spec = deck.model().keyImage();
                deck.setKeyImage(key, button.render(spec.width(), spec.height()));
            }
        });
    }

    public void redrawAll() {
        for (int i = 0; i < buttons.length(); i++) redraw(i);
    }

    public void setBrightness(int percent) {
        manager.submit(deckId, deck -> deck.setBrightness(percent));
    }

    /** Fills the LCD strip on a Plus or the info screen on a Neo. No-op elsewhere. */
    public void setScreenImage(DeckImage image) {
        if (!model.hasScreen()) return;
        manager.submit(deckId, deck -> deck.setScreenImage(image));
    }

    // ------------------------------------------------------------------
    // Folders and pages
    // ------------------------------------------------------------------
    //
    // A "folder" is a level you can descend into and come back from (back() returns to
    // wherever you were, including which page of the parent was showing). A "page" is one of
    // several sibling layouts *within* the current folder that nextPage()/previousPage() cycle
    // between, without changing the folder stack. The base/root level is itself just a folder
    // with nothing above it; back() at the root does nothing and returns false.

    /**
     * One page's worth of assignments, both by key and by name. The page maps are
     * authoritative; {@link DeckSurface#buttons} only mirrors the currently showing one.
     */
    private final class Page {
        private final Map<Integer, DeckButton> keys = new HashMap<>();
        private final Map<String, NamedButton> named = new LinkedHashMap<>();
        private int nextKey;

        Page() {}

        Page(Map<Integer, DeckButton> map) {
            keys.putAll(map);
            for (DeckButton value : map.values()) {
                if (value instanceof NamedButton nb) named.put(nb.name(), nb);
            }
        }

        @Nullable DeckButton key(int index) { return keys.get(index); }

        @Nullable NamedButton findNamed(String name) { return named.get(name); }

        List<String> namedKeys() { return List.copyOf(named.keySet()); }

        int namedKey(String name) {
            NamedButton button = named.get(name);
            if (button == null) return -1;
            for (Map.Entry<Integer, DeckButton> entry : keys.entrySet()) {
                if (entry.getValue() == button) return entry.getKey();
            }
            return -1;
        }

        void set(int index, @Nullable DeckButton button) {
            DeckButton old = keys.put(index, button);
            if (old instanceof NamedButton previous) {
                named.remove(previous.name(), previous);
                previous.detach();
            }
            if (button instanceof NamedButton nb) {
                NamedButton existing = named.get(nb.name());
                if (existing != null && existing != nb) {
                    throw new IllegalArgumentException("duplicate button name \"" + nb.name() + "\" on this page");
                }
                named.put(nb.name(), nb);
                nb.attach(DeckSurface.this, index);
            }
        }

        void attachAll() {
            for (Map.Entry<Integer, DeckButton> entry : keys.entrySet()) {
                if (entry.getValue() instanceof NamedButton nb) nb.attach(DeckSurface.this, entry.getKey());
            }
        }

        Map<Integer, DeckButton> asMap() { return new HashMap<>(keys); }

        /** First key with no button, skipping navigation-reserved slots. -1 when the page is full. */
        int firstFreeKey() {
            int count = keyCount();
            for (int i = 0; i < count; i++) {
                int candidate = (nextKey + i) % count;
                if (keys.containsKey(candidate) || isReservedKey(candidate)) continue;
                nextKey = candidate + 1;
                return candidate;
            }
            return -1;
        }
    }

    private record Frame(List<Page> pages, int pageIndex) {}

    private final Deque<Frame> folderStack = new ArrayDeque<>();
    private List<Page> pages = List.of(new Page());
    private int pageIndex = 0;

    private Page currentPage() { return pages.get(pageIndex); }

    /**
     * True if the key is used by the folder system's back/next/previous navigation. The back
     * key is always off limits: every folder page gets one when the driver packages a layout,
     * even folders built from a depth-0 capture surface. The prev/next keys matter once a page
     * may have siblings.
     */
    private boolean isReservedKey(int key) {
        int backKey = keyCount() - model.columns();
        if (key == backKey) return true;
        return pageCount() > 1 && (key == backKey + 1 || key == keyCount() - 1);
    }

    /**
     * Descends into a folder with a single page. The current level (including whichever page of
     * it was showing) is remembered and restored by {@link #back()}.
     */
    public void openFolder(Map<Integer, DeckButton> page) {
        openFolder(List.of(page));
    }

    /**
     * Descends into a folder with several sibling pages, showing the first. Use
     * {@link #nextPage()} / {@link #previousPage()} to move between them once inside.
     */
    public void openFolder(List<Map<Integer, DeckButton>> newPages) {
        if (newPages == null || newPages.isEmpty()) {
            throw new IllegalArgumentException("a folder needs at least one page");
        }
        List<Page> converted = new ArrayList<>(newPages.size());
        for (Map<Integer, DeckButton> page : newPages) converted.add(new Page(page));
        folderStack.push(new Frame(pages, pageIndex));
        pages = converted;
        pageIndex = 0;
        applyCurrentPage();
    }

    /**
     * Leaves the current folder, restoring the parent level exactly as it was (including which
     * of its pages was showing). Returns false if already at the root, in which case nothing
     * happens.
     */
    public boolean back() {
        Frame parent = folderStack.poll();
        if (parent == null) return false;
        pages = parent.pages();
        pageIndex = parent.pageIndex();
        applyCurrentPage();
        return true;
    }

    /** True if {@link #back()} would do something. */
    public boolean canGoBack() { return !folderStack.isEmpty(); }

    /** How many folders deep the current level is; 0 at the root. */
    public int folderDepth() { return folderStack.size(); }

    /**
     * Moves to the next sibling page within the current folder, wrapping around. Returns false
     * (and does nothing) if the current folder only has one page.
     */
    public boolean nextPage() {
        if (pages.size() <= 1) return false;
        pageIndex = (pageIndex + 1) % pages.size();
        applyCurrentPage();
        return true;
    }

    /** Moves to the previous sibling page within the current folder, wrapping around. */
    public boolean previousPage() {
        if (pages.size() <= 1) return false;
        pageIndex = (pageIndex - 1 + pages.size()) % pages.size();
        applyCurrentPage();
        return true;
    }

    /** Jumps directly to a sibling page within the current folder by index. */
    public boolean goToPage(int index) {
        if (index < 0 || index >= pages.size()) return false;
        pageIndex = index;
        applyCurrentPage();
        return true;
    }

    /**
     * Appends a new, initially empty page to the current folder and switches to it. Useful when
     * building a folder's content up front, e.g. inside {@link DeckLayout#populate}, rather than
     * reacting to {@link #nextPage()} calls at runtime.
     */
    public void addPage() {
        List<Page> updated = new ArrayList<>(pages);
        updated.add(new Page());
        pages = updated;
        pageIndex = pages.size() - 1;
        applyCurrentPage();
    }

    /**
     * A snapshot of every page in the current folder. Used to pull a folder's content back out
     * after a {@link DeckLayout#populate} call finishes.
     */
    public List<Map<Integer, DeckButton>> exportPages() {
        List<Map<Integer, DeckButton>> out = new ArrayList<>(pages.size());
        for (Page page : pages) out.add(page.asMap());
        return out;
    }

    /** Number of sibling pages in the current folder. Always at least 1. */
    public int pageCount() { return pages.size(); }

    /** Index of the page currently showing within the current folder. */
    public int currentPageIndex() { return pageIndex; }

    /**
     * Replaces the base level's pages outright and clears the whole folder stack; there is
     * nothing to {@link #back()} to after this. Use to (re)define what the deck shows with
     * nothing open, e.g. from a layout's {@link DeckLayout#populate}.
     */
    public void setRootPages(List<Map<Integer, DeckButton>> rootPages) {
        folderStack.clear();
        List<Page> converted = new ArrayList<>();
        if (rootPages != null && !rootPages.isEmpty()) {
            for (Map<Integer, DeckButton> page : rootPages) converted.add(new Page(page));
        } else {
            converted.add(new Page());
        }
        pages = converted;
        pageIndex = 0;
        applyCurrentPage();
    }

    public void setRootPage(Map<Integer, DeckButton> rootPage) {
        setRootPages(List.of(rootPage));
    }

    private void applyCurrentPage() {
        Page page = pages.get(pageIndex);
        page.attachAll();
        for (int i = 0; i < buttons.length(); i++) buttons.set(i, page.key(i));
        redrawAll();
    }

    /**
     * @deprecated use {@link #openFolder(Map)}, which does the same thing under a clearer name
     *             now that folders can also have multiple pages.
     */
    @Deprecated
    public void pushPage(Map<Integer, DeckButton> page) { openFolder(page); }

    /** @deprecated use {@link #back()}. */
    @Deprecated
    public boolean popPage() { return back(); }

    /** @deprecated use {@link #folderDepth()}. */
    @Deprecated
    public int pageDepth() { return folderDepth(); }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * Routes an event to the assigned button. Feed everything from
     * {@link StreamDeckManager#drainEvents} through here.
     *
     * @return true if this surface handled the event
     */
    public boolean handle(DeckEvent event) {
        if (!deckId.equals(event.deckId())) return false;

        switch (event) {
            case DeckEvent.KeyDown down -> {
                DeckButton button = button(down.key());
                if (button == null) return false;
                button.onDown(this, down.key());
                return true;
            }
            case DeckEvent.KeyUp up -> {
                DeckButton button = button(up.key());
                if (button == null) return false;
                button.onUp(this, up.key());
                return true;
            }
            case DeckEvent.Connected ignored -> {
                redrawAll();
                return true;
            }
            default -> {
            }
        }
        return false;
    }
}