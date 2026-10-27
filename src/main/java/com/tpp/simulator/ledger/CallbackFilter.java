package com.tpp.simulator.ledger;

import java.time.OffsetDateTime;

/**
 * Bộ lọc danh sách callback (BR19.2), kết hợp bằng AND. Trường null là không lọc.
 *
 * @param receivedFrom cận dưới đã kẹp (UTC), tính cả đầu
 * @param receivedTo cận trên đã kẹp (UTC), tính cả đầu
 */
public record CallbackFilter(
    String serviceCode,
    String transactionId,
    String outcome,
    String rejectionReason,
    OffsetDateTime receivedFrom,
    OffsetDateTime receivedTo) {

  public static CallbackFilter none() {
    return new CallbackFilter(null, null, null, null, null, null);
  }
}
