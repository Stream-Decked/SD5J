package dev.wolfieboy09.sd5j.core;

import dev.wolfieboy09.sd5j.core.image.DeckImage;
import dev.wolfieboy09.sd5j.core.image.DeckImageCodec;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * A single bound deck session.
 *
 * <p>Thin over a {@link DeckTransport}: every hardware-facing call (draw a key, set the
 * brightness, reset) is delegated to the transport, which in this project is the remote
 * WebSocket client. Encoding still happens here and on the caller's thread; in a game you
 * almost certainly want {@link StreamDeckManager} instead, which keeps this object off the
 * render thread.</p>
 *
 * <p>Not thread safe.</p>
 */
@SuppressWarnings("unused")
@NotThreadSafe
public final class StreamDeck implements AutoCloseable {
    private final DeckModel model;
    private final DeckTransport transport;
    private final String id;
    private boolean closed;

    public StreamDeck(DeckModel model, DeckTransport transport, String id) {
        this.model = model;
        this.transport = transport;
        this.id = id;
    }

    /** Stable identifier, the deck id the plugin assigned this client. */
    public String id()       { return id; }
    public DeckModel model() { return model; }
    public boolean isClosed(){ return closed; }

    // ------------------------------------------------------------------
    // Device control
    // ------------------------------------------------------------------

    /** Returns the deck to its idle state. */
    public void reset() {
        ensureOpen();
        transport.reset();
    }

    /** @param percent 0 to 100 */
    public void setBrightness(int percent) {
        ensureOpen();
        transport.setBrightness(Math.clamp(percent, 0, 100));
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /** Scales, reorients, encodes and uploads an image to one key. */
    public void setKeyImage(int key, DeckImage image) {
        setKeyImageEncoded(key, DeckImageCodec.encodeKey(model, image));
    }

    /**
     * Uploads bytes already produced by {@link DeckImageCodec#encodeKey}. Worth using when the
     * same image goes to several keys or is redrawn often: encoding is the expensive half.
     */
    public void setKeyImageEncoded(int key, byte[] encoded) {
        ensureOpen();
        if (!model.hasKeyScreens()) throw new UnsupportedOperationException(model + " has no key screens");
        if (key < 0 || key >= model.keyCount()) throw new IllegalArgumentException("no key " + key);
        transport.setKeyImage(key, encoded);
    }

    public void clearKey(int key) {
        setKeyImageEncoded(key, DeckImageCodec.blankKey(model));
    }

    public void clearAllKeys() {
        if (!model.hasKeyScreens()) return;
        byte[] blank = DeckImageCodec.blankKey(model);
        for (int i = 0; i < model.keyCount(); i++) setKeyImageEncoded(i, blank);
    }

    /** Fills the whole LCD strip / info screen. */
    public void setScreenImage(DeckImage image) {
        setScreenImageEncoded(DeckImageCodec.encodeScreen(model, image));
    }

    public void setScreenImageEncoded(byte[] encoded) {
        ensureOpen();
        if (model.screenImage() == null) throw new UnsupportedOperationException(model + " has no screen");
        transport.setScreenImage(encoded);
    }

    /**
     * Marks the session closed. The underlying {@link DeckTransport} stays: one connection may
     * be rebound to a fresh deck, so its lifecycle belongs to {@link StreamDeckManager}.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("deck " + id + " is closed");
    }
}