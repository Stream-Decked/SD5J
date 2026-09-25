package dev.wolfieboy09.sd5j.core;

import org.jetbrains.annotations.Nullable;

/**
 * The seam the deck driver talks through; the shipped implementation, {@code RemoteDeckTransport},
 * drives a real deck through the Stream Deck app over a local WebSocket. A transport is bound to
 * at most one deck at a time ({@link #boundDeckId()} / {@link #boundModel()}), reported as
 * {@link DeckEvent.Connected} and {@link DeckEvent.Disconnected}.
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

    /**
     * Asks the host app to leave Modspace, restoring the deck's previous profile. A no-op while
     * disconnected. The shipped transport sends an {@code exit} frame the plugin answers by
     * switching the deck back.
     */
    void exit();

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