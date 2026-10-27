package com.tpp.simulator.ledger;

import com.tpp.simulator.config.MaskedHeaders;
import java.time.Instant;

/**
 * Một lần nhận bị từ chối, sẵn sàng ghi (BR15.2): kết quả ánh xạ rỗng, không transactionId, status,
 * timestamp nghiệp vụ. Header chỉ đi vào dưới kiểu {@link MaskedHeaders}.
 *
 * @param rejectionReason một trong sáu lý do của BR14.6
 * @param httpStatus mã HTTP của response từ chối
 * @param payloadJson văn bản JSON hợp lệ lưu trung thực được; null nếu lưu ở dạng văn bản gốc
 * @param payloadRawText văn bản gốc; null nếu payload nằm ở {@code payloadJson}
 * @param responseBodyJson thân lỗi đã (sẽ) trả, dạng văn bản JSON
 */
public record RejectedCallback(
    String serviceCode,
    long configVersionId,
    Instant receivedAt,
    String rejectionReason,
    int httpStatus,
    MaskedHeaders requestHeaders,
    String payloadJson,
    String payloadRawText,
    String responseBodyJson) {}
