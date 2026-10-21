package com.freightfox.chatapp.service;

import com.freightfox.chatapp.model.ChatMessage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the Server-Sent Events connections and forwards room messages to them.
 *
 * <p>Subscribers are grouped by room id, so a message published on
 * {@code chat:room:A:messages} is written only to clients that opened the stream
 * for room {@code A}. Emitters are removed on completion, timeout and error, and a
 * scheduled heartbeat keeps idle connections alive through proxies.
 */
@Service
public class RealtimeService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeService.class);

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final long emitterTimeoutMs;

    public RealtimeService(@Value("${app.chat.sse-timeout-ms:1800000}") long emitterTimeoutMs) {
        this.emitterTimeoutMs = emitterTimeoutMs;
    }

    /**
     * Registers a new SSE client for {@code roomId} and sends an initial {@code connected} event.
     * The caller is responsible for having verified that the room exists.
     */
    public SseEmitter subscribe(String roomId) {
        SseEmitter emitter = createEmitter();
        subscribers.computeIfAbsent(roomId, key -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> remove(roomId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(error -> {
            log.debug("SSE connection error in room '{}': {}", roomId, error.getMessage());
            cleanup.run();
        });

        deliver(roomId, emitter, SseEmitter.event()
                .name("connected")
                .reconnectTime(3000)
                .data(Map.of("roomId", roomId), MediaType.APPLICATION_JSON));

        log.debug("SSE subscriber attached to room '{}' ({} now attached)", roomId, subscriberCount(roomId));
        return emitter;
    }

    /** Writes a message to every client currently subscribed to {@code roomId}. */
    public void broadcast(String roomId, ChatMessage message) {
        List<SseEmitter> roomSubscribers = subscribers.get(roomId);
        if (roomSubscribers == null || roomSubscribers.isEmpty()) {
            log.debug("No SSE subscribers for room '{}', dropping real-time delivery", roomId);
            return;
        }

        for (SseEmitter emitter : roomSubscribers) {
            deliver(roomId, emitter, SseEmitter.event()
                    .id(UUID.randomUUID().toString())
                    .name("message")
                    .data(message, MediaType.APPLICATION_JSON));
        }
    }

    /** Comment-only events keep idle connections open; they are ignored by EventSource listeners. */
    @Scheduled(fixedDelayString = "${app.chat.sse-heartbeat-ms:15000}")
    public void sendHeartbeats() {
        subscribers.forEach((roomId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                deliver(roomId, emitter, SseEmitter.event().comment("keep-alive"));
            }
        });
    }

    public int subscriberCount(String roomId) {
        List<SseEmitter> emitters = subscribers.get(roomId);
        return emitters == null ? 0 : emitters.size();
    }

    public int totalSubscribers() {
        return subscribers.values().stream().mapToInt(List::size).sum();
    }

    /** Factory method so tests can substitute an emitter that records events. */
    protected SseEmitter createEmitter() {
        return new SseEmitter(emitterTimeoutMs);
    }

    private void deliver(String roomId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException ex) {
            // The client is gone (browser closed, network dropped, emitter already completed).
            log.debug("Removing dead SSE subscriber from room '{}': {}", roomId, ex.getMessage());
            remove(roomId, emitter);
            completeQuietly(emitter);
        }
    }

    private void remove(String roomId, SseEmitter emitter) {
        subscribers.computeIfPresent(roomId, (key, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // Already completed.
        }
    }

    @PreDestroy
    void completeAll() {
        subscribers.values().forEach(emitters -> emitters.forEach(this::completeQuietly));
        subscribers.clear();
    }
}
