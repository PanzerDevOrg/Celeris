package com.panzer.mods.celeris_example.compressor.block;

import com.panzer.mods.celeris.framework.network.ProgressSimulator;
import com.panzer.mods.celeris.framework.ticking.IdleTickFilter;
import com.panzer.mods.celeris_example.compressor.registry.CompressorBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class CompressorBlockEntity extends BlockEntity {

    private static final long PROCESS_TICKS = 100L;
    private static final int SEARCH_RADIUS = 2;

    private final IdleTickFilter idle = new IdleTickFilter();
    public final ProgressSimulator progress = new ProgressSimulator();
    private boolean active;

    public CompressorBlockEntity(BlockPos pos, BlockState state) {
        super(CompressorBlockEntities.COMPRESSOR.get(), pos, state);
    }

    @SuppressWarnings("unused")
    public static void tick(Level level, BlockPos pos, BlockState ignoredState, CompressorBlockEntity be) {
        if (!be.idle.shouldTick(be.active)) return;

        List<ItemEntity> nearby = level.getEntitiesOfClass(
                ItemEntity.class, new AABB(pos).inflate(SEARCH_RADIUS));

        boolean hasInput = !nearby.isEmpty();

        if (hasInput && !be.active) {
            be.active = true;
            be.progress.start(level.getGameTime(), PROCESS_TICKS);
        } else if (!hasInput && be.active) {
            be.active = false;
            be.progress.stop();
        }

        if (be.active) be.setChanged();
    }

    public float progress() {
        Level lvl = this.level;
        return (lvl == null) ? 0f : this.progress.progress(lvl.getGameTime());
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("active", active);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.loadAdditional(tag, registries);
        active = tag.getBoolean("active");
    }

    public ItemStackHandler getItemHandler() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        static final ItemStackHandler INSTANCE = new ItemStackHandler(1);
    }
}
