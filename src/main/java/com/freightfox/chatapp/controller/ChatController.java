package com.freightfox.chatapp.controller;

import com.freightfox.chatapp.model.ApiResponse;
import com.freightfox.chatapp.model.ChatHistoryResponse;
import com.freightfox.chatapp.model.ChatMessage;
import com.freightfox.chatapp.model.CreateRoomRequest;
import com.freightfox.chatapp.model.JoinRoomRequest;
import com.freightfox.chatapp.model.RoomInfoResponse;
import com.freightfox.chatapp.model.SendMessageRequest;
import com.freightfox.chatapp.model.ValidationRules;
import com.freightfox.chatapp.service.ChatRoomService;
import com.freightfox.chatapp.service.RealtimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chatapp/chatrooms")
@Validated
@Tag(name = "Chat rooms", description = "Room lifecycle, message history and real-time delivery")
public class ChatController {

    private static final String ROOM_ID_MESSAGE = "roomId " + ValidationRules.ROOM_ID_MESSAGE;

    private final ChatRoomService chatRoomService;
    private final RealtimeService realtimeService;

    public ChatController(ChatRoomService chatRoomService, RealtimeService realtimeService) {
        this.chatRoomService = chatRoomService;
        this.realtimeService = realtimeService;
    }

    @PostMapping
    @Operation(summary = "Create a chat room")
    public ResponseEntity<RoomInfoResponse> createRoom(@Valid @RequestBody CreateRoomRequest request) {
        chatRoomService.createRoom(request.getRoomName());
        return ResponseEntity.ok(new RoomInfoResponse(
                "Chat room '" + request.getRoomName() + "' created successfully.",
                request.getRoomName(),
                "success"
        ));
    }

    @PostMapping("/{roomId}/join")
    @Operation(summary = "Join a chat room")
    public ResponseEntity<ApiResponse> joinRoom(
            @PathVariable @Pattern(regexp = ValidationRules.ROOM_ID_PATTERN, message = ROOM_ID_MESSAGE) String roomId,
            @Valid @RequestBody JoinRoomRequest request) {

        chatRoomService.joinRoom(roomId, request.getParticipant());
        return ResponseEntity.ok(ApiResponse.success(
                "User '" + request.getParticipant() + "' joined chat room '" + roomId + "'."
        ));
    }

    @PostMapping("/{roomId}/messages")
    @Operation(summary = "Send a message; it is persisted and broadcast to room subscribers")
    public ResponseEntity<ApiResponse> sendMessage(
            @PathVariable @Pattern(regexp = ValidationRules.ROOM_ID_PATTERN, message = ROOM_ID_MESSAGE) String roomId,
            @Valid @RequestBody SendMessageRequest request) {

        chatRoomService.sendMessage(roomId, request.getParticipant(), request.getMessage());
        return ResponseEntity.ok(ApiResponse.success("Message sent successfully."));
    }

    @GetMapping("/{roomId}/messages")
    @Operation(summary = "Get the newest messages in chronological order")
    public ResponseEntity<ChatHistoryResponse> getMessages(
            @PathVariable @Pattern(regexp = ValidationRules.ROOM_ID_PATTERN, message = ROOM_ID_MESSAGE) String roomId,
            @RequestParam(defaultValue = "${app.chat.default-history-limit:50}")
            @Min(value = 1, message = "limit must be at least 1")
            @Max(value = 100, message = "limit must be at most 100") int limit) {

        return ResponseEntity.ok(new ChatHistoryResponse(chatRoomService.getMessages(roomId, limit)));
    }

    /**
     * Server-Sent Events stream for one room.
     *
     * <p>Events: {@code connected} once on subscribe, {@code message} for every chat message
     * published to {@code chat:room:{roomId}:messages}. Comments ({@code : keep-alive}) are
     * sent periodically as heartbeats and must be ignored by clients.
     */
    @GetMapping(value = "/{roomId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "Subscribe to a room's real-time message stream (SSE)",
            description = "Returns text/event-stream. Named events: 'connected' and 'message'. "
                    + "Use GET /api/chatapp/chatrooms/{roomId}/messages for history.",
            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "SSE stream",
                    content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                            schema = @Schema(implementation = ChatMessage.class))))
    public SseEmitter stream(
            @PathVariable @Pattern(regexp = ValidationRules.ROOM_ID_PATTERN, message = ROOM_ID_MESSAGE) String roomId) {

        chatRoomService.requireRoom(roomId);
        return realtimeService.subscribe(roomId);
    }

    @DeleteMapping("/{roomId}")
    @Operation(summary = "Delete a room and all of its data")
    public ResponseEntity<ApiResponse> deleteRoom(
            @PathVariable @Pattern(regexp = ValidationRules.ROOM_ID_PATTERN, message = ROOM_ID_MESSAGE) String roomId) {

        chatRoomService.deleteRoom(roomId);
        return ResponseEntity.ok(ApiResponse.success("Chat room '" + roomId + "' deleted successfully."));
    }
}
