package com.freightfox.chatapp.service;

import com.freightfox.chatapp.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for the SSE registry: it must fan out per room, clean up dead emitters
 * and never leak empty room entries.
 */
class RealtimeServiceTest {

    private TestRealtimeService service;

    @BeforeEach
    void setUp() {
        service = new TestRealtimeService(() -> new CapturingEmitter(60_000L));
    }

    @Test
    void subscribe_registersSubscriberAndSendsConnectedEvent() {
        CapturingEmitter emitter = (CapturingEmitter) service.subscribe("general");

        assertThat(service.subscriberCount("general")).isEqualTo(1);
        assertThat(emitter.events()).hasSize(1);
    }

    @Test
    void broadcast_reachesAllSubscribersOfTheRoom() {
        CapturingEmitter first = (CapturingEmitter) service.subscribe("general");
        CapturingEmitter second = (CapturingEmitter) service.subscribe("general");
        first.clear();
        second.clear();

        service.broadcast("general", new ChatMessage("alice", "hello"));

        assertThat(first.events()).hasSize(1);
        assertThat(second.events()).hasSize(1);
    }

    @Test
    void broadcast_doesNotReachOtherRooms() {
        CapturingEmitter roomA = (CapturingEmitter) service.subscribe("room-a");
        CapturingEmitter roomB = (CapturingEmitter) service.subscribe("room-b");
        roomA.clear();
        roomB.clear();

        service.broadcast("room-a", new ChatMessage("alice", "only for a"));

        assertThat(roomA.events()).hasSize(1);
        assertThat(roomB.events()).isEmpty();
    }

    @Test
    void broadcast_withNoSubscribers_isNoOp() {
        assertThatCode(() -> service.broadcast("empty-room", new ChatMessage("alice", "hi")))
                .doesNotThrowAnyException();
        assertThat(service.totalSubscribers()).isZero();
    }

    @Test
    void deadSubscriber_isRemovedWhenDeliveryFails() {
        TestRealtimeService failing = new TestRealtimeService(FailingEmitter::new);
        failing.subscribe("general");
        assertThat(failing.subscriberCount("general")).isEqualTo(1);

        failing.broadcast("general", new ChatMessage("alice", "hello"));

        assertThat(failing.subscriberCount("general")).isZero();
    }

    @Test
    void completeAll_clearsEveryRoom() {
        service.subscribe("room-a");
        service.subscribe("room-b");

        service.completeAll();

        assertThat(service.totalSubscribers()).isZero();
        assertThat(service.subscriberCount("room-a")).isZero();
    }

    @Test
    void heartbeat_sendsCommentToEverySubscriber() {
        CapturingEmitter roomA = (CapturingEmitter) service.subscribe("room-a");
        CapturingEmitter roomB = (CapturingEmitter) service.subscribe("room-b");
        roomA.clear();
        roomB.clear();

        service.sendHeartbeats();

        assertThat(roomA.events()).hasSize(1);
        assertThat(roomB.events()).hasSize(1);
    }

    @Test
    void subscriberCount_tracksRoomsIndependently() {
        service.subscribe("room-a");
        service.subscribe("room-a");
        service.subscribe("room-b");

        assertThat(service.subscriberCount("room-a")).isEqualTo(2);
        assertThat(service.subscriberCount("room-b")).isEqualTo(1);
        assertThat(service.subscriberCount("room-c")).isZero();
        assertThat(service.totalSubscribers()).isEqualTo(3);
    }

    /** SseEmitter records nothing when never initialized, so the test captures send() calls. */
    static class CapturingEmitter extends SseEmitter {
        private final List<SseEventBuilder> events = new CopyOnWriteArrayList<>();

        CapturingEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) {
            events.add(builder);
        }

        List<SseEventBuilder> events() {
            return events;
        }

        void clear() {
            events.clear();
        }
    }

    /** Accepts the initial {@code connected} event, then behaves like a closed connection. */
    static final class FailingEmitter extends CapturingEmitter {
        private final AtomicInteger sends = new AtomicInteger();

        FailingEmitter() {
            super(60_000L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            if (sends.incrementAndGet() > 1) {
                throw new IllegalStateException("client disconnected");
            }
            super.send(builder);
        }
    }

    static final class TestRealtimeService extends RealtimeService {
        private final Supplier<SseEmitter> emitterFactory;

        TestRealtimeService(Supplier<SseEmitter> emitterFactory) {
            super(60_000L);
            this.emitterFactory = emitterFactory;
        }

        @Override
        protected SseEmitter createEmitter() {
            return emitterFactory.get();
        }
    }
}
