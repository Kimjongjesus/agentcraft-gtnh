package com.robertsnest.aifactory.client;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com.google.gson.JsonObject;

public class OracleSessionTest {

    @Test
    public void resetAfterPublicationDoesNotWaitForThreadTail() throws Exception {
        AtomicBoolean delivered = new AtomicBoolean();
        OracleSession session = new OracleSession(route -> new JsonObject());
        assertTrue(session.request("/oracle/base", (data, error) -> delivered.set(true)));
        session.awaitWorkerForTest();
        // Reproduce the precise published-completion/live-thread state, without
        // relying on winning the few-instruction JVM thread-exit race.
        CountDownLatch releaseTail = new CountDownLatch(1);
        Thread tail = new Thread(() -> {
            try {
                releaseTail.await();
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
        });
        java.lang.reflect.Field worker = OracleSession.class.getDeclaredField("worker");
        worker.setAccessible(true);
        tail.start();
        worker.set(session, tail);
        try {
            session.reset();
            session.drain();
            assertFalse("old world callback discarded", delivered.get());
            assertTrue("published request is no longer fetching", session.request("/oracle/base", (d, e) -> {}));
            session.awaitWorkerForTest();
        } finally {
            releaseTail.countDown();
            tail.join(2000);
        }
    }

    @Test
    public void singleFlightDeliversOnlyOnDrainAndDiscardsOldWorld() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean delivered = new AtomicBoolean();
        OracleSession session = new OracleSession(route -> {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
            return new JsonObject();
        });
        assertTrue(session.request("/oracle/base", (data, error) -> delivered.set(true)));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertFalse(session.request("/oracle/base", (data, error) -> fail("queued")));
        assertFalse(delivered.get());
        session.reset();
        release.countDown();
        session.awaitWorkerForTest();
        session.drain();
        assertFalse(delivered.get());
        assertTrue(session.request("/oracle/base", (data, error) -> delivered.set(true)));
        session.awaitWorkerForTest();
        assertFalse(delivered.get());
        session.drain();
        assertTrue(delivered.get());
    }

    @Test
    public void textIsBoundedAndDoesNotAllowMinecraftFormatting() {
        assertEquals("safe text", OracleText.clean("safe\u00a7k\u0000 text"));
        JsonObject obj = new JsonObject();
        obj.addProperty("answer", "fixture\nCRITICAL: unpowered");
        assertTrue(
            OracleText.lines(obj)
                .toString()
                .contains("CRITICAL"));
    }
}
