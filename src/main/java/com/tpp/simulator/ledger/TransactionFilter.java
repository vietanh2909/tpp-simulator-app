package com.tpp.simulator.ledger;

import java.time.OffsetDateTime;

/**
 * Bộ lọc danh sách giao dịch (BR19.1), kết hợp bằng AND. Trường null là không lọc.
 *
 * @param receivedFrom cận dưới đã kẹp (UTC), tính cả đầu
 * @param receivedTo cận trên đã kẹp (UTC), tính cả đầu
 */
public record TransactionFilter(
    String serviceCode,
    String transactionId,
    String status,
    OffsetDateTime receivedFrom,
    OffsetDateTime receivedTo) {

  public static TransactionFilter none() {
    return new TransactionFilter(null, null, null, null, null);
  }
}
