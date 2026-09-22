package dev.wolfieboy09.sd5j.core;

/**
 * A decoded input report, straight off the wire. These are absolute states, not changes;
 * {@link StreamDeckManager} diffs them into {@link DeckEvent}s.
 */
@SuppressWarnings("unused")
public sealed interface DeckInput {
    /** Read timed out with nothing to report. */
    record None() implements DeckInput {}

    /**
     * Current pressed state of every button, ordered by logical key index. On a Neo the two
     * capacitive touch points follow the eight physical keys.
     */
    record Buttons(boolean[] states) implements DeckInput {}

    /** Current pressed state of each rotary encoder (Stream Deck +). */
    record EncoderPress(boolean[] states) implements DeckInput {}

    /** Detents turned since the last report, per encoder. Negative is counter-clockwise. */
    record EncoderTurn(int[] deltas) implements DeckInput {}

    /** Short tap on the touch strip. */
    record TouchTap(int x, int y) implements DeckInput {}

    /** Press and hold on the touch strip. */
    record TouchHold(int x, int y) implements DeckInput {}

    /** Drag across the touch strip. */
    record TouchSwipe(int fromX, int fromY, int toX, int toY) implements DeckInput {}

    DeckInput NONE = new None();
}