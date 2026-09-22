package dev.wolfieboy09.sd5j.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Owns the driver thread. JPEG encoding is not free, so none of it runs on the game thread:
 * hand in work with {@link #submit} and collect input with {@link #drainEvents}, both safe
 * from any thread.
 *
 * <p>Decks are not scanned here anymore. The {@link DeckTransport} does the hardware-facing
 * work: it connects to the server, reports decks as they come and go, and pushes input back as
 * {@link DeckEvent}s on whatever thread it likes. Every transport event is routed through the
 * same {@link #drainEvents} path as before, so listeners and layout logic are unchanged.</p>
 *
 * <pre>{@code
 * StreamDeckManager manager = new StreamDeckManager(new RemoteDeckTransport());
 * manager.setDefaultBrightness(70);
 * manager.start();
 *
 * manager.submitAll(deck -> deck.setKeyImage(0, icon));
 *
 * // once per client tick:
 * manager.drainEvents(event -> { ... });
 * }</pre>
 */
@SuppressWarnings("unused")
public final class StreamDeckManager implements AutoCloseable {
    public static final Logger LOGGER = LoggerFactory.getLogger(StreamDeckManager.class);

    /** Work to run against one open deck, on the driver thread. */
    @FunctionalInterface
    public interface DeckTask {
        void run(StreamDeck deck) throws Exception;
    }

    /** Identity of a connected deck, safe to hold onto from any thread. */
    public record DeckInfo(String id, DeckModel model) {}

    private final DeckTransport transport;
    private final Queue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final Queue<DeckEvent> events = new ConcurrentLinkedQueue<>();
    private final List<Consumer<DeckEvent>> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Throwable>> errorHandlers = new CopyOnWriteArrayList<>();

    /** Deck map. Guarded by its own monitor because transport events and the driver thread touch it. */
    private final Map<String, StreamDeck> decks = new LinkedHashMap<>();

    private volatile List<DeckInfo> connected = List.of();
    private volatile Thread thread;
    private volatile boolean running;
    private volatile int defaultBrightness = 80;
    private volatile boolean resetOnConnect = true;

    public StreamDeckManager(DeckTransport transport) {
        this.transport = transport;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public synchronized void start() {
        if (running) return;
        running = true;
        transport.setListener(new DeckTransport.Listener() {
            @Override
            public void onEvent(DeckEvent event) { onTransportEvent(event); }
            @Override
            public void onError(Throwable throwable) { reportError(throwable); }
        });
        transport.connect();
        Thread t = new Thread(this::runLoop, "stream-deck-driver");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    @Override
    public synchronized void close() {
        if (!running) return;
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try { t.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        thread = null;
    }

    public boolean isRunning() { return running; }

    /** Brightness applied to decks as they connect, 0 to 100. */
    public void setDefaultBrightness(int percent) { this.defaultBrightness = percent; }

    /** Whether a newly connected deck is reset and blanked. On by default. */
    public void setResetOnConnect(boolean reset) { this.resetOnConnect = reset; }

    // ------------------------------------------------------------------
    // Public API, callable from any thread
    // ------------------------------------------------------------------

    /** Decks currently open, newest last. */
    public List<DeckInfo> connectedDecks() { return connected; }

    public boolean hasDeck() { return !connected.isEmpty(); }

    /** Queues work against one deck. Silently dropped if that deck is gone by the time it runs. */
    public void submit(String deckId, DeckTask task) {
        commands.add(() -> {
            StreamDeck deck;
            synchronized (decks) { deck = decks.get(deckId); }
            if (deck != null) runTask(deck, task);
        });
    }

    /** Queues work against every connected deck. */
    public void submitAll(DeckTask task) {
        commands.add(() -> {
            List<StreamDeck> snapshot;
            synchronized (decks) { snapshot = new ArrayList<>(decks.values()); }
            for (StreamDeck deck : snapshot) runTask(deck, task);
        });
    }

    /**
     * Returns every connected deck's display to its idle logo, without touching button
     * assignments or the driver thread itself.
     */
    public void resetAllDecks() {
        submitAll(StreamDeck::reset);
    }

    /** Queues work that needs no deck, run in order with the rest. */
    public void submit(Runnable work) {
        commands.add(work);
    }

    /**
     * Hands every queued event to {@code consumer} on the calling thread and clears the queue.
     * Registered listeners fire too. Call this once per client tick.
     */
    public void drainEvents(Consumer<DeckEvent> consumer) {
        DeckEvent event;
        while ((event = events.poll()) != null) {
            if (consumer != null) {
                try { consumer.accept(event); } catch (Throwable t) { reportError(t); }
            }
            for (Consumer<DeckEvent> listener : listeners) {
                try { listener.accept(event); } catch (Throwable t) { reportError(t); }
            }
        }
    }

    public void drainEvents() { drainEvents(null); }

    /** Listeners fire on whichever thread calls {@link #drainEvents}. */
    public void addListener(Consumer<DeckEvent> listener) { listeners.add(listener); }

    public void removeListener(Consumer<DeckEvent> listener) { listeners.remove(listener); }

    /** Driver thread failures are routed here instead of killing the thread. */
    public void addErrorHandler(Consumer<Throwable> handler) { errorHandlers.add(handler); }

    // ------------------------------------------------------------------
    // Transport -> event routing
    // ------------------------------------------------------------------

    private void onTransportEvent(DeckEvent event) {
        if (event instanceof DeckEvent.Connected connected) {
            onConnected(connected);
            return;
        }
        if (event instanceof DeckEvent.Disconnected disconnected) {
            synchronized (decks) {
                decks.remove(disconnected.deckId());
            }
            refreshConnectedList();
            events.add(disconnected);
            return;
        }
        events.add(event);
    }

    private void onConnected(DeckEvent.Connected connected) {
        String id = connected.deckId();
        StreamDeck deck;
        synchronized (decks) {
            if (decks.containsKey(id)) return;
            deck = new StreamDeck(connected.model(), transport, id);
            decks.put(id, deck);
        }
        if (resetOnConnect) {
            try {
                deck.reset();
                deck.setBrightness(defaultBrightness);
                deck.clearAllKeys();
            } catch (Throwable t) {
                reportError(t);
            }
        }
        refreshConnectedList();
        events.add(connected);
    }

    private void refreshConnectedList() {
        List<DeckInfo> list = new ArrayList<>(decks.size());
        synchronized (decks) {
            for (Map.Entry<String, StreamDeck> e : decks.entrySet()) {
                list.add(new DeckInfo(e.getKey(), e.getValue().model()));
            }
        }
        connected = List.copyOf(list);
    }

    // ------------------------------------------------------------------
    // Driver thread
    // ------------------------------------------------------------------

    private void runLoop() {
        try { DeckText.warmup(); } catch (Throwable t) { LOGGER.debug("AWT warm-up failed", t); }
        try {
            while (running) {
                Runnable command;
                while ((command = commands.poll()) != null) {
                    try { command.run(); } catch (Throwable t) { reportError(t); }
                }
                sleep(10);
            }
        } finally {
            synchronized (decks) {
                for (StreamDeck deck : new ArrayList<>(decks.values())) {
                    try { deck.reset(); } catch (Throwable ignored) { }
                    try { deck.close(); } catch (Throwable ignored) { }
                }
                decks.clear();
            }
            connected = List.of();
            commands.clear();
            try { transport.close(); } catch (Throwable ignored) { }
        }
    }

    private void runTask(StreamDeck deck, DeckTask task) {
        try {
            task.run(deck);
        } catch (Throwable t) {
            reportError(t);
        }
    }

    private void reportError(Throwable t) {
        if (errorHandlers.isEmpty()) {
            LOGGER.error("Stream Deck driver error", t);
            return;
        }
        for (Consumer<Throwable> handler : errorHandlers) {
            try { handler.accept(t); } catch (Throwable suppressed) {
                LOGGER.error("Stream Deck error handler threw", suppressed);
            }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}