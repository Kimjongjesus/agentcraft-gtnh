package dev.agentcraft.gtnh.write.proto;

/** Millisecond clock, injectable for tests. */
public interface Clock {

    long now();

    Clock SYSTEM = new Clock() {

        @Override
        public long now() {
            return System.currentTimeMillis();
        }
    };
}
