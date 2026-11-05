package com.kodegladiators.kaasutracker.exception;

public record ValidationError(String field, String message) {
}
