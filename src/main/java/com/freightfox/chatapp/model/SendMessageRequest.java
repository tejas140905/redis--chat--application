package com.freightfox.chatapp.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class SendMessageRequest {

    @NotBlank(message = "Participant name is required")
    @Size(max = 64, message = "Participant name must be at most 64 characters")
    private String participant;

    @NotBlank(message = "Message content is required")
    @Size(max = 2000, message = "Message must be at most 2000 characters")
    private String message;

    public SendMessageRequest() {}

    public SendMessageRequest(String participant, String message) {
        this.participant = participant;
        this.message = message;
    }

    public String getParticipant() {
        return participant;
    }

    public void setParticipant(String participant) {
        this.participant = participant;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
