package com.freightfox.chatapp.listener;

import com.freightfox.chatapp.model.ChatMessage;
import com.freightfox.chatapp.service.RealtimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatMessageListenerTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private RealtimeService realtimeService;

    @Mock
    private Message message;

    @SuppressWarnings("unchecked")
    private final RedisSerializer<Object> serializer = mock(RedisSerializer.class);

    private ChatMessageListener listener;

    @BeforeEach
    void setUp() {
        listener = new ChatMessageListener(redisTemplate, realtimeService);
        lenient().doReturn(serializer).when(redisTemplate).getValueSerializer();
    }

    @Test
    void onMessage_forwardsPayloadToTheRoomFromTheChannel() {
        ChatMessage payload = new ChatMessage("alice", "hello");
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);

        when(message.getChannel()).thenReturn("chat:room:general:messages".getBytes(StandardCharsets.UTF_8));
        when(message.getBody()).thenReturn(body);
        when(serializer.deserialize(body)).thenReturn(payload);

        listener.onMessage(message, null);

        verify(realtimeService).broadcast("general", payload);
    }

    @Test
    void onMessage_ignoresChannelsThatAreNotRoomChannels() {
        when(message.getChannel()).thenReturn("some:other:channel".getBytes(StandardCharsets.UTF_8));

        listener.onMessage(message, null);

        verify(realtimeService, never()).broadcast(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void onMessage_ignoresUnexpectedPayloadType() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);

        when(message.getChannel()).thenReturn("chat:room:general:messages".getBytes(StandardCharsets.UTF_8));
        when(message.getBody()).thenReturn(body);
        when(serializer.deserialize(body)).thenReturn("not a chat message");

        listener.onMessage(message, null);

        verify(realtimeService, never()).broadcast(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void onMessage_swallowsDeserializationFailures() {
        byte[] body = "broken".getBytes(StandardCharsets.UTF_8);

        when(message.getChannel()).thenReturn("chat:room:general:messages".getBytes(StandardCharsets.UTF_8));
        when(message.getBody()).thenReturn(body);
        when(serializer.deserialize(body)).thenThrow(new RuntimeException("corrupt payload"));

        assertThatCode(() -> listener.onMessage(message, null)).doesNotThrowAnyException();
        verify(realtimeService, never()).broadcast(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }
}
