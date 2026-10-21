package com.freightfox.chatapp.exception;

public class RoomNotFoundException extends RuntimeException {
    private final String roomId;

    public RoomNotFoundException(String roomId) {
        super("Chat room '" + roomId + "' not found");
        this.roomId = roomId;
    }

    public String getRoomId() {
        return roomId;
    }
}