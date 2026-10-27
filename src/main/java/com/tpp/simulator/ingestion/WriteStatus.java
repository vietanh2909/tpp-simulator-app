package com.tpp.simulator.ingestion;

/**
 * Trạng thái ghi tường minh của một lần xử lý (RD-02). {@code CallbackIngestionService} là nơi duy
 * nhất bắt lỗi kho dữ liệu và luôn trả một trong các giá trị này thay vì ném ngoại lệ.
 */
public enum WriteStatus {
  /** Bản ghi (callback được chấp nhận hoặc lần nhận bị từ chối) đã commit. */
  WRITTEN,
  /** Callback được chấp nhận không ghi được; giao dịch đã hủy. */
  ACCEPTED_WRITE_FAILED,
  /** Lần nhận bị từ chối không ghi được; kết quả vẫn mang mã từ chối gốc (BR15.6). */
  REJECTED_RECORD_FAILED,
  /** Không đọc được ảnh dịch vụ (BR14.9); chỉ có ở lối vào HTTP. */
  SNAPSHOT_READ_FAILED
}
