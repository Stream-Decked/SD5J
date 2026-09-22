package dev.wolfieboy09.sd5j.core;

import dev.wolfieboy09.sd5j.core.image.DeckImage;

import java.util.List;
import java.util.Map;

/**
 * One key's worth of behavior: what it looks like and what happens when it is pressed.
 *
 * <p>{@link #render} runs on the driver thread, so it must not touch game state directly.
 * {@link #onDown} and {@link #onUp} run wherever {@link StreamDeckManager#drainEvents} is
 * called, which for a mod should be the client tick, so they may touch game state freely.</p>
 */
@SuppressWarnings("unused")
public interface DeckButton {
    /**
     * Produces the key image at the panel's native size. Anything else gets scaled to fit.
     * Called on the driver thread whenever the button is (re)drawn, not every frame.
     */
    DeckImage render(int width, int height);

    default void onDown(DeckSurface surface, int key) {}

    default void onUp(DeckSurface surface, int key) {}

    /** Fixed image, action on press. */
    static DeckButton of(DeckImage image, Runnable onPress) {
        return new DeckButton() {
            @Override public DeckImage render(int width, int height) {
                return image.fitInto(width, height, 0xFF000000);
            }
            @Override public void onDown(DeckSurface surface, int key) {
                if (onPress != null) onPress.run();
            }
        };
    }

    /** Text label on a solid background. */
    static DeckButton text(String label, int textArgb, int backgroundArgb, Runnable onPress) {
        return new DeckButton() {
            @Override public DeckImage render(int width, int height) {
                return DeckText.label(width, height, label, textArgb, backgroundArgb);
            }
            @Override public void onDown(DeckSurface surface, int key) {
                if (onPress != null) onPress.run();
            }
        };
    }

    /** Icon with a caption strip along the bottom. */
    static DeckButton labelled(DeckImage icon, String label, Runnable onPress) {
        return new DeckButton() {
            @Override public DeckImage render(int width, int height) {
                DeckImage out = DeckImage.black(width, height);
                int captionHeight = Math.max(14, height / 4);
                out.draw(icon.fitInto(width, height - captionHeight, 0xFF000000), 0, 0);
                DeckText.drawWrapped(out, label, DeckText.DEFAULT_FONT.deriveFont((float) captionHeight - 2),
                        0xFFFFFFFF, 2, height - captionHeight, width, captionHeight);
                return out;
            }
            @Override public void onDown(DeckSurface surface, int key) {
                if (onPress != null) onPress.run();
            }
        };
    }

    // ------------------------------------------------------------------
    // Navigation: folders and pages, mirroring DeckSurface's folder/page model
    // ------------------------------------------------------------------

    /** Descends into a single-page folder on press. See {@link DeckSurface#openFolder(Map)}. */
    static DeckButton folder(DeckImage icon, Map<Integer, DeckButton> page) {
        return folder(icon, List.of(page));
    }

    /** Descends into a multipage folder on press. See {@link DeckSurface#openFolder(List)}. */
    static DeckButton folder(DeckImage icon, List<Map<Integer, DeckButton>> pages) {
        return new DeckButton() {
            @Override public DeckImage render(int width, int height) {
                return icon.pixelFitInto(width, height, 0xFF000000);
            }
            @Override public void onDown(DeckSurface surface, int key) {
                surface.openFolder(pages);
            }
        };
    }

    /** Text-label variant of {@link #folder(DeckImage, Map)}. */
    static DeckButton folder(String label, int textArgb, int backgroundArgb, Map<Integer, DeckButton> page) {
        return folder(label, textArgb, backgroundArgb, List.of(page));
    }

    /** Text-label variant of {@link #folder(DeckImage, List)}. */
    static DeckButton folder(String label, int textArgb, int backgroundArgb,
                             List<Map<Integer, DeckButton>> pages) {
        return new DeckButton() {
            @Override public DeckImage render(int width, int height) {
                return DeckText.label(width, height, label, textArgb, backgroundArgb);
            }
            @Override public void onDown(DeckSurface surface, int key) {
                surface.openFolder(pages);
            }
        };
    }

    /** Leaves the current folder on press. See {@link DeckSurface#back()}. */
    static DeckButton back(DeckImage icon) {
        return new DeckButton() {
            @Override public DeckImage render(int width, int height) {
                return icon.pixelFitInto(width, height, 0xFF000000);
            }
            @Override public void onDown(DeckSurface surface, int key) {
                surface.back();
            }
        };
    }

    /** Text-label variant of {@link #back(DeckImage)}. */
    static DeckButton back(String label, int textArgb, int backgroundArgb) {
        return new DeckButton() {
            @Override public DeckImage render(int width, int height) {
                return DeckText.label(width, height, label, textArgb, backgroundArgb);
            }
            @Override public void onDown(DeckSurface surface, int key) {
                surface.back();
            }
        };
    }

    /** Cycles to the next sibling page on press. See {@link DeckSurface#nextPage()}. */
    static DeckButton nextPage(DeckImage icon) {
        return new DeckButton() {
            @Override public DeckImage render(int width, int height) {
                return icon.pixelFitInto(width, height, 0xFF000000);
            }
            @Override public void onDown(DeckSurface surface, int key) {
                surface.nextPage();
            }
        };
    }

    /** Text-label variant of {@link #nextPage(DeckImage)}. */
    static DeckButton nextPage(String label, int textArgb, int backgroundArgb) {
        return new DeckButton() {
            @Override public DeckImage render(int width, int height) {
                return DeckText.label(width, height, label, textArgb, backgroundArgb);
            }
            @Override public void onDown(DeckSurface surface, int key) {
                surface.nextPage();
            }
        };
    }

    /** Cycles to the previous sibling page on press. See {@link DeckSurface#previousPage()}. */
    static DeckButton previousPage(DeckImage icon) {
        return new DeckButton() {
            @Override public DeckImage render(int width, int height) {
                return icon.pixelFitInto(width, height, 0xFF000000);
            }
            @Override public void onDown(DeckSurface surface, int key) {
                surface.previousPage();
            }
        };
    }

    /** Text-label variant of {@link #previousPage(DeckImage)}. */
    static DeckButton previousPage(String label, int textArgb, int backgroundArgb) {
        return new DeckButton() {
            @Override public DeckImage render(int width, int height) {
                return DeckText.label(width, height, label, textArgb, backgroundArgb);
            }
            @Override public void onDown(DeckSurface surface, int key) {
                surface.previousPage();
            }
        };
    }
}