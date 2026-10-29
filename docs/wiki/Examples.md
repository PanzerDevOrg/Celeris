# Examples

Short code you can copy into your mod. Each example uses public Celeris classes. The complete, working code is in the [example folder](https://github.com/bichal/Celeris/tree/main/example) of the repository.

## 1. Check what Celeris is running

```java
import net.bichal.mods.celeris.api.CelerisFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CelerisStatus {

    private static final Logger LOGGER = LoggerFactory.getLogger("MyMod");

    public static void logStatus() {
        LOGGER.info("Celeris memory: {}", CelerisFeatures.memoryBackendName());
        LOGGER.info("Celeris math: {}", CelerisFeatures.vectorBackendName());
        LOGGER.info("Celeris compression: {}", CelerisFeatures.compressionCodecName());
        if (!CelerisFeatures.isRunningAtFullPerformance()) {
            LOGGER.info("Celeris is running some plain Java code. It works, but it is slower.");
        }
    }
}
```

## 2. Work on a background thread, apply on the game thread

Use `AsyncResultQueue` when a worker thread finishes some work, and the game thread must apply the result safely.

```java
import net.bichal.mods.celeris.framework.async.AsyncResultQueue;

public final class MyWork {

    // Create once. The capacity must be a power of two, such as 256.
    private final AsyncResultQueue<String> results = new AsyncResultQueue<>(256);

    // Runs on a worker thread.
    public void finish(String result) {
        if (!results.offer(result)) {
            // The queue is full, so the game thread is behind.
            // Try again later, or drop the result.
        }
    }

    // Runs on the game thread, once per tick. Applies at most 32 results.
    public void tick() {
        results.drainTo(this::apply, 32);
    }

    private void apply(String result) {
        // Change the world here, on the game thread.
    }
}
```

## 3. Compress data, then get it back

```java
import java.nio.charset.StandardCharsets;
import net.bichal.mods.celeris.network.CompressedPayload;
import net.bichal.mods.celeris.network.PayloadCompression;

byte[] raw = "some data that you want to send".getBytes(StandardCharsets.UTF_8);

// Compresses with the level from the Celeris settings.
CompressedPayload packed = PayloadCompression.compress(raw);

// Gets the original bytes back.
byte[] unpacked = PayloadCompression.decompress(packed);
```

Notes:

- Data shorter than 256 bytes is kept as it is, because compressing it would not make it smaller.
- A compressed payload can be up to 1 MiB.
- Decompressing throws an error if the data would be larger than 8 MiB.

## 4. Send a batch of messages with a recorder (advanced)

This sketch shows how pipe items are moved and how their changes are sent to players. It leaves out the imports and the setup of the pipe network. The full code is in `PipeNetworkManager.java` in the example folder.

```java
// Items that cannot stay in the network are dropped into the world.
var migrator = new TransitMigrator((handle, speed, nodeRaw) -> dropInWorld(handle, nodeRaw));

// Pipes keep their items when they are edited.
var graph = new SegmentedGraphImpl<>(topology, TransitPayload::new, migrator);
var solver = new TransitSolver(TransitConfig.defaults(), new RoundRobinRouter(), endpoints, migrator);
solver.bind(graph);

// Collects what changed, and turns it into messages for players.
var recorder = new TransitDeltaRecorder(0L, bytes -> send(TransitDeltaPackets.encode(bytes)));
solver.setEvents(recorder);
migrator.setEvents(recorder, solver::tickCount);

graph.addNodes(ids, masks, count);      // a whole chunk of pipes in one call
solver.insert(node, direction, handle); // an item enters the pipe that leaves `node`

solver.tick();                          // once per server tick
recorder.drain(solver.tickCount());     // sends only what changed
```

The client side reads these messages with `TransitDeltaPackets.decode` and draws items with `TransitMotion`. See the example folder for that code.
