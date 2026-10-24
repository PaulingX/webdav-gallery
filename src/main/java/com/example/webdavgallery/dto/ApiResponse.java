package com.example.webdavgallery.dto;

public class ApiResponse<T> {

    public static final int CODE_OK = 0;
    public static final int CODE_ERROR = 1;

    private int code;
    private String message;
    private T data;

    public ApiResponse() {
    }

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(CODE_OK, "success", data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(CODE_OK, "success", null);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(CODE_ERROR, message, null);
    }

    public int getCode() { return code; }

    public void setCode(int code) { this.code = code; }

    public String getMessage() { return message; }

    public void setMessage(String message) { this.message = message; }

    public T getData() { return data; }

    public void setData(T data) { this.data = data; }
}
