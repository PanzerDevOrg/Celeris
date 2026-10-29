import net.bichal.mods.celeris.physics.BodyBatch;
import net.bichal.mods.celeris.physics.BodyFlags;
import net.bichal.mods.celeris.physics.BodyParams;
import net.bichal.mods.celeris.physics.EntityBodyBridge;
import net.bichal.mods.celeris.physics.PhysicsFactory;
import net.bichal.mods.celeris.physics.PhysicsMode;
import net.bichal.mods.celeris.physics.PhysicsTestAccess;
import net.bichal.mods.celeris.physics.TerrainView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Heap <-> off-heap synchronisation with a stand-in entity class. */
class EntityBodyBridgeTest {

    static final class FakeEntity {
        int slot = -1;
        double x, y, z, vx, vy, vz;
        boolean onGround;
        int vanillaTicks;
        int writes;
        boolean removeOnVanillaTick;

        FakeEntity(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final PhysicsFactory factory = PhysicsTestAccess.javaFactory();
    private EntityBodyBridge<FakeEntity> bridge;
    private final List<FakeEntity> removedDuringTick = new ArrayList<>();

    private final EntityBodyBridge.Accessor<FakeEntity> accessor = new EntityBodyBridge.Accessor<>() {
        @Override
        public int slot(FakeEntity e) {
            return e.slot;
        }

        @Override
        public void setSlot(FakeEntity e, int slot) {
            e.slot = slot;
        }

        @Override
        public BodyParams params(FakeEntity e) {
            return BodyParams.ITEM;
        }

        @Override
        public void read(FakeEntity e, BodyBatch b, int slot) {
            b.setDimensions(slot, 0.25F, 0.25F);
            b.setPosition(slot, e.x, e.y, e.z);
            b.setVelocity(slot, e.vx, e.vy, e.vz);
            b.setState(slot, e.onGround, false, 0);
        }

        @Override
        public void write(FakeEntity e, BodyBatch b, int slot, int flags) {
            e.x = b.x(slot);
            e.y = b.y(slot);
            e.z = b.z(slot);
            e.vx = b.vx(slot);
            e.vy = b.vy(slot);
            e.vz = b.vz(slot);
            e.onGround = (flags & BodyFlags.ON_GROUND) != 0;
            e.writes++;
        }

        @Override
        public void tickVanilla(FakeEntity e) {
            e.vanillaTicks++;
            if (e.removeOnVanillaTick) {
                bridge.unregister(e);
                removedDuringTick.add(e);
            }
        }
    };

    @Test
    void simulatesDefersAndSyncs() {
        try (TerrainView t = PhysicsTestSupport.floorWorld(factory)) {
            bridge = new EntityBodyBridge<>(factory.newBatch(64, PhysicsMode.VANILLA, 0), accessor);
            FakeEntity falling = new FakeEntity(0.5, 8.0, 0.5);
            FakeEntity nearComplex = new FakeEntity(9.5, 5.0, 9.5);
            FakeEntity doomed = new FakeEntity(9.5, 5.2, 9.5);
            doomed.removeOnVanillaTick = true;
            FakeEntity last = new FakeEntity(2.5, 6.0, 2.5);
            for (FakeEntity e : new FakeEntity[]{falling, nearComplex, doomed, last}) {
                assertTrue(bridge.register(e));
            }

            assertEquals(2, bridge.tick(t, 0));
            assertEquals(1, nearComplex.vanillaTicks);
            assertEquals(1, doomed.vanillaTicks);
            assertEquals(List.of(doomed), removedDuringTick);
            assertEquals(-1, doomed.slot, "unlinked after the tick");
            assertEquals(3, bridge.size());
            assertEquals(2, last.slot, "last body moved into the hole");
            assertEquals(1, falling.writes);
            assertTrue(falling.y < 8.0);

            // An outside write (teleport) is picked up on the next tick.
            falling.y = 20.0;
            falling.vy = 0;
            bridge.markDirty(falling);
            bridge.tick(t, 0);
            assertEquals(20.0 - 0.04, falling.y);

            for (int i = 0; i < 80; i++) {
                bridge.tick(t, 0);
            }
            assertEquals(5.0, falling.y);
            assertTrue(falling.onGround);
            bridge.close();
        }
    }

    @Test
    void aWriteMadeDuringTheDeferredPassIsNotOverwrittenBySyncOut() {
        try (TerrainView t = PhysicsTestSupport.floorWorld(factory)) {
            FakeEntity victim = new FakeEntity(0.5, 8.0, 0.5);
            FakeEntity pusher = new FakeEntity(9.5, 5.0, 9.5); // near the unloaded section: deferred
            EntityBodyBridge.Accessor<FakeEntity> pushing = new EntityBodyBridge.Accessor<>() {
                @Override public int slot(FakeEntity e) { return accessor.slot(e); }
                @Override public void setSlot(FakeEntity e, int s) { accessor.setSlot(e, s); }
                @Override public BodyParams params(FakeEntity e) { return accessor.params(e); }
                @Override public void read(FakeEntity e, BodyBatch b, int s) { accessor.read(e, b, s); }
                @Override public void write(FakeEntity e, BodyBatch b, int s, int f) { accessor.write(e, b, s, f); }
                @Override public void tickVanilla(FakeEntity e) {
                    if (e == pusher) {
                        victim.vy = 1.0;
                        bridge.markDirty(victim);
                    }
                }
            };
            bridge = new EntityBodyBridge<>(factory.newBatch(64, PhysicsMode.VANILLA, 0), pushing);
            assertTrue(bridge.register(victim));
            assertTrue(bridge.register(pusher));
            assertEquals(1, bridge.tick(t, 0));
            assertEquals(1.0, victim.vy, "knockback kept");
            bridge.tick(t, 0);
            assertTrue(victim.vy > 0.9, "knockback survives the next tick: " + victim.vy);
            bridge.close();
        }
    }
}
