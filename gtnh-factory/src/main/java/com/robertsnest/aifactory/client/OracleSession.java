package com.robertsnest.aifactory.client;

import java.io.IOException;
import java.util.function.BiConsumer;

import com.google.gson.JsonObject;

/** Single in-flight request, no queue. Completion runs only when the client ticks. */
public final class OracleSession {

    public interface Fetch {

        JsonObject get(String route) throws IOException;
    }

    private final Fetch fetch;
    private Thread worker;
    private Runnable completion;
    private long generation;
    private boolean busy;
    private boolean fetching;

    public OracleSession(Fetch fetch) {
        this.fetch = fetch;
    }

    public synchronized boolean request(String route, BiConsumer<JsonObject, String> callback) {
        if (busy) return false;
        busy = true;
        fetching = true;
        long epoch = generation;
        worker = new Thread(() -> {
            JsonObject data = null;
            String error = null;
            try {
                data = fetch.get(route);
            } catch (Exception e) {
                error = "Oracle unavailable: check endpoint, token, and service (no retry queued)";
            }
            final JsonObject result = data;
            final String failure = error;
            synchronized (OracleSession.this) {
                fetching = false;
                if (epoch == generation) completion = () -> callback.accept(result, failure);
                else busy = false;
            }
        }, "oracle-client-read");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public void drain() {
        Runnable ready;
        synchronized (this) {
            ready = completion;
            completion = null;
            if (ready != null) busy = false;
        }
        if (ready != null) ready.run();
    }

    public synchronized void reset() {
        generation++;
        completion = null;
        // Admission follows request state, not the JVM thread-exit tail.
        busy = fetching;
    }

    void awaitWorkerForTest() throws InterruptedException {
        worker.join(20000);
    }
}
