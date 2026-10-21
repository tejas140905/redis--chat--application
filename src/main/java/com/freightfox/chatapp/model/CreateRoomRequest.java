package com.freightfox.chatapp.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class CreateRoomRequest {

    @NotBlank(message = "Room name is required")
    @Pattern(regexp = ValidationRules.ROOM_ID_PATTERN, message = "roomName " + ValidationRules.ROOM_ID_MESSAGE)
    private String roomName;

    public CreateRoomRequest() {}

    public CreateRoomRequest(String roomName) {
        this.roomName = roomName;
    }

    public String getRoomName() {
        return roomName;
    }

    public void setRoomName(String roomName) {
        this.roomName = roomName;
    }
}
