package com.taskengine.controller;

import com.taskengine.service.StreamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE (Server‑Sent Events) controller that provides a single endpoint for the frontend to receive
 * real‑time notifications. The {@link StreamService} manages a set of {@link SseEmitter}s and broadcasts
 * {@code Notification} objects to all connected clients.
 */
@RestController
@RequestMapping("/api")
public class StreamController {

    private final StreamService streamService;

    @Autowired
    public StreamController(StreamService streamService) {
        this.streamService = streamService;
    }

    /**
     * Opens a new SSE connection. The client should keep the connection alive and listen for incoming
     * JSON messages representing a {@code Notification}.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        // The service creates a new emitter, registers it, and returns it to the client.
        return streamService.createEmitter();
    }
}
