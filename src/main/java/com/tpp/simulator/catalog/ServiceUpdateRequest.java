package com.tpp.simulator.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Thân yêu cầu {@code PUT /api/services/{serviceCode}} (ServiceUpdate, BR12.1). Thay toàn bộ trường
 * cấu hình; {@code serviceCode}, {@code enabled}, {@code deleted} và trường lạ trong thân bị bỏ qua
 * nên không có ở đây. Mọi trường là kiểu bao để phân biệt "thiếu" với giá trị mặc định.
 */
public record ServiceUpdateRequest(
    String name,
    String description,
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
