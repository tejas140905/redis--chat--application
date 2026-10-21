package com.freightfox.chatapp.service;

import com.freightfox.chatapp.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PubSubServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @InjectMocks
    private PubSubService pubSubService;

    @Test
    void publishMessage_sendsToRoomChannel() {
        ChatMessage message = new ChatMessage("alice", "hello");

        pubSubService.publishMessage("general", message);

        verify(redisTemplate).convertAndSend("chat:room:general:messages", message);
    }
}
