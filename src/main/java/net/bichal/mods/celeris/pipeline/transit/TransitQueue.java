package net.bichal.mods.celeris.pipeline.transit;

import java.util.Arrays;

/**
 * Items travelling one way along a segment, in struct-of-arrays form: three
 * parallel primitive arrays (progress, speed, handle) and no object per item.
 * {@code handle} is an index into whatever registry the consumer keeps (stack,
 * filter id, ...); Celeris never looks inside it.
 *
 * <p>{@code progress} is the distance travelled from the entry end, in blocks
 * (0 = just entered, {@code segment.length()} = at the exit node). Index 0 is
 * the head (largest progress), the last index the tail, so a queue is always
 * sorted by progress, descending. Speed is blocks per tick.
 *
 * <p>Not thread-safe; owned by the graph's thread. The solver mutates it every
 * tick: read it between ticks.
 */
public final class TransitQueue {

    float[] progress = new float[4];
    float[] speed = new float[4];
    int[] handle = new int[4];
    int size;

    /** Distance the head went past the exit in the last tick (used to carry motion across a junction). */
    float carry;
    /** True while the head waits at the exit because nothing downstream accepted it. */
    boolean blocked;
    /**
     * True when the last tick found every item pressed against the one ahead and the head against the
     * exit: another tick would change nothing but {@link #carry}, so the solver skips the pass over the items.
     * Cleared by every change to the queue.
     */
    boolean settled;
    /** True once an item with a speed that is negative or not a number was added: such a queue is never settled. */
    boolean oddSpeed;

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public float progressAt(int index) {
        checkIndex(index);
        return progress[index];
    }

    public float speedAt(int index) {
        checkIndex(index);
        return speed[index];
    }

    public int handleAt(int index) {
        checkIndex(index);
        return handle[index];
    }

    /** True while the head item sits at the exit node, waiting to be accepted. */
    public boolean isBlocked() {
        return blocked;
    }

    public void setSpeedAt(int index, float newSpeed) {
        checkIndex(index);
        speed[index] = newSpeed;
        settled = false;
        oddSpeed |= !(newSpeed >= 0f);
    }

    /** Appends at the tail without any spacing check; {@code newProgress} must not exceed the current tail's. */
    void addTail(int itemHandle, float itemSpeed, float newProgress) {
        ensureCapacity(size + 1);
        settled = false;
        oddSpeed |= !(itemSpeed >= 0f);
        progress[size] = newProgress;
        speed[size] = itemSpeed;
        handle[size] = itemHandle;
        size++;
    }

    /** Inserts keeping the descending order; after existing items of equal progress. */
    void insertSorted(int itemHandle, float itemSpeed, float newProgress) {
        ensureCapacity(size + 1);
        settled = false;
        oddSpeed |= !(itemSpeed >= 0f);
        int at = size;
        while (at > 0 && progress[at - 1] < newProgress) {
            at--;
        }
        if (at < size) {
            System.arraycopy(progress, at, progress, at + 1, size - at);
            System.arraycopy(speed, at, speed, at + 1, size - at);
            System.arraycopy(handle, at, handle, at + 1, size - at);
        }
        progress[at] = newProgress;
        speed[at] = itemSpeed;
        handle[at] = itemHandle;
        size++;
    }

    void removeHead() {
        size--;
        if (size > 0) {
            System.arraycopy(progress, 1, progress, 0, size);
            System.arraycopy(speed, 1, speed, 0, size);
            System.arraycopy(handle, 1, handle, 0, size);
        }
        blocked = false;
        settled = false;
        oddSpeed &= size > 0;
        carry = 0f;
    }

    void clear() {
        size = 0;
        blocked = false;
        settled = false;
        oddSpeed = false;
        carry = 0f;
    }

    private void ensureCapacity(int required) {
        if (required <= progress.length) {
            return;
        }
        int capacity = Integer.highestOneBit(required - 1) << 1;
        progress = Arrays.copyOf(progress, capacity);
        speed = Arrays.copyOf(speed, capacity);
        handle = Arrays.copyOf(handle, capacity);
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(index);
        }
    }
}
