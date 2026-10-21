package com.freightfox.chatapp.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

public class ChatMessage {
    private String participant;
    private String message;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant timestamp;

    public ChatMessage() {
        this.timestamp = Instant.now();
    }

    public ChatMessage(String participant, String message) {
        this.participant = participant;
        this.message = message;
        this.timestamp = Instant.now();
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

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}