package com.panzer.mods.celeris_example.compressor.menu;

import com.panzer.mods.celeris_example.compressor.block.CompressorBlockEntity;
import com.panzer.mods.celeris_example.compressor.registry.CompressorBlocks;
import com.panzer.mods.celeris_example.compressor.registry.CompressorMenus;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.SlotItemHandler;
import org.jetbrains.annotations.NotNull;

/* Example NOT FINISHED! */
public final class CompressorMenu extends AbstractContainerMenu {

    private static final int SCALE = 10_000;

    private final CompressorBlockEntity blockEntity;
    private final ContainerLevelAccess access;
    private final DataSlot progressSlot;

    public CompressorMenu(int id, Inventory playerInv) {
        this(id, playerInv, null, ContainerLevelAccess.NULL);
    }

    public CompressorMenu(int id, Inventory playerInv, CompressorBlockEntity be, ContainerLevelAccess access) {
        super(CompressorMenus.COMPRESSOR.get(), id);
        this.blockEntity = be;
        this.access = access;

        this.progressSlot = DataSlot.standalone();
        this.addDataSlot(this.progressSlot);

        if (be != null) {
            this.addSlot(new SlotItemHandler(be.getItemHandler(), 0, 56, 35));
            this.addSlot(new SlotItemHandler(be.getItemHandler(), 1, 116, 35));
        } else {
            this.addSlot(new Slot(new SimpleContainer(2), 0, 56, 35));
            this.addSlot(new Slot(new SimpleContainer(2), 1, 116, 35));
        }

        // Player Inventory(3x9), Slots 2 - 28
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }

        // Player Hotbar (1x9), Slots 29 - 37
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(playerInv, col, 8 + col * 18, 142));
        }
    }

    @Override
    public void broadcastChanges() {
        if (blockEntity != null) {
            progressSlot.set((int) (blockEntity.progress() * SCALE));
        }
        super.broadcastChanges();
    }

    public float syncedProgress() {
        return progressSlot.get() / (float) SCALE;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return AbstractContainerMenu.stillValid(access, player, CompressorBlocks.COMPRESSOR.get());
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);

        if (slot.hasItem()) {
            ItemStack stackInSlot = slot.getItem();
            itemstack = stackInSlot.copy();

            // Index:
            // 0..1: Compressor Slot
            // 2..28: Player Inv
            // 29..37: Hotbar
            if (index < 2) {
                if (!this.moveItemStackTo(stackInSlot, 2, 38, true)) {
                    return ItemStack.EMPTY;
                }
                slot.onQuickCraft(stackInSlot, itemstack);
            } else {
                if (!this.moveItemStackTo(stackInSlot, 0, 1, false)) {
                    if (index < 29) {
                        if (!this.moveItemStackTo(stackInSlot, 29, 38, false)) {
                            return ItemStack.EMPTY;
                        }
                    } else if (!this.moveItemStackTo(stackInSlot, 2, 29, false)) {
                        return ItemStack.EMPTY;
                    }
                }
            }

            if (stackInSlot.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            if (stackInSlot.getCount() == itemstack.getCount()) {
                return ItemStack.EMPTY;
            }

            slot.onTake(player, stackInSlot);
        }

        return itemstack;
    }
}
