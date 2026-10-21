package com.freightfox.chatapp.model;

public class ApiResponse {
    private String message;
    private String status;
    private Object data;

    public ApiResponse() {}

    public ApiResponse(String message, String status) {
        this.message = message;
        this.status = status;
    }

    public ApiResponse(String message, String status, Object data) {
        this.message = message;
        this.status = status;
        this.data = data;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public static ApiResponse success(String message) {
        return new ApiResponse(message, "success");
    }

    public static ApiResponse success(String message, Object data) {
        return new ApiResponse(message, "success", data);
    }

    public static ApiResponse error(String message) {
        return new ApiResponse(message, "error");
    }
}