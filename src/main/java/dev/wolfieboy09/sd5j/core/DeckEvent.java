package dev.wolfieboy09.sd5j.core;

/**
 * What a mod actually listens for: edges rather than states.
 *
 * <p>Every event carries the id of the deck it came from ({@code deckId}, the device serial
 * where the OS gives us one) so a single listener can serve several decks.</p>
 */
@SuppressWarnings("unused")
public sealed interface DeckEvent {
    String deckId();
    DeckModel model();

    /** A deck was plugged in and is ready for drawing. */
    record Connected(String deckId, DeckModel model) implements DeckEvent {}

    /** A deck went away. Any images queued for it are dropped. */
    record Disconnected(String deckId, DeckModel model) implements DeckEvent {}

    /** @param key logical key index, row major from the top left */
    record KeyDown(String deckId, DeckModel model, int key) implements DeckEvent {}

    record KeyUp(String deckId, DeckModel model, int key) implements DeckEvent {}

    /** Capacitive touch point on a Neo, indexed from 0 (left of the keys). */
    record TouchPointDown(String deckId, DeckModel model, int point) implements DeckEvent {}

    record TouchPointUp(String deckId, DeckModel model, int point) implements DeckEvent {}

    /** Rotary encoder pressed in (Stream Deck +). */
    record EncoderDown(String deckId, DeckModel model, int encoder) implements DeckEvent {}

    record EncoderUp(String deckId, DeckModel model, int encoder) implements DeckEvent {}

    /** @param delta detents since the last event; negative is counter-clockwise */
    record EncoderTurn(String deckId, DeckModel model, int encoder, int delta) implements DeckEvent {}

    /** Coordinates are in touch strip pixels, origin top left. */
    record ScreenTap(String deckId, DeckModel model, int x, int y) implements DeckEvent {}

    record ScreenHold(String deckId, DeckModel model, int x, int y) implements DeckEvent {}

    record ScreenSwipe(String deckId, DeckModel model, int fromX, int fromY, int toX, int toY) implements DeckEvent {}
}