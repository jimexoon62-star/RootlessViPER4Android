package me.timschneeberger.rootlessjamesdsp.audio;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/** Independent of Binder, AudioRecord and the PCM writer. poll() must run on its own thread. */
public final class ShellRouteGuard {
    private final LongSupplier clock;
    private final Runnable cleanup;
    private final Executor cleanupExecutor;
    private final IntConsumer terminate;
    private final AtomicBoolean closed = new AtomicBoolean(), terminated = new AtomicBoolean();
    private final long startedAt;
    private volatile long lastAck, closingAt;
    private volatile boolean ready, cleaned, closingPublished;
    private volatile int exitCode;

    public ShellRouteGuard(LongSupplier clock, Runnable cleanup, Executor cleanupExecutor, IntConsumer terminate) {
        this.clock = clock; this.cleanup = cleanup; this.cleanupExecutor = cleanupExecutor; this.terminate = terminate;
        startedAt = lastAck = clock.getAsLong();
    }
    public boolean isClosed() { return closed.get(); }
    public synchronized void ready() {
        if (!closed.get()) { lastAck = clock.getAsLong(); ready = true; }
    }
    public synchronized void acknowledge() {
        if (!closed.get() && ready) lastAck = clock.getAsLong();
    }
    public synchronized void close(int code) {
        if (!closed.compareAndSet(false, true)) return;
        closingAt = clock.getAsLong(); exitCode = code; closingPublished = true;
        // A stuck cleanup cannot block the independent termination deadline.
        try { cleanupExecutor.execute(() -> { try { cleanup.run(); } finally { cleaned = true; } }); }
        catch (RuntimeException ignored) { /* poll still terminates the policy owner. */ }
    }
    public void poll() {
        if (!closed.get() && clock.getAsLong() - (ready ? lastAck : startedAt) >= (ready ? 1500 : 4000)) {
            System.err.println(ready ? "SHELL_RENDERER_LEASE_EXPIRED" : "SHELL_INITIALIZATION_TIMEOUT");
            close(1);
        }
        if (closingPublished && (cleaned || clock.getAsLong() - closingAt >= 250) && terminated.compareAndSet(false, true))
        {
            if (!cleaned) System.err.println("SHELL_ROUTE_RELEASE_UNCONFIRMED: cleanup deadline; terminating policy owner");
            terminate.accept(exitCode);
        }
    }
}
