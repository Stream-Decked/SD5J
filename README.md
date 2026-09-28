# SD5J

A pure Java library for talking to Elgato Stream Decks.

SD5J gives you a typed event bus, a surface model for laying out buttons, page and folder
navigation, and image encoding. It has no Minecraft dependency and no native code, so it works
in any Java 21 application.

## Documentation

Full documentation lives at **[stream-decked.github.io](https://stream-decked.github.io/)**,
under [SD5J](https://stream-decked.github.io/sd5j/).

## Adding it to your project

```gradle
repositories {
    maven {
        name = "sd5j"
        url = "https://dl.cloudsmith.io/public/wolfieboy09/sd5j/maven/"
        content {
            includeGroup("dev.wolfieboy09.sd5j")
        }
    }
}

dependencies {
    implementation "dev.wolfieboy09.sd5j:sd5j:1.0.0"
}
```

Maven Central is not used. The artifact is published to Cloudsmith.

### Runtime dependencies

`org.slf4j:slf4j-api` and `com.google.code.gson:gson`, both `implementation` scope. Minecraft
already ships compatible versions, so mod consumers usually get them for free. Outside
Minecraft, declare them yourself.

## A first look

```java
StreamDeckManager manager = StreamDeckManager.createDefault();

manager.on(DeckEvent.Connected.class, connected -> {
    logger.info("bound to {}", connected.model());
    DeckSurface surface = new DeckSurface(manager, connected.deckId(), connected.model());
    surface.setButton(0, DeckButton.text("Hi", 0xFFFFFFFF, 0xFF4477AA, this::sayHi));
});

manager.on(DeckEvent.KeyDown.class, key -> logger.info("key {} down", key.key()));
manager.start();
```

`createDefault()` connects over the WebSocket to the Stream Deck plugin, reading
`~/.config/streamdecked/pairing.json`. Use `new StreamDeckManager(new RemoteDeckTransport(name))` if
you want to name your client, and `close()` when you are done.

## What you get

- **Events**: keys, rotary encoders, and screen taps and holds, each a sealed record with the
  deck it came from.
- **Surfaces**: place buttons by key or by column and row, with text, icons, and icon plus
  caption variants. Columns past the deck width wrap, so a layout written for a larger deck
  keeps its buttons.
- **Navigation**: pages and folders as a stack, with home, back and page buttons.
- **Images**: BMP or JPEG, with the rotation and mirroring each deck needs handled for you.

Not supported: swipes and Neo capacitive touchpoints. Elgato's Stream Deck plugin SDK does not
deliver them, so there is nothing to subscribe to.

## Building from source

```bash
./gradlew build
./gradlew publishToMavenLocal
```

Java 21 is required. The build expects Cloudsmith credentials in `~/.gradle/gradle.properties`
even for a local build, because the publishing block is evaluated at configuration time:

```properties
cloudsmith.username=...
cloudsmith.publishing.apiKey=...
```

Or pass them inline with `-Pcloudsmith.username=... -Pcloudsmith.publishing.apiKey=...`.

The version is `lib_version` in `gradle.properties` and must be kept in step with
`RemoteDeckTransport.LIB_VERSION`, which is the version the library reports to the plugin over
the WebSocket.

## Project information

- [Documentation](https://stream-decked.github.io/)
- [Licence](https://stream-decked.github.io/about-us/licence.html), Apache 2.0
- [AI stance](https://stream-decked.github.io/about-us/ai-stance.html)
