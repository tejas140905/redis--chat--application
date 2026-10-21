package com.freightfox.chatapp.model;

import java.util.List;

public class ChatHistoryResponse {
    private List<ChatMessage> messages;

    public ChatHistoryResponse() {}

    public ChatHistoryResponse(List<ChatMessage> messages) {
        this.messages = messages;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages;
    }
}