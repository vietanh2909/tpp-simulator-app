package com.tpp.simulator.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Thân yêu cầu {@code POST /api/services} (ServiceCreate). Mọi trường là kiểu bao (nullable) để
 * phân biệt "thiếu" với giá trị mặc định; kiểm tra do {@link ServiceValidator} làm và thu thập mọi
 * lỗi cùng lúc.
 */
public record ServiceCreateRequest(
    String serviceCode,
    String name,
    String description,
    Boolean enabled,
    String authType,
    String apiKeyHeaderName,
    String apiKeySecret,
    String transactionIdPath,
    String statusPath,
    String timestampPath,
    String timestampFormat,
    String timestampPattern,
    String defaultTimeZone,
    Integer responseHttpStatus,
    @JsonDeserialize(using = RawJsonBodyDeserializer.class) JsonNode responseBody) {}
