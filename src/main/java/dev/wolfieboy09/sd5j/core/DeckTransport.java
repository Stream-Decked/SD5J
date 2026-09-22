package dev.wolfieboy09.sd5j.core;

import org.jetbrains.annotations.Nullable;

/**
 * What the deck driver talks through. Ripping out direct HID ownership replaced every
 * hardware-facing call on {@link StreamDeck} (draw a key, set the brightness, reset, read
 * input) with this seam; the shipped implementation, {@code RemoteDeckTransport}, drives a
 * real deck through the Stream Deck app over a local WebSocket instead of claiming the device.
 *
 * <p>A {@link DeckTransport} represents the connection to the controlling app session, which is bound to
 * at most one deck ({@link #boundDeckId()} / {@link #boundModel()}). Decks are assigned and
 * recalled by the plugin, then reported here as {@link DeckEvent.Connected} and
 * {@link DeckEvent.Disconnected}; input arrives the same way, as pushed {@link DeckEvent}s.</p>
 */
public interface DeckTransport extends AutoCloseable {

    /** Puts the transport on the wire. Safe to call once; later calls are ignored. */
    void connect();

    /** True once the plugin has accepted this client's hello handshake. */
    boolean isConnected();

    /** The deck this client is bound to, or null while queued or disconnected. */
    @Nullable String boundDeckId();

    /** The model the plugin reported for the bound deck, or null while there is none. */
    @Nullable DeckModel boundModel();

    /**
     * Uploads an already-encoded key image ({@code DeckImageCodec.encodeKey} output, which the
     * plugin forwards straight to the app's setImage). A no-op while disconnected.
     */
    void setKeyImage(int key, byte[] encoded);

    /**
     * Uploads an already-encoded screen strip image. Currently unused by the wire protocol;
     * the app owns screen drawing. Kept so the seam stays total. A no-op while disconnected.
     */
    void setScreenImage(byte[] encoded);

    /** Sets the app-side brightness, 0 to 100. Currently unused by the wire protocol. */
    void setBrightness(int percent);

    /** Returns the deck to its idle state. Currently unused by the wire protocol. */
    void reset();

    /** Connects the transport's event stream to a listener, or detaches it with null. */
    void setListener(@Nullable Listener listener);

    @Override
    void close();

    boolean isClosed();

    /** Receives the frames the transport turns into deck events. */
    interface Listener {
        void onEvent(DeckEvent event);

        void onError(Throwable throwable);
    }
}