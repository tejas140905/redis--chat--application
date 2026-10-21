package com.freightfox.chatapp.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI chatOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Freightfox Chat API")
                .version("1.0.0")
                .description("""
                        Spring Boot + Redis chat service.

                        Real-time delivery uses Server-Sent Events:
                        `GET /api/chatapp/chatrooms/{roomId}/stream` returns `text/event-stream`
                        with named events `connected` and `message`. A message POSTed through
                        the REST API is persisted in Redis, published to `chat:room:{roomId}:messages`
                        and forwarded to subscribers of that room only.

                        History: `GET /api/chatapp/chatrooms/{roomId}/messages?limit=N`
                        (1-100, default 50).
                        """)
                .contact(new Contact().name("Freightfox")));
    }
}
