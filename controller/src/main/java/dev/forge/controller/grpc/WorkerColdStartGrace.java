package dev.forge.controller.grpc;

import org.springframework.stereotype.Component;


@Component
public class WorkerColdStartGrace {

    /*
     * After a controller restart, give the persisted
     * authoritative worker process time to reconnect before a
     * brand-new incarnation may supersede it.
     */
    private static final long
            RECONNECT_GRACE_MS = 10_000;


    private final long controllerStartedAt =
            System.currentTimeMillis();


    public boolean isActive() {

        return remainingMillis() > 0;
    }


    public long remainingMillis() {

        long elapsed =
                System.currentTimeMillis()
                        - controllerStartedAt;


        return Math.max(
                0,
                RECONNECT_GRACE_MS
                        - elapsed
        );
    }
}
