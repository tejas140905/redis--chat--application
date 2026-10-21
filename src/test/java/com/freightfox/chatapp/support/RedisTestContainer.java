package com.freightfox.chatapp.support;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Redis container shared by every integration test in the JVM.
 *
 * <p>The previous test setup started a container but never registered its host/port with
 * Spring, so tests silently talked to {@code localhost:6379} instead of the container.
 * {@link #register(DynamicPropertyRegistry)} wires the real mapped address into the context.
 */
public final class RedisTestContainer {

    private static GenericContainer<?> container;

    private RedisTestContainer() {
    }

    public static synchronized GenericContainer<?> get() {
        if (container == null) {
            container = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);
            container.start();
            Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
        }
        return container;
    }

    public static void register(DynamicPropertyRegistry registry) {
        String externalHost = System.getenv("TEST_REDIS_HOST");
        if (externalHost != null && !externalHost.isBlank()) {
            // Used when the Docker socket cannot be used by Testcontainers (e.g. Docker Desktop
            // proxies the socket with a minimum API version). The database is real Redis.
            String externalPort = System.getenv().getOrDefault("TEST_REDIS_PORT", "6379");
            registry.add("spring.data.redis.host", () -> externalHost);
            registry.add("spring.data.redis.port", () -> Integer.parseInt(externalPort));
            return;
        }

        GenericContainer<?> redis = get();
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    /** Clears the test database so cases do not leak state into each other. */
    public static void flush(RedisTemplate<String, ?> template) {
        template.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    public static void waitUntil(String description, java.time.Duration timeout, java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + description, ex);
            }
        }
        throw new AssertionError("Timed out after " + timeout + " waiting for " + description);
    }
}
