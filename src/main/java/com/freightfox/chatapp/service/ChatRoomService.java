package com.freightfox.chatapp.service;

import com.freightfox.chatapp.exception.RoomAlreadyExistsException;
import com.freightfox.chatapp.exception.RoomNotFoundException;
import com.freightfox.chatapp.model.ChatMessage;
import com.freightfox.chatapp.redis.RedisKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class ChatRoomService {

    private static final String FIELD_NAME = "name";
    private static final String FIELD_CREATED_AT = "createdAt";

    private final RedisTemplate<String, Object> redisTemplate;
    private final PubSubService pubSubService;
    private final int maxHistoryLimit;

    public ChatRoomService(RedisTemplate<String, Object> redisTemplate,
                           PubSubService pubSubService,
                           @Value("${app.chat.max-history-limit:100}") int maxHistoryLimit) {
        this.redisTemplate = redisTemplate;
        this.pubSubService = pubSubService;
        this.maxHistoryLimit = Math.max(1, maxHistoryLimit);
    }

    /**
     * Creates a room. {@code HSETNX} on the metadata hash makes the check-and-create atomic,
     * so two concurrent requests for the same name cannot both succeed.
     */
    public void createRoom(String roomName) {
        String metadataKey = RedisKeys.metadata(roomName);

        Boolean created = redisTemplate.opsForHash()
                .putIfAbsent(metadataKey, FIELD_CREATED_AT, Instant.now().toString());

        if (!Boolean.TRUE.equals(created)) {
            throw new RoomAlreadyExistsException(roomName);
        }

        redisTemplate.opsForHash().put(metadataKey, FIELD_NAME, roomName);
    }

    public void joinRoom(String roomId, String participant) {
        requireRoom(roomId);
        redisTemplate.opsForSet().add(RedisKeys.participants(roomId), participant);
    }

    public void leaveRoom(String roomId, String participant) {
        requireRoom(roomId);
        redisTemplate.opsForSet().remove(RedisKeys.participants(roomId), participant);
    }

    public Set<Object> getParticipants(String roomId) {
        requireRoom(roomId);
        Set<Object> participants = redisTemplate.opsForSet().members(RedisKeys.participants(roomId));
        return participants == null ? Set.of() : participants;
    }

    public boolean roomExists(String roomId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.metadata(roomId)));
    }

    public void requireRoom(String roomId) {
        if (!roomExists(roomId)) {
            throw new RoomNotFoundException(roomId);
        }
    }

    /**
     * Persists the message on the room list, trims the history and then publishes it.
     * Sending is also treated as joining, so the participants set stays accurate even when
     * a client skips {@code /join}; the message is persisted before the event is published
     * so subscribers that immediately fetch history see it.
     */
    public void sendMessage(String roomId, String participant, String message) {
        requireRoom(roomId);

        redisTemplate.opsForSet().add(RedisKeys.participants(roomId), participant);

        ChatMessage chatMessage = new ChatMessage(participant, message);
        String messagesKey = RedisKeys.messages(roomId);

        Long size = redisTemplate.opsForList().rightPush(messagesKey, chatMessage);
        if (size != null && size > maxHistoryLimit) {
            redisTemplate.opsForList().trim(messagesKey, size - maxHistoryLimit, -1);
        }

        pubSubService.publishMessage(roomId, chatMessage);
    }

    /**
     * Returns the newest {@code limit} messages in chronological order (oldest first).
     * The effective limit is clamped to the configured {@code app.chat.max-history-limit}.
     */
    public List<ChatMessage> getMessages(String roomId, int limit) {
        requireRoom(roomId);

        Long size = redisTemplate.opsForList().size(RedisKeys.messages(roomId));
        if (size == null || size == 0) {
            return List.of();
        }

        int effectiveLimit = Math.max(1, Math.min(limit, maxHistoryLimit));
        long start = Math.max(0, size - effectiveLimit);

        List<Object> raw = redisTemplate.opsForList().range(RedisKeys.messages(roomId), start, -1);
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .filter(ChatMessage.class::isInstance)
                .map(ChatMessage.class::cast)
                .toList();
    }

    /** Removes metadata, participants and message history in one command. */
    public void deleteRoom(String roomId) {
        requireRoom(roomId);
        redisTemplate.delete(List.of(
                RedisKeys.metadata(roomId),
                RedisKeys.participants(roomId),
                RedisKeys.messages(roomId)
        ));
    }
}
