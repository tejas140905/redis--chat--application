package com.freightfox.chatapp.service;

import com.freightfox.chatapp.model.ChatMessage;
import com.freightfox.chatapp.redis.RedisKeys;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class PubSubService {

    private final RedisTemplate<String, Object> redisTemplate;

    public PubSubService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Publishes to {@code chat:room:{roomId}:messages}; delivery is asynchronous. */
    public void publishMessage(String roomId, ChatMessage message) {
        redisTemplate.convertAndSend(RedisKeys.channel(roomId), message);
    }
}
