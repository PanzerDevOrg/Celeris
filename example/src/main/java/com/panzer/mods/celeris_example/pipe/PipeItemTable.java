package com.panzer.mods.celeris_example.pipe;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;

/**
 * Maps the integer handles the transit solver moves around to the stacks they
 * stand for. The solver never sees an {@link ItemStack}: it moves ints.
 *
 * <p>A released handle is not reused until {@link #syncFlushed()} says the
 * clients were told about the release, so a handle never means two different
 * items inside one batch of messages.
 */
final class PipeItemTable {

    private ItemStack[] stacks = new ItemStack[64];
    private int nextHandle;
    private final IntArrayList free = new IntArrayList();

    // Changes since the last message to the clients.
    private final IntArrayList defined = new IntArrayList();
    private final IntArrayList released = new IntArrayList();

    int add(ItemStack stack) {
        int handle = free.isEmpty() ? nextHandle++ : free.removeInt(free.size() - 1);
        if (handle >= stacks.length) {
            stacks = Arrays.copyOf(stacks, stacks.length << 1);
        }
        stacks[handle] = stack;
        defined.add(handle);
        return handle;
    }

    ItemStack get(int handle) {
        return stacks[handle];
    }

    /** Forgets the handle (delivered, lost or never accepted). Returns its stack. */
    ItemStack release(int handle) {
        ItemStack stack = stacks[handle];
        stacks[handle] = null;
        released.add(handle);
        return stack;
    }

    boolean hasChanges() {
        return !defined.isEmpty() || !released.isEmpty();
    }

    /** Handles introduced since the last flush, and the registry id of their item. */
    int[] definedHandles() {
        return defined.toIntArray();
    }

    int[] definedItems() {
        int[] items = new int[defined.size()];
        for (int i = 0; i < items.length; i++) {
            ItemStack stack = stacks[defined.getInt(i)];
            items[i] = stack == null ? -1 : BuiltInRegistries.ITEM.getId(stack.getItem());
        }
        return items;
    }

    int[] releasedHandles() {
        return released.toIntArray();
    }

    /** The clients have been told about the pending changes: released handles become reusable. */
    void syncFlushed() {
        free.addAll(released);
        defined.clear();
        released.clear();
    }

    /** Every handle that currently stands for an item, for a player who just joined. */
    int[] liveHandles() {
        IntArrayList live = new IntArrayList();
        for (int h = 0; h < nextHandle; h++) {
            if (stacks[h] != null) {
                live.add(h);
            }
        }
        return live.toIntArray();
    }

    int itemIdOf(int handle) {
        return BuiltInRegistries.ITEM.getId(stacks[handle].getItem());
    }
}
