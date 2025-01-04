package dev.hedge.api;

import java.time.Instant;

public record ApiError(String code, String message, Instant at) {

    static ApiError of(String code, String message) {
        return new ApiError(code, message, Instant.now());
    }
}