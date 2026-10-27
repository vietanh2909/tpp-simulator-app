package com.tpp.simulator.ledger;

import com.tpp.simulator.config.MaskedHeaders;
import java.time.Instant;

/**
 * Một lần nhận callback được chấp nhận, sẵn sàng ghi. Header chỉ đi vào dưới kiểu {@link
 * MaskedHeaders} (đã che).
 *
 * @param outcome {@code SUCCESS} hoặc {@code MAPPING_ERROR}
 * @param payloadJson văn bản JSON hợp lệ của payload gốc
 * @param transactionId giá trị đã ánh xạ; null khi lỗi ánh xạ (khi đó callback không thuộc giao
 *     dịch nào, BR4.2)
 * @param status giá trị đã ánh xạ; null khi lỗi ánh xạ
 * @param mappingFieldResultsJson JSON của kết quả ba trường
 * @param responseStatus mã HTTP đã (sẽ) trả cho lần nhận này
 * @param responseBody văn bản JSON đã (sẽ) trả
 */
public record NewCallback(
    String serviceCode,
    long configVersionId,
    Instant receivedAt,
    String outcome,
    MaskedHeaders requestHeaders,
    String payloadJson,
    String transactionId,
    String status,
    Instant businessTimestampUtc,
    String mappingFieldResultsJson,
    int responseStatus,
    String responseBody) {

  boolean mappingError() {
    return "MAPPING_ERROR".equals(outcome);
  }
}
