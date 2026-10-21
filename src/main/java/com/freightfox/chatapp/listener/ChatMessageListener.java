package com.freightfox.chatapp.listener;

import com.freightfox.chatapp.model.ChatMessage;
import com.freightfox.chatapp.redis.RedisKeys;
import com.freightfox.chatapp.service.RealtimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Receives every room broadcast from Redis Pub/Sub and hands it to {@link RealtimeService},
 * which writes it to the SSE clients subscribed to that room.
 *
 * <p>This is the only path from a persisted message to a connected client; the sending
 * request never writes to clients directly, so a message is delivered exactly once.
 */
@Component
public class ChatMessageListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageListener.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final RealtimeService realtimeService;

    public ChatMessageListener(RedisTemplate<String, Object> redisTemplate, RealtimeService realtimeService) {
        this.redisTemplate = redisTemplate;
        this.realtimeService = realtimeService;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            String roomId = RedisKeys.roomIdFromChannel(channel);
            if (roomId == null) {
                log.warn("Ignoring Pub/Sub message on unsupported channel '{}'", channel);
                return;
            }

            Object payload = redisTemplate.getValueSerializer().deserialize(message.getBody());
            if (!(payload instanceof ChatMessage chatMessage)) {
                log.warn("Ignoring Pub/Sub message of unexpected type on channel '{}'", channel);
                return;
            }

            realtimeService.broadcast(roomId, chatMessage);
            log.debug("Delivered message from '{}' to room '{}' ({} subscriber(s))",
                    chatMessage.getParticipant(), roomId, realtimeService.subscriberCount(roomId));
        } catch (Exception ex) {
            // Never rethrow: a failed delivery must not kill the listener thread.
            log.error("Failed to process Pub/Sub message", ex);
        }
    }
}
