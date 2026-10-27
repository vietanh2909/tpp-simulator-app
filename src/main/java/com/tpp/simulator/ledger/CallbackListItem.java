package com.tpp.simulator.ledger;

import java.time.Instant;

/** Một dòng danh sách callback (BR19.5): cả ba kết quả SUCCESS, MAPPING_ERROR, REJECTED. */
public record CallbackListItem(
    long recordId,
    String serviceCode,
    boolean serviceDeleted,
    Instant receivedAt,
    String outcome,
    String rejectionReason,
    int httpStatusReturned,
    String transactionId,
    String status,
    Instant businessTimestamp) {}
