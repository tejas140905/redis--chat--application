package com.freightfox.chatapp.service;

import com.freightfox.chatapp.exception.RoomAlreadyExistsException;
import com.freightfox.chatapp.exception.RoomNotFoundException;
import com.freightfox.chatapp.model.ChatMessage;
import com.freightfox.chatapp.redis.RedisKeys;
import com.freightfox.chatapp.support.RedisTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-level tests against a real Redis container.
 * {@code max-history-limit} is lowered to 3 so trimming is easy to assert.
 */
@SpringBootTest
@TestPropertySource(properties = "app.chat.max-history-limit=3")
class ChatRoomServiceTest {

    @Autowired
    private ChatRoomService chatRoomService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        RedisTestContainer.register(registry);
    }

    @BeforeEach
    void cleanup() {
        RedisTestContainer.flush(redisTemplate);
    }

    @Test
    void createRoom_storesMetadataHash() {
        chatRoomService.createRoom("general");

        assertThat(chatRoomService.roomExists("general")).isTrue();
        assertThat(redisTemplate.opsForHash().get(RedisKeys.metadata("general"), "name")).isEqualTo("general");
        assertThat(redisTemplate.opsForHash().get(RedisKeys.metadata("general"), "createdAt")).isNotNull();
    }

    @Test
    void createRoom_whenRoomExists_throws() {
        chatRoomService.createRoom("general");

        assertThatThrownBy(() -> chatRoomService.createRoom("general"))
                .isInstanceOf(RoomAlreadyExistsException.class)
                .hasMessageContaining("general");
    }

    @Test
    void createRoom_concurrentRequests_onlyOneSucceeds() throws Exception {
        int attempts = 6;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try {
            for (int i = 0; i < attempts; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        chatRoomService.createRoom("race-room");
                        return true;
                    } catch (RoomAlreadyExistsException ex) {
                        return false;
                    }
                }));
            }
            start.countDown();

            int successes = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void joinRoom_addsParticipantToSet() {
        chatRoomService.createRoom("general");
        chatRoomService.joinRoom("general", "user1");
        chatRoomService.joinRoom("general", "user1");

        Set<Object> participants = chatRoomService.getParticipants("general");
        assertThat(participants).containsExactly("user1");
    }

    @Test
    void joinRoom_whenRoomMissing_throws() {
        assertThatThrownBy(() -> chatRoomService.joinRoom("nonexistent", "user1"))
                .isInstanceOf(RoomNotFoundException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    void sendMessage_persistsMessageAndRegistersParticipant() {
        chatRoomService.createRoom("general");

        chatRoomService.sendMessage("general", "user1", "Hello World");

        List<ChatMessage> messages = chatRoomService.getMessages("general", 10);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getParticipant()).isEqualTo("user1");
        assertThat(messages.get(0).getMessage()).isEqualTo("Hello World");
        assertThat(messages.get(0).getTimestamp()).isNotNull();
        assertThat(chatRoomService.getParticipants("general")).contains("user1");
    }

    @Test
    void sendMessage_whenRoomMissing_throws() {
        assertThatThrownBy(() -> chatRoomService.sendMessage("nonexistent", "user1", "test"))
                .isInstanceOf(RoomNotFoundException.class);
    }

    @Test
    void getMessages_returnsNewestNInChronologicalOrder() {
        chatRoomService.createRoom("general");
        chatRoomService.sendMessage("general", "user1", "Message 1");
        chatRoomService.sendMessage("general", "user1", "Message 2");
        chatRoomService.sendMessage("general", "user1", "Message 3");

        assertThat(chatRoomService.getMessages("general", 2))
                .extracting(ChatMessage::getMessage)
                .containsExactly("Message 2", "Message 3");

        assertThat(chatRoomService.getMessages("general", 1))
                .extracting(ChatMessage::getMessage)
                .containsExactly("Message 3");
    }

    @Test
    void sendMessage_trimsHistoryToConfiguredMaximum() {
        chatRoomService.createRoom("general");
        for (int i = 1; i <= 5; i++) {
            chatRoomService.sendMessage("general", "user1", "Message " + i);
        }

        List<ChatMessage> messages = chatRoomService.getMessages("general", 100);

        assertThat(messages).extracting(ChatMessage::getMessage)
                .containsExactly("Message 3", "Message 4", "Message 5");
        assertThat(redisTemplate.opsForList().size(RedisKeys.messages("general"))).isEqualTo(3L);
    }

    @Test
    void getMessages_whenRoomEmpty_returnsEmptyList() {
        chatRoomService.createRoom("general");

        assertThat(chatRoomService.getMessages("general", 10)).isEmpty();
    }

    @Test
    void getMessages_whenRoomMissing_throws() {
        assertThatThrownBy(() -> chatRoomService.getMessages("nonexistent", 10))
                .isInstanceOf(RoomNotFoundException.class);
    }

    @Test
    void leaveRoom_removesParticipant() {
        chatRoomService.createRoom("general");
        chatRoomService.joinRoom("general", "user1");
        chatRoomService.joinRoom("general", "user2");

        chatRoomService.leaveRoom("general", "user1");

        assertThat(chatRoomService.getParticipants("general")).containsExactly("user2");
    }

    @Test
    void deleteRoom_removesMetadataParticipantsAndMessages() {
        chatRoomService.createRoom("general");
        chatRoomService.joinRoom("general", "user1");
        chatRoomService.sendMessage("general", "user1", "test");

        chatRoomService.deleteRoom("general");

        assertThat(chatRoomService.roomExists("general")).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeys.metadata("general"))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeys.participants("general"))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeys.messages("general"))).isFalse();
    }

    @Test
    void deleteRoom_whenRoomMissing_throws() {
        assertThatThrownBy(() -> chatRoomService.deleteRoom("nonexistent"))
                .isInstanceOf(RoomNotFoundException.class);
    }
}
