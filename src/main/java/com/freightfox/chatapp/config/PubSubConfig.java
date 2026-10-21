package com.freightfox.chatapp.config;

import com.freightfox.chatapp.listener.ChatMessageListener;
import com.freightfox.chatapp.redis.RedisKeys;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

@Configuration
public class PubSubConfig {

    /**
     * The application's only listener container. It subscribes to every room channel
     * ({@code chat:room:*:messages}) so that new rooms are covered without reconfiguration.
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter messageListenerAdapter) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(messageListenerAdapter, new PatternTopic(RedisKeys.channelPattern()));
        return container;
    }

    @Bean
    public MessageListenerAdapter messageListenerAdapter(ChatMessageListener listener) {
        return new MessageListenerAdapter(listener, "onMessage");
    }
}
