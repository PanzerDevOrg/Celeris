package com.panzer.mods.celeris_example.memorybank;

import com.panzer.mods.celeris.core.memory.MemoryBus;
import com.panzer.mods.celeris.core.memory.backend.CelerisRuntime;
import com.panzer.mods.celeris_example.memorybank.registry.MemoryBankBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import java.lang.ref.Cleaner;

/**
 * A real, working demo of {@link MemoryBus}
 * for per-block-entity state, including the part every off-heap-per-object
 * design has to get right: cleanup that runs even if the chunk unloads
 * and this object is garbage collected without {@link #setRemoved()} ever
 * being called.
 *
 * <p><b>Important:</b> {@link #BUS} is shared by every memory bank on the
 * server -- it's backed by {@link CelerisRuntime#backend()},
 * the same JVM-wide backend everything else in Celeris uses.
 * {@code MemoryBus.close()} frees <em>that whole backend</em>, not just
 * this bus's channels -- calling it here would break every other Celeris
 * subsystem in the game. This class only ever calls
 * {@link MemoryBus#closeChannel}, never
 * {@code close()}.
 */
public final class MemoryBankBlockEntity extends BlockEntity {

    /** Shared by every memory bank in the JVM -- see the class javadoc for why this must never have {@code close()} called on it. */
    static final MemoryBus BUS = new MemoryBus();

    private static final Cleaner CLEANER = Cleaner.create();

    static final int SLOT_COUNT = 64;
    private static final long CHANNEL_BYTES = (long) SLOT_COUNT * Long.BYTES;

    private final int channel;
    private final Cleaner.Cleanable cleanable;

    public MemoryBankBlockEntity(BlockPos pos, BlockState state) {
        super(MemoryBankBlockEntities.MEMORY_BANK.get(), pos, state);
        this.channel = BUS.openChannel(CHANNEL_BYTES);
        // The cleaning action must not reference `this` (directly or via a
        // non-static inner class) -- holding a reference would keep this
        // block entity reachable forever and the action would never run.
        this.cleanable = CLEANER.register(this, new ChannelCloser(BUS, channel));
    }

    /** Off-heap counter at slot 0, incremented by right-clicking the block. */
    public long counter() {
        return BUS.readLong(channel, 0L);
    }

    public void incrementCounter() {
        BUS.writeLong(channel, 0L, counter() + 1L);
        setChanged();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // Prompt free on real removal (block broken). The Cleaner above is
        // the safety net for the chunk-unload-without-removal case, not the
        // primary path -- freeing here avoids leaving the channel's memory
        // sitting around until the next GC cycle notices this is garbage.
        cleanable.clean();
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.saveAdditional(tag, registries);
        byte[] buffer = new byte[(int) CHANNEL_BYTES];
        BUS.copyToHeap(channel, 0L, buffer, 0, buffer.length);
        tag.putByteArray("memory", buffer);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.loadAdditional(tag, registries);
        byte[] buffer = tag.getByteArray("memory");
        if (buffer.length == CHANNEL_BYTES) {
            BUS.copyFromHeap(channel, 0L, buffer, 0, buffer.length);
        }
    }

    /** Deliberately holds no reference to the {@link MemoryBankBlockEntity} being cleaned -- see the constructor comment. */
    private record ChannelCloser(MemoryBus bus, int channel) implements Runnable {
        @Override
        public void run() {
            bus.closeChannel(channel);
        }
    }
}
