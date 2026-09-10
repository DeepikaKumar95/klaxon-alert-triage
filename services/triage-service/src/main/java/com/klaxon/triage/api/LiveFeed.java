package com.klaxon.triage.api;

import com.klaxon.triage.api.dto.TriagedAlertView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-sent events to the dashboard.
 *
 * SSE rather than WebSocket on purpose: the traffic is one-way, it survives
 * proxies, and the browser reconnects on its own. A WebSocket would be more
 * machinery for no benefit here.
 */
@Component
public class LiveFeed {

    private static final Logger log = LoggerFactory.getLogger(LiveFeed.class);
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));

        try {
            emitter.send(SseEmitter.event().name("hello").data("connected"));
        } catch (IOException e) {
            subscribers.remove(emitter);
        }
        return emitter;
    }

    public void broadcast(TriagedAlertView view) {
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().name("triaged").data(view));
            } catch (Exception e) {
                // A dead browser tab is normal, not an error worth shouting about.
                subscribers.remove(emitter);
                log.debug("Dropped a disconnected subscriber");
            }
        }
    }

    public int subscriberCount() { return subscribers.size(); }
}
