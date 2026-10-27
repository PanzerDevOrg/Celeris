package net.bichal.mods.celeris.pipeline.transit;

import net.bichal.mods.celeris.api.transit.TransitDelta;

/**
 * Turns the {@link TransitEvents} of a solver (and its migrator) into
 * {@link TransitDelta} messages. Register it with {@code solver.setEvents} and
 * {@code migrator.setEvents}; once per tick (or whenever {@link #pending()} is
 * large) call {@link #drain} to take the bytes and start over.
 *
 * <p>A transfer, reported by the events as an enter on the new segment followed
 * by a leave on the old one, is written as a single {@code move}. A message that
 * fills up is handed to the sink and a new one started, so no operation is lost.
 */
public final class TransitDeltaRecorder implements TransitEvents {

    /** Receives each finished message. */
    @FunctionalInterface
    public interface Sink {
        void accept(byte[] message);
    }

    private final TransitDelta.Writer writer = new TransitDelta.Writer();
    private final Sink sink;
    private long baseTick;

    // An enter waiting to see whether it is half of a transfer.
    private boolean hasEnter;
    private long enterSegment;
    private boolean enterForward;
    private int enterHandle;
    private float enterSpeed;
    private float enterProgress;
    private long enterTick;

    public TransitDeltaRecorder(long startTick, Sink sink) {
        this.sink = sink;
        this.baseTick = startTick;
        writer.reset(startTick);
    }

    /** Operations recorded and not yet drained (a pending enter counts). */
    public int pending() {
        return writer.opCount() + (hasEnter ? 1 : 0);
    }

    /** Hands everything recorded so far to the sink as one or more messages, then starts a new message at {@code nextBaseTick}. */
    public void drain(long nextBaseTick) {
        flushEnter();
        if (writer.opCount() > 0) {
            sink.accept(writer.toBytes());
        }
        baseTick = nextBaseTick;
        writer.reset(nextBaseTick);
    }

    @Override
    public void itemEntered(long segmentId, boolean forward, int handle, float speed, float progress, long tick) {
        flushEnter();
        hasEnter = true;
        enterSegment = segmentId;
        enterForward = forward;
        enterHandle = handle;
        enterSpeed = speed;
        enterProgress = progress;
        enterTick = tick;
    }

    @Override
    public void itemLeft(long segmentId, boolean forward, int handle, int reason, long tick) {
        if (reason == LEFT_TRANSFERRED && hasEnter && enterHandle == handle && enterTick == tick) {
            hasEnter = false;
            ensureRoom();
            writer.move(handle, enterSegment, enterForward, enterProgress, enterSpeed, tick);
            return;
        }
        flushEnter();
        ensureRoom();
        writer.leave(handle, tick);
    }

    @Override
    public void itemSpeedChanged(long segmentId, boolean forward, int handle, float speed, float progress, long tick) {
        flushEnter();
        ensureRoom();
        writer.speed(handle, progress, speed, tick);
    }

    private void flushEnter() {
        if (hasEnter) {
            hasEnter = false;
            ensureRoom();
            writer.enter(enterHandle, enterSegment, enterForward, enterProgress, enterSpeed, enterTick);
        }
    }

    /** Starts a new message when this one is full or the next operation is too far in time to be relative to it. */
    private void ensureRoom() {
        if (writer.isFull()) {
            sink.accept(writer.toBytes());
            writer.reset(baseTick);
        }
    }
}
