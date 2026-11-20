package com.taskengine.service;

import com.taskengine.model.Notification;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StreamService {

    private final Set<SseEmitter> emitters = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public SseEmitter createEmitter() {
        SseEmitter emitter = new SseEmitter(60 * 60 * 1000L); // 1 hour timeout
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            emitters.remove(emitter);
        });
        emitter.onError(e -> {
            emitter.complete();
            emitters.remove(emitter);
        });

        // Send an initial connected handshake
        try {
            emitter.send(SseEmitter.event()
                    .name("CONNECTED")
                    .data(Map.of("message", "Connected to TaskEngine Real-Time Stream", "status", "ONLINE")));
        } catch (IOException e) {
            emitters.remove(emitter);
        }

        return emitter;
    }

    public void publish(Notification notification) {
        broadcastEvent("NOTIFICATION", notification);
    }

    public void broadcastEvent(String eventName, Object payload) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }
}
