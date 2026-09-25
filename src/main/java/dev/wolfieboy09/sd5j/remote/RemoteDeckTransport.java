package dev.wolfieboy09.sd5j.remote;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.wolfieboy09.sd5j.core.DeckEvent;
import dev.wolfieboy09.sd5j.core.DeckModel;
import dev.wolfieboy09.sd5j.core.DeckTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link DeckTransport} backed by the Stream Deck app bridge: a WebSocket client to the local
 * plugin server. Connection facts come from the pairing file the plugin writes
 * ({@link #DEFAULT_PAIRING_FILE}), re-read on every attempt so a plugin restart (new port and
 * token) is picked up automatically.
 */
@SuppressWarnings("unused")
public final class RemoteDeckTransport implements DeckTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteDeckTransport.class);
    private static final Gson GSON = new Gson();

    public static final String DEFAULT_PAIRING_FILE =
            Path.of(System.getProperty("user.home"), ".streamdecked", "pairing.json").toString();
    private static final String DEFAULT_CLIENT_NAME = "minecraft";
    private static final String LIB_VERSION = "1.1.0";

    private static final long CONNECT_TIMEOUT_MS = 10_000;
    private static final long MIN_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 15_000;

    private final String clientName;
    private final Path pairingFile;
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Stops the reconnect loop independent of a dead connection. */
    private final CountDownLatch stop = new CountDownLatch(1);

    private volatile DeckTransport.Listener listener;
    private volatile String boundDeckId;
    private volatile DeckModel boundModel;
    private volatile Thread thread;

    /** Page-0 surface kept in sync by {@link #setKeyImage}, re-uploaded on {@code surface {request:true}}. */
    private final ConcurrentHashMap<Integer, String> painted = new ConcurrentHashMap<>();

    public RemoteDeckTransport() {
        this(DEFAULT_PAIRING_FILE, DEFAULT_CLIENT_NAME);
    }

    public RemoteDeckTransport(String clientName) {
        this(DEFAULT_PAIRING_FILE, clientName);
    }

    public RemoteDeckTransport(String pairingFile, String clientName) {
        this.pairingFile = Path.of(pairingFile);
        this.clientName = clientName;
    }

    public String clientName() { return clientName; }
    public Path pairingFile() { return pairingFile; }

    // ------------------------------------------------------------------
    // DeckTransport
    // ------------------------------------------------------------------

    @Override
    public synchronized void connect() {
        if (closed.get() || thread != null) return;
        Thread t = new Thread(this::run, "stream-deck-remote");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    @Override
    public boolean isConnected() {
        return connected.get() && socket.get() != null && !closed.get();
    }

    @Override
    public String boundDeckId() { return boundDeckId; }

    @Override
    public DeckModel boundModel() { return boundModel; }

    @Override
    public void setKeyImage(int key, byte[] encoded) {
        String imageBase64 = Base64.getEncoder().encodeToString(encoded);
        painted.put(key, imageBase64);
        WebSocket ws = socket.get();
        if (ws == null || !connected.get()) {
            LOGGER.debug("dropping setImage for key {} while disconnected", key);
            return;
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "setImage");
        frame.addProperty("key", key);
        frame.addProperty("page", 0);
        frame.addProperty("imageBase64", imageBase64);
        ws.sendText(GSON.toJson(frame), true);
    }

    /**
     * Screen images are not yet defined by the wire protocol; the app owns screen drawing.
     */
    @Override
    public void setScreenImage(byte[] encoded) {
        LOGGER.debug("screen image upload waiting on protocol refinement ({} bytes dropped)", encoded.length);
    }

    /** Brightness is app-owned; there is no brightness frame yet. */
    @Override
    public void setBrightness(int percent) {
        LOGGER.debug("brightness is handled by the app; ignoring setBrightness({})", percent);
    }

    /** Device reset is app-owned; there is no reset frame yet. */
    @Override
    public void reset() {
        LOGGER.debug("device reset is handled by the app; ignoring reset()");
    }

    @Override
    public void exit() {
        WebSocket ws = socket.get();
        if (ws == null || !connected.get()) {
            LOGGER.debug("dropping exit while disconnected");
            return;
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "exit");
        ws.sendText(GSON.toJson(frame), true);
        LOGGER.info("asked the plugin to leave Modspace and restore the previous profile");
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public boolean isClosed() { return closed.get(); }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            WebSocket ws = socket.getAndSet(null);
            if (ws != null) {
                try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "client shutting down"); }
                catch (Throwable ignored) { ws.abort(); }
            }
            stop.countDown();
            Thread t = thread;
            if (t != null) t.interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Connection loop
    // ------------------------------------------------------------------

    private void run() {
        HttpClient client = HttpClient.newHttpClient();
        long backoff = MIN_BACKOFF_MS;
        while (!closed.get()) {
            Pairing pairing = readPairing();
            if (pairing == null) {
                LOGGER.info("No Stream Deck server found (no pairing file at {})", pairingFile);
                backoff = sleep(backoff);
                continue;
            }
            URI uri = URI.create("ws://127.0.0.1:" + pairing.port());
            CountDownLatch gone = new CountDownLatch(1);
            try {
                WebSocket ws = client.newWebSocketBuilder()
                        .buildAsync(uri, new Frames(pairing, gone))
                        .get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                socket.set(ws);
                gone.await();
            } catch (Exception e) {
                LOGGER.info("No Stream Deck server found on ws://127.0.0.1:{} ({})",
                        pairing.port() + "", rootMessage(e));
            } finally {
                socket.set(null);
                setConnected(false);
            }
            if (closed.get()) return;
            backoff = sleep(backoff);
        }
    }

    private void handleFrame(String raw) {
        JsonObject frame;
        try {
            frame = JsonParser.parseString(raw).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            LOGGER.debug("dropping unparseable frame: {}", e.getMessage());
            return;
        }

        switch (string(frame, "type")) {
            case "helloOk" -> handleHelloOk(frame);
            case "deckConnect" -> handleDeckConnect(frame);
            case "deckDisconnect" -> handleDeckDisconnect(frame);
            case "keyDown" -> emitKey(true, frame);
            case "keyUp" -> emitKey(false, frame);
            case "surface" -> handleSurfaceRequest(frame);
            case "error" -> LOGGER.warn("plugin reported an error: {}", string(frame, "message"));
            case null, default -> LOGGER.debug("ignoring unknown frame type: {}", string(frame, "type"));
        }
    }

    private void handleHelloOk(JsonObject frame) {
        setConnected(true);
        JsonArray decks = frame.has("decks") && frame.get("decks").isJsonArray()
                ? frame.getAsJsonArray("decks") : null;
        String assigned = string(frame, "deckId");

        JsonObject mine = null;
        if (decks != null) {
            for (JsonElement element : decks) {
                if (!element.isJsonObject()) continue;
                JsonObject deck = element.getAsJsonObject();
                if (assigned != null && assigned.equals(string(deck, "deckId"))) mine = deck;
            }
            if (mine == null && !decks.isEmpty() && decks.get(0).isJsonObject()) mine = decks.get(0).getAsJsonObject();
        }
        if (mine == null) {
            LOGGER.debug("helloOk gave no deck; the client is queued");
            return;
        }
        bind(new DeckEvent.Connected(string(mine, "deckId"), modelOf(mine)));
    }

    private void handleDeckConnect(JsonObject frame) {
        setConnected(true);
        bind(new DeckEvent.Connected(string(frame, "deckId"), modelOf(frame)));
    }

    private void handleDeckDisconnect(JsonObject frame) {
        String deckId = string(frame, "deckId");
        if (deckId == null || !deckId.equals(boundDeckId)) return;
        DeckModel was = boundModel;
        boundDeckId = null;
        boundModel = null;
        emit(new DeckEvent.Disconnected(deckId, was));
    }

    private void handleSurfaceRequest(JsonObject frame) {
        if (!bool(frame, "request")) return;
        JsonObject reply = new JsonObject();
        reply.addProperty("type", "surface");
        reply.addProperty("activePage", 0);
        JsonArray pages = new JsonArray();
        JsonObject page = new JsonObject();
        page.addProperty("page", 0);
        JsonArray buttons = new JsonArray();
        for (Map.Entry<Integer, String> entry : painted.entrySet()) {
            JsonObject button = new JsonObject();
            button.addProperty("key", entry.getKey());
            button.addProperty("page", 0);
            button.addProperty("imageBase64", entry.getValue());
            buttons.add(button);
        }
        page.add("buttons", buttons);
        pages.add(page);
        reply.add("pages", pages);
        WebSocket ws = socket.get();
        if (ws != null) ws.sendText(GSON.toJson(reply), true);
    }

    private void bind(DeckEvent.Connected connected) {
        if (connected.model() == null) {
            LOGGER.debug("ignoring deck \"{}\": unknown model", connected.deckId());
            return;
        }
        boundDeckId = connected.deckId();
        boundModel = connected.model();
        emit(connected);
    }

    private void emitKey(boolean down, JsonObject frame) {
        String deckId = string(frame, "deckId");
        if (deckId == null) deckId = boundDeckId;
        if (deckId == null || boundModel == null) return;
        int key = integer(frame, "key");
        if (down) {
            emit(new DeckEvent.KeyDown(deckId, boundModel, key));
        } else {
            emit(new DeckEvent.KeyUp(deckId, boundModel, key));
        }
    }

    private DeckModel modelOf(JsonObject object) {
        DeckModel canonical = DeckModel.fromDisplayName(string(object, "model"));
        int columns = integer(object, "columns");
        int rows = integer(object, "rows");
        if (bool(object, "generic")
                || (columns > 0 && rows > 0 && canonical != null && canonical.keyCount() != columns * rows)) {
            return DeckModel.generic(string(object, "model"), columns, rows);
        }
        return canonical;
    }

    private void emit(DeckEvent event) {
        DeckTransport.Listener l = listener;
        if (l != null) {
            try { l.onEvent(event); } catch (Throwable t) { LOGGER.error("event listener threw", t); }
        }
    }

    private void setConnected(boolean value) { connected.set(value); }

    private Pairing readPairing() {
        try {
            if (!Files.isReadable(pairingFile)) return null;
            JsonObject m = JsonParser.parseString(Files.readString(pairingFile)).getAsJsonObject();
            int port = integer(m, "port");
            String token = string(m, "token");
            if (port <= 0 || token == null || token.isEmpty()) return null;
            return new Pairing(port, token);
        } catch (IOException | JsonSyntaxException | IllegalStateException e) {
            LOGGER.debug("could not read pairing file", e);
            return null;
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    private static int integer(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsInt() : 0;
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                ? element.getAsBoolean() : false;
    }

    private record Pairing(int port, String token) {}

    private long sleep(long backoff) {
        try {
            if (stop.await(backoff, TimeUnit.MILLISECONDS)) return backoff;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return Math.min(backoff * 2, MAX_BACKOFF_MS);
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) root = root.getCause();
        String msg = root.getMessage();
        return msg == null ? root.getClass().getSimpleName() : msg;
    }

    // ------------------------------------------------------------------
    // WebSocket frames
    // ------------------------------------------------------------------

    private final class Frames implements WebSocket.Listener {
        private final Pairing pairing;
        private final CountDownLatch gone;

        Frames(Pairing pairing, CountDownLatch gone) {
            this.pairing = pairing;
            this.gone = gone;
        }

        @Override
        public void onOpen(WebSocket ws) {
            JsonObject hello = new JsonObject();
            hello.addProperty("type", "hello");
            hello.addProperty("token", pairing.token());
            hello.addProperty("clientName", clientName);
            hello.addProperty("libVersion", LIB_VERSION);
            ws.sendText(GSON.toJson(hello), true);
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            if (last) handleFrame(data.toString());
            ws.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            runClose();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            LOGGER.debug("socket error: {}", rootMessage(error));
            runClose();
        }

        private void runClose() {
            socket.compareAndSet(socket.get(), null);
            setConnected(false);
            gone.countDown();
        }
    }
}