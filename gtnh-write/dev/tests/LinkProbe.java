package dev.agentcraft.gtnh.write.mc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test-side bridge to {@link ControlLink}'s package-private writer probe (compiled only into the pure checks, never
 * into the jar). Records what the real writer thread dequeues, drops and how it exits, and can hold the writer
 * between the dequeue and the send-start decision on deterministic latches.
 */
public final class LinkProbe implements ControlLink.WriterProbe {

    public final List<String> dequeued = Collections.synchronizedList(new ArrayList<String>());
    public final List<String> dropped = Collections.synchronizedList(new ArrayList<String>());
    public final CountDownLatch exited = new CountDownLatch(1);
    public volatile Throwable exitError;
    private volatile CountDownLatch holdAt, holdRelease;

    private LinkProbe() {}

    public static LinkProbe on(ControlLink link) {
        LinkProbe p = new LinkProbe();
        link.probe = p;
        return p;
    }

    /** The next dequeued frame counts {@code at} down, then the writer waits (at most 10 s) for {@code release}. */
    public void holdNext(CountDownLatch at, CountDownLatch release) {
        holdRelease = release;
        holdAt = at;
    }

    @Override
    public void dequeued(String frame) {
        dequeued.add(frame);
        CountDownLatch at = holdAt, rel = holdRelease;
        if (at == null) return;
        holdAt = null;
        holdRelease = null;
        at.countDown();
        try {
            rel.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void dropped(String frame) {
        dropped.add(frame);
    }

    @Override
    public void exited(Throwable error) {
        exitError = error;
        exited.countDown();
    }
}
