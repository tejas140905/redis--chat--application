package com.freightfox.chatapp.exception;

public class RoomAlreadyExistsException extends RuntimeException {
    private final String roomName;

    public RoomAlreadyExistsException(String roomName) {
        super("Chat room '" + roomName + "' already exists");
        this.roomName = roomName;
    }

    public String getRoomName() {
        return roomName;
    }
}