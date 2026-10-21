package com.freightfox.chatapp.controller;

import com.freightfox.chatapp.exception.RoomAlreadyExistsException;
import com.freightfox.chatapp.exception.RoomNotFoundException;
import com.freightfox.chatapp.model.ChatMessage;
import com.freightfox.chatapp.service.ChatRoomService;
import com.freightfox.chatapp.service.RealtimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatRoomService chatRoomService;

    @MockBean
    private RealtimeService realtimeService;

    // --- create room -----------------------------------------------------

    @Test
    void createRoom_returnsSuccess() throws Exception {
        mockMvc.perform(post("/api/chatapp/chatrooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomName\":\"general\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.roomId").value("general"));
    }

    @Test
    void createRoom_whenRoomExists_returns409() throws Exception {
        doThrow(new RoomAlreadyExistsException("general")).when(chatRoomService).createRoom("general");

        mockMvc.perform(post("/api/chatapp/chatrooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomName\":\"general\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Chat room 'general' already exists"));
    }

    @Test
    void createRoom_withBlankName_returns400() throws Exception {
        // A blank name violates both @NotBlank and @Pattern; either message is a valid rejection.
        mockMvc.perform(post("/api/chatapp/chatrooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("roomName")));
    }

    @Test
    void createRoom_withIllegalCharacters_returns400() throws Exception {
        mockMvc.perform(post("/api/chatapp/chatrooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomName\":\"bad/name\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void createRoom_withMalformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/chatapp/chatrooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{roomName:general}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or missing JSON request body"));
    }

    // --- join ------------------------------------------------------------

    @Test
    void joinRoom_returnsSuccess() throws Exception {
        mockMvc.perform(post("/api/chatapp/chatrooms/general/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participant\":\"user1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void joinRoom_whenRoomMissing_returns404() throws Exception {
        doThrow(new RoomNotFoundException("general")).when(chatRoomService).joinRoom("general", "user1");

        mockMvc.perform(post("/api/chatapp/chatrooms/general/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participant\":\"user1\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Chat room 'general' not found"));
    }

    @Test
    void joinRoom_withBlankParticipant_returns400() throws Exception {
        mockMvc.perform(post("/api/chatapp/chatrooms/general/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participant\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("participant: Participant name is required"));
    }

    // --- send message ----------------------------------------------------

    @Test
    void sendMessage_returnsSuccess() throws Exception {
        mockMvc.perform(post("/api/chatapp/chatrooms/general/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participant\":\"user1\",\"message\":\"Hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        verify(chatRoomService).sendMessage("general", "user1", "Hello");
    }

    @Test
    void sendMessage_whenRoomMissing_returns404() throws Exception {
        doThrow(new RoomNotFoundException("general"))
                .when(chatRoomService).sendMessage("general", "user1", "Hello");

        mockMvc.perform(post("/api/chatapp/chatrooms/general/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participant\":\"user1\",\"message\":\"Hello\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sendMessage_withBlankMessage_returns400() throws Exception {
        mockMvc.perform(post("/api/chatapp/chatrooms/general/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participant\":\"user1\",\"message\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("message: Message content is required"));
    }

    // --- history ---------------------------------------------------------

    @Test
    void getMessages_returnsMessages() throws Exception {
        ChatMessage msg = new ChatMessage("user1", "Hello");
        when(chatRoomService.getMessages("general", 10)).thenReturn(List.of(msg));

        mockMvc.perform(get("/api/chatapp/chatrooms/general/messages?limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].participant").value("user1"))
                .andExpect(jsonPath("$.messages[0].message").value("Hello"))
                .andExpect(jsonPath("$.messages[0].timestamp").isString());
    }

    @Test
    void getMessages_withoutLimit_usesConfiguredDefault() throws Exception {
        when(chatRoomService.getMessages("general", 50)).thenReturn(List.of());

        mockMvc.perform(get("/api/chatapp/chatrooms/general/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isArray());

        verify(chatRoomService).getMessages("general", 50);
    }

    @Test
    void getMessages_withLimitZero_returns400() throws Exception {
        mockMvc.perform(get("/api/chatapp/chatrooms/general/messages?limit=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void getMessages_withNegativeLimit_returns400() throws Exception {
        mockMvc.perform(get("/api/chatapp/chatrooms/general/messages?limit=-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getMessages_withLimitAboveMaximum_returns400() throws Exception {
        mockMvc.perform(get("/api/chatapp/chatrooms/general/messages?limit=101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getMessages_withNonNumericLimit_returns400() throws Exception {
        mockMvc.perform(get("/api/chatapp/chatrooms/general/messages?limit=abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void getMessages_whenRoomMissing_returns404() throws Exception {
        doThrow(new RoomNotFoundException("general")).when(chatRoomService).getMessages("general", 10);

        mockMvc.perform(get("/api/chatapp/chatrooms/general/messages?limit=10"))
                .andExpect(status().isNotFound());
    }

    // --- real-time stream ------------------------------------------------

    @Test
    void stream_returnsEventStream() throws Exception {
        SseEmitter emitter = new SseEmitter(60_000L);
        when(realtimeService.subscribe("general")).thenReturn(emitter);

        mockMvc.perform(get("/api/chatapp/chatrooms/general/stream"))
                .andExpect(request().asyncStarted());

        verify(chatRoomService).requireRoom("general");
        emitter.complete();
    }

    @Test
    void stream_whenRoomMissing_returns404() throws Exception {
        doThrow(new RoomNotFoundException("general")).when(chatRoomService).requireRoom("general");

        mockMvc.perform(get("/api/chatapp/chatrooms/general/stream"))
                .andExpect(status().isNotFound());
    }

    // --- delete ----------------------------------------------------------

    @Test
    void deleteRoom_returnsSuccess() throws Exception {
        mockMvc.perform(delete("/api/chatapp/chatrooms/general"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        verify(chatRoomService).deleteRoom("general");
    }

    @Test
    void deleteRoom_whenRoomMissing_returns404() throws Exception {
        doThrow(new RoomNotFoundException("general")).when(chatRoomService).deleteRoom("general");

        mockMvc.perform(delete("/api/chatapp/chatrooms/general"))
                .andExpect(status().isNotFound());
    }
}
