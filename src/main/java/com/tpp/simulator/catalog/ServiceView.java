package com.tpp.simulator.catalog;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;

/**
 * Phản hồi {@code Service}. KHÔNG có {@code apiKeySecret} (R-03, NFR3.4): chỉ có {@code
 * apiKeyConfigured}.
 *
 * @param responseBody văn bản JSON cố định đã cấu hình, ghi ra nguyên dạng
 */
public record ServiceView(
    String serviceCode,
    String name,
    String description,
    boolean enabled,
    boolean deleted,
    String authType,
    String apiKeyHeaderName,
    boolean apiKeyConfigured,
    String transactionIdPath,
    String statusPath,
    String timestampPath,
    String timestampFormat,
    String timestampPattern,
    String defaultTimeZone,
    int responseHttpStatus,
    @JsonRawValue String responseBody,
    String callbackPath,
    int currentVersionNumber,
    Instant createdAt,
    Instant updatedAt) {}
