package com.tpp.simulator.ledger;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;

/**
 * Chi tiết một lần nhận (CallbackDetail của hợp đồng C4). Các trường JSON đọc từ jsonb được ghi ra
 * nguyên dạng để không phải dựng cây ở bộ nhớ lần hai. {@code payloadJson} là dạng đã chuẩn hóa của
 * jsonb (BR5.3, BR19.6).
 *
 * <p>Chỉ MỘT trong {@code payloadJson} và {@code payloadRawText} có mặt (BR15.4): {@code
 * payloadRawText} vắng thì payload nằm ở {@code payloadJson} (kể cả khi là JSON {@code null});
 * {@code payloadRawText} có mặt (kể cả chuỗi rỗng) thì {@code payloadJson} vắng.
 */
public record CallbackDetail(
    long recordId,
    String serviceCode,
    boolean serviceDeleted,
    Instant receivedAt,
    String outcome,
    String rejectionReason,
    int httpStatusReturned,
    String transactionId,
    String status,
    Instant businessTimestamp,
    Integer configVersionNumber,
    @JsonRawValue String requestHeaders,
    @JsonRawValue @JsonInclude(JsonInclude.Include.NON_NULL) String payloadJson,
    @JsonInclude(JsonInclude.Include.NON_NULL) String payloadRawText,
    @JsonRawValue String mappingFieldResults,
    Integer responseStatusReturned,
    @JsonRawValue String responseBodyReturned) {}
