package com.tpp.simulator.mapping;

import java.time.Instant;

/**
 * Kết quả ánh xạ một payload.
 *
 * @param businessTimestampUtc timestamp nghiệp vụ đã chuẩn hóa UTC (cắt về micro giây); null khi
 *     thiếu hoặc lỗi
 */
public record MappingResult(
    FieldResult transactionId,
    FieldResult status,
    FieldResult timestamp,
    Instant businessTimestampUtc,
    MappingOutcome outcome) {}
