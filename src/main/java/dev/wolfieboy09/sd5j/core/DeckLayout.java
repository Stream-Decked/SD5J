package dev.wolfieboy09.sd5j.core;

/**
 * An addon's contribution to a deck: put buttons on a freshly connected panel.
 *
 * <p>Registered by the embedding application (the Minecraft mod does this via its plugin
 * registry) and applied on every deck connect. Registrations under the same id namespace share
 * one auto-generated folder; {@link #populate} runs against that folder's content, using
 * {@link DeckSurface#setButton} and {@link DeckSurface#addPage} for more pages. Avoid the keys
 * reserved for the folder's back/next/previous buttons (see {@link DeckSurface}). Runs on the
 * client thread.</p>
 */
@FunctionalInterface
public interface DeckLayout {
    /** Assign buttons here. Called once per connected deck. */
    void populate(DeckSurface surface);

    /**
     * Lets a layout opt out of a particular panel, e.g. a layout that needs 32 keys or the
     * touch strip.
     */
    default boolean appliesTo(DeckSurface surface) {
        return true;
    }
}