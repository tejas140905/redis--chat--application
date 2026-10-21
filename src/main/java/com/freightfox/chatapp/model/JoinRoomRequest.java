package com.freightfox.chatapp.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class JoinRoomRequest {

    @NotBlank(message = "Participant name is required")
    @Size(max = 64, message = "Participant name must be at most 64 characters")
    private String participant;

    public JoinRoomRequest() {}

    public JoinRoomRequest(String participant) {
        this.participant = participant;
    }

    public String getParticipant() {
        return participant;
    }

    public void setParticipant(String participant) {
        this.participant = participant;
    }
}
