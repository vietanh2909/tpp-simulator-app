package com.tpp.simulator.catalog;

import com.tpp.simulator.mapping.TimestampFormat;
import java.time.ZoneId;

/**
 * Ảnh dịch vụ bất biến của MỘT request nhận callback (BR14.1): đã xóa, bật/tắt, kiểu xác thực, tên
 * header khóa, khóa, cùng phiên bản cấu hình hiện hành, đọc bằng một truy vấn (SC-02). Đây là nơi
 * DUY NHẤT trong ứng dụng giữ giá trị khóa API; {@link #toString()} không bao giờ in nó (SD-03).
 *
 * @param serviceCode mã đúng chữ gốc lúc tạo
 * @param apiKeySecret khóa lưu nguyên văn; null khi {@code authType} là {@code NONE}
 * @param responseBody văn bản JSON cố định của response đã cấu hình
 */
public record ServiceRuntimeSnapshot(
    String serviceCode,
    boolean enabled,
    boolean deleted,
    String authType,
    String apiKeyHeaderName,
    String apiKeySecret,
    long versionId,
    int versionNumber,
    String transactionIdPath,
    String statusPath,
    String timestampPath,
    TimestampFormat timestampFormat,
    String timestampPattern,
    ZoneId defaultTimeZone,
    int responseHttpStatus,
    String responseBody) {

  public boolean requiresApiKey() {
    return "API_KEY".equals(authType);
  }

  /** Biến thể ảnh ở trạng thái tắt (công cụ nạp mô phỏng lý do SERVICE_DISABLED, BR22.4). */
  public ServiceRuntimeSnapshot withEnabled(boolean value) {
    return new ServiceRuntimeSnapshot(
        serviceCode,
        value,
        deleted,
        authType,
        apiKeyHeaderName,
        apiKeySecret,
        versionId,
        versionNumber,
        transactionIdPath,
        statusPath,
        timestampPath,
        timestampFormat,
        timestampPattern,
        defaultTimeZone,
        responseHttpStatus,
        responseBody);
  }

  /** Biến thể ảnh ở trạng thái đã xóa (lý do SERVICE_DELETED). */
  public ServiceRuntimeSnapshot withDeleted(boolean value) {
    return new ServiceRuntimeSnapshot(
        serviceCode,
        enabled,
        value,
        authType,
        apiKeyHeaderName,
        apiKeySecret,
        versionId,
        versionNumber,
        transactionIdPath,
        statusPath,
        timestampPath,
        timestampFormat,
        timestampPattern,
        defaultTimeZone,
        responseHttpStatus,
        responseBody);
  }

  /** Biến thể ảnh {@code API_KEY} với header và khóa cho trước (công cụ nạp, PD-08). */
  public ServiceRuntimeSnapshot withApiKey(String headerName, String secret) {
    return new ServiceRuntimeSnapshot(
        serviceCode,
        enabled,
        deleted,
        "API_KEY",
        headerName,
        secret,
        versionId,
        versionNumber,
        transactionIdPath,
        statusPath,
        timestampPath,
        timestampFormat,
        timestampPattern,
        defaultTimeZone,
        responseHttpStatus,
        responseBody);
  }

  @Override
  public String toString() {
    return "ServiceRuntimeSnapshot[serviceCode="
        + serviceCode
        + ", enabled="
        + enabled
        + ", deleted="
        + deleted
        + ", authType="
        + authType
        + ", versionNumber="
        + versionNumber
        + "]";
  }
}
