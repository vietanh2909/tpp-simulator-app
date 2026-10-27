package com.tpp.simulator.ingestion;

/**
 * Response đã cấu hình của dịch vụ: mã HTTP và body JSON cố định (BR2.8).
 *
 * @param body văn bản JSON cố định
 */
public record ConfiguredResponse(int status, String body) {}
