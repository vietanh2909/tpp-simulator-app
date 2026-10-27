package com.tpp.simulator.ledger;

import java.time.Instant;

/** Một dòng giao dịch (TransactionSummary của hợp đồng C4). */
public record TransactionSummary(
    String serviceCode,
    boolean serviceDeleted,
    String transactionId,
    String latestStatus,
    Instant latestBusinessTimestamp,
    Instant latestReceivedAt,
    long latestCallbackId,
    boolean latestHasMappingError,
    int callbackCount) {}
