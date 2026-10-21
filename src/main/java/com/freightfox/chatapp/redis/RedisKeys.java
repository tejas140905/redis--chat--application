package com.freightfox.chatapp.redis;

/**
 * Single source of truth for Redis key and channel naming.
 *
 * <p>Room data is spread over three keys and one Pub/Sub channel:
 * <ul>
 *   <li>{@code chat:room:{id}:metadata} — hash (name, createdAt)</li>
 *   <li>{@code chat:room:{id}:participants} — set</li>
 *   <li>{@code chat:room:{id}:messages} — list (newest at the tail)</li>
 *   <li>{@code chat:room:{id}:messages} — Pub/Sub channel</li>
 * </ul>
 */
public final class RedisKeys {

    private static final String ROOM_PREFIX = "chat:room:";
    private static final String MESSAGES_SUFFIX = ":messages";

    private RedisKeys() {
    }

    public static String metadata(String roomId) {
        return ROOM_PREFIX + roomId + ":metadata";
    }

    public static String participants(String roomId) {
        return ROOM_PREFIX + roomId + ":participants";
    }

    public static String messages(String roomId) {
        return ROOM_PREFIX + roomId + MESSAGES_SUFFIX;
    }

    public static String channel(String roomId) {
        return ROOM_PREFIX + roomId + MESSAGES_SUFFIX;
    }

    /** Pattern used by the Pub/Sub listener container to subscribe to every room. */
    public static String channelPattern() {
        return ROOM_PREFIX + "*" + MESSAGES_SUFFIX;
    }

    /** Extracts the room id from a channel name, or {@code null} if the channel is not a room channel. */
    public static String roomIdFromChannel(String channel) {
        if (channel == null || !channel.startsWith(ROOM_PREFIX) || !channel.endsWith(MESSAGES_SUFFIX)) {
            return null;
        }
        String roomId = channel.substring(ROOM_PREFIX.length(), channel.length() - MESSAGES_SUFFIX.length());
        return roomId.isEmpty() ? null : roomId;
    }
}
