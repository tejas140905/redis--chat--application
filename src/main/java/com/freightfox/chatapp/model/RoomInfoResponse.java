package com.freightfox.chatapp.model;

public class RoomInfoResponse {
    private String message;
    private String roomId;
    private String status;

    public RoomInfoResponse() {}

    public RoomInfoResponse(String message, String roomId, String status) {
        this.message = message;
        this.roomId = roomId;
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}