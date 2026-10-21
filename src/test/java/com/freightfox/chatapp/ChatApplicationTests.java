package com.freightfox.chatapp;

import com.freightfox.chatapp.support.RedisTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class ChatApplicationTests {

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        RedisTestContainer.register(registry);
    }

    @Test
    void contextLoads() {
    }
}
