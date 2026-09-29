package com.xinhe.nexus.management.service;

import org.springframework.http.HttpStatus;

public class ManagementException extends RuntimeException {
    private final HttpStatus status;

    public ManagementException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
