package com.tv.live.exception;

public class RedirectFailedException extends Exception {
    private final int code;
    private final String location;
    private final String originUrl;

    public RedirectFailedException(String msg, int code, String originUrl, String location) {
        super(msg);
        this.code = code;
        this.originUrl = originUrl;
        this.location = location;
    }

    public int getCode() {
        return code;
    }

    public String getLocation() {
        return location;
    }

    public String getOriginUrl() {
        return originUrl;
    }
}
