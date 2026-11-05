package com.kodegladiators.kaasutracker.exception;

import java.time.Instant;
import java.util.List;

public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<ValidationError> validationErrors
) {
    public ApiErrorResponse {
        validationErrors = List.copyOf(validationErrors);
    }
}
