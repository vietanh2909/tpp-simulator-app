package com.tpp.simulator.config;

/** Một lỗi trường trong {@code fieldErrors} của {@code ErrorResponse}. */
public record FieldError(String field, String message) {}
