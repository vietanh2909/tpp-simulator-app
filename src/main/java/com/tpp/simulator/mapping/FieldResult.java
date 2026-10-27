package com.tpp.simulator.mapping;

/**
 * Kết quả ánh xạ của một trong ba trường (lưu trong {@code mappingFieldResults}).
 *
 * @param value giá trị lấy được (timestamp là ISO 8601 UTC); null khi lỗi hoặc bị bỏ qua
 * @param errorCode mã lỗi ổn định của đúng trường này; null nếu không lỗi
 * @param error câu mô tả ngắn cho người đọc, không chứa dữ liệu nhạy cảm
 * @param warning chỉ dùng cho timestamp đã cấu hình đường dẫn mà không có giá trị
 */
public record FieldResult(String value, MappingErrorCode errorCode, String error, String warning) {

  static FieldResult ok(String value) {
    return new FieldResult(value, null, null, null);
  }

  static FieldResult skipped() {
    return new FieldResult(null, null, null, null);
  }

  static FieldResult warn(String warning) {
    return new FieldResult(null, null, null, warning);
  }

  static FieldResult error(MappingErrorCode code, String message) {
    return new FieldResult(null, code, message, null);
  }

  public boolean hasError() {
    return errorCode != null;
  }
}
