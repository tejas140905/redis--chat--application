package com.freightfox.chatapp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freightfox.chatapp.redis.RedisKeys;
import com.freightfox.chatapp.support.RedisTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests over HTTP against a real Redis container: REST surface, history
 * semantics, error mapping and genuine Server-Sent Events delivery.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private String baseUrl;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        RedisTestContainer.register(registry);
    }

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + "/api/chatapp/chatrooms";
        RedisTestContainer.flush(redisTemplate);
    }

    // --- REST happy paths ------------------------------------------------

    @Test
    void createJoinSendAndRead_worksEndToEnd() throws Exception {
        assertThat(createRoom("general").getStatusCode().value()).isEqualTo(200);
        assertThat(postJson("/general/join", "{\"participant\":\"alice\"}").getStatusCode().value()).isEqualTo(200);
        assertThat(postJson("/general/messages",
                "{\"participant\":\"alice\",\"message\":\"Hello Freightfox\"}").getStatusCode().value()).isEqualTo(200);

        JsonNode history = body(get("/general/messages?limit=10"));

        assertThat(history.get("messages").size()).isEqualTo(1);
        assertThat(history.get("messages").get(0).get("participant").asText()).isEqualTo("alice");
        assertThat(history.get("messages").get(0).get("message").asText()).isEqualTo("Hello Freightfox");
        assertThat(history.get("messages").get(0).get("timestamp").asText()).matches(
                "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");
    }

    @Test
    void history_returnsNewestForLimitOne_andBothInChronologicalOrderForLimitTwo() throws Exception {
        createRoom("order-room");
        postJson("/order-room/messages", "{\"participant\":\"alice\",\"message\":\"first\"}");
        postJson("/order-room/messages", "{\"participant\":\"alice\",\"message\":\"second\"}");

        JsonNode newest = body(get("/order-room/messages?limit=1"));
        assertThat(newest.get("messages").size()).isEqualTo(1);
        assertThat(newest.get("messages").get(0).get("message").asText()).isEqualTo("second");

        JsonNode both = body(get("/order-room/messages?limit=2"));
        assertThat(both.get("messages").size()).isEqualTo(2);
        assertThat(both.get("messages").get(0).get("message").asText()).isEqualTo("first");
        assertThat(both.get("messages").get(1).get("message").asText()).isEqualTo("second");

        assertThat(redisTemplate.opsForList().size(RedisKeys.messages("order-room"))).isEqualTo(2L);
    }

    @Test
    void joiningRoom_updatesParticipantSet() {
        createRoom("people-room");
        postJson("/people-room/join", "{\"participant\":\"alice\"}");
        postJson("/people-room/join", "{\"participant\":\"bob\"}");

        assertThat(redisTemplate.opsForSet().members(RedisKeys.participants("people-room")))
                .containsExactlyInAnyOrder("alice", "bob");
    }

    // --- error mapping ---------------------------------------------------

    @Test
    void duplicateRoomCreation_returns409() {
        createRoom("dup-room");

        ResponseEntity<String> duplicate = createRoom("dup-room");

        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
        assertThat(duplicate.getBody()).contains("already exists");
    }

    @Test
    void joinMissingRoom_returns404() {
        ResponseEntity<String> response = postJson("/missing/join", "{\"participant\":\"alice\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("not found");
    }

    @Test
    void sendToMissingRoom_returns404() {
        ResponseEntity<String> response = postJson("/missing/messages",
                "{\"participant\":\"alice\",\"message\":\"hello\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void invalidLimits_return400() {
        createRoom("limits-room");

        assertThat(get("/limits-room/messages?limit=0").getStatusCode().value()).isEqualTo(400);
        assertThat(get("/limits-room/messages?limit=-1").getStatusCode().value()).isEqualTo(400);
        assertThat(get("/limits-room/messages?limit=101").getStatusCode().value()).isEqualTo(400);
        assertThat(get("/limits-room/messages?limit=abc").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void invalidPayloads_return400WithErrorBody() {
        createRoom("validation-room");

        ResponseEntity<String> blankRoom = postJson("", "{\"roomName\":\"\"}");
        ResponseEntity<String> blankParticipant = postJson("/validation-room/join", "{\"participant\":\"\"}");
        ResponseEntity<String> blankMessage = postJson("/validation-room/messages",
                "{\"participant\":\"alice\",\"message\":\"\"}");
        ResponseEntity<String> malformed = postJson("/validation-room/messages", "{not-json}");

        assertThat(List.of(blankRoom, blankParticipant, blankMessage, malformed))
                .allSatisfy(response -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(400);
                    assertThat(response.getBody()).contains("\"status\":\"error\"");
                });
    }

    @Test
    void invalidRoomIdInPath_returns400() {
        assertThat(get("/bad!id/messages").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void deleteRoom_removesEverythingAndHistoryBecomesUnavailable() throws Exception {
        createRoom("delete-room");
        postJson("/delete-room/messages", "{\"participant\":\"alice\",\"message\":\"bye\"}");

        assertThat(deleteRoom("delete-room").getStatusCode().value()).isEqualTo(200);

        assertThat(get("/delete-room/messages").getStatusCode().value()).isEqualTo(404);
        assertThat(redisTemplate.hasKey(RedisKeys.metadata("delete-room"))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeys.participants("delete-room"))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeys.messages("delete-room"))).isFalse();

        assertThat(deleteRoom("delete-room").getStatusCode().value()).isEqualTo(404);
    }

    // --- real-time delivery ----------------------------------------------

    @Test
    void realTime_deliversOnlyToSubscribersOfTheSameRoom() {
        createRoom("live-a");
        createRoom("live-b");

        try (SseClient roomA = new SseClient(streamUrl("live-a"));
             SseClient roomB = new SseClient(streamUrl("live-b"))) {

            RedisTestContainer.waitUntil("both SSE streams to connect", TIMEOUT,
                    () -> roomA.hasConnectedEvent() && roomB.hasConnectedEvent());

            postJson("/live-a/messages", "{\"participant\":\"alice\",\"message\":\"hello room A\"}");

            RedisTestContainer.waitUntil("room A to receive its message", TIMEOUT,
                    () -> roomA.hasMessage("hello room A"));

            sleep(500);
            assertThat(roomB.hasMessage("hello room A"))
                    .as("room B must not receive room A's message")
                    .isFalse();

            postJson("/live-b/messages", "{\"participant\":\"bob\",\"message\":\"hello room B\"}");

            RedisTestContainer.waitUntil("room B to receive its message", TIMEOUT,
                    () -> roomB.hasMessage("hello room B"));
            assertThat(roomA.hasMessage("hello room B"))
                    .as("room A must not receive room B's message")
                    .isFalse();
        }
    }

    @Test
    void stream_forMissingRoom_returns404() {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(streamUrl("never-created")))
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .build();

        HttpResponse<String> response = assertDoesNotThrow(() -> client.send(request, HttpResponse.BodyHandlers.ofString()));

        assertThat(response.statusCode()).isEqualTo(404);
    }

    // --- helpers ---------------------------------------------------------

    private ResponseEntity<String> createRoom(String roomName) {
        return postJson("", "{\"roomName\":\"" + roomName + "\"}");
    }

    private ResponseEntity<String> postJson(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(baseUrl + path, new HttpEntity<>(json, headers), String.class);
    }

    private ResponseEntity<String> get(String path) {
        return rest.getForEntity(baseUrl + path, String.class);
    }

    private ResponseEntity<String> deleteRoom(String roomName) {
        return rest.exchange(baseUrl + "/" + roomName, HttpMethod.DELETE, null, String.class);
    }

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody());
    }

    private String streamUrl(String roomId) {
        return baseUrl + "/" + roomId + "/stream";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static <T> T assertDoesNotThrow(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** Minimal SSE client: reads the event stream on a background thread and records lines. */
    private static final class SseClient implements AutoCloseable {

        private final List<String> lines = new CopyOnWriteArrayList<>();
        private volatile Stream<String> stream;

        SseClient(String url) {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                    .build();

            Thread reader = new Thread(() -> {
                try {
                    stream = client.send(request, HttpResponse.BodyHandlers.ofLines()).body();
                    stream.forEach(lines::add);
                } catch (Exception ignored) {
                    // Stream closed by the test or the server.
                }
            }, "sse-client");
            reader.setDaemon(true);
            reader.start();
        }

        boolean hasConnectedEvent() {
            return lines.stream().anyMatch(line -> line.startsWith("data:") && line.contains("\"roomId\""));
        }

        boolean hasMessage(String text) {
            return lines.stream().anyMatch(line -> line.startsWith("data:") && line.contains(text));
        }

        @Override
        public void close() {
            Stream<String> current = stream;
            if (current != null) {
                current.close();
            }
        }
    }
}
