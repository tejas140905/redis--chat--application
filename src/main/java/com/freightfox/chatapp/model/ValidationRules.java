package com.freightfox.chatapp.model;

/**
 * Validation rules shared by request DTOs and path variables, so a room id accepted on
 * creation is always accepted on join/message/stream requests.
 */
public final class ValidationRules {

    public static final String ROOM_ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}$";
    public static final String ROOM_ID_MESSAGE =
            "must start with a letter or digit and contain only letters, digits, spaces, '.', '-' or '_' (max 64 characters)";

    private ValidationRules() {
    }
}
