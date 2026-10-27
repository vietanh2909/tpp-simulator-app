package com.tpp.simulator.catalog;

import com.tpp.simulator.catalog.ServiceConfigComparator.VersionFields;
import com.tpp.simulator.catalog.ServiceValidator.NewService;
import com.tpp.simulator.config.ApiException;
import com.tpp.simulator.config.ErrorCode;
import com.tpp.simulator.config.FieldError;
import com.tpp.simulator.config.LogSanitizer;
import com.tpp.simulator.mapping.TimestampFormat;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tạo, sửa, bật/tắt, xóa mềm, liệt kê và đọc dịch vụ (W7, W11 đến W15). Giao dịch cơ sở dữ liệu mở
 * ở đây, không ở bộ điều khiển (NFR2.1).
 *
 * <p>Sửa, bật/tắt và xóa mềm của CÙNG một dịch vụ dùng chung một phần tuần tự hóa là khóa dòng
 * {@code FOR NO KEY UPDATE} trên dòng {@code services}, là câu dữ liệu đầu tiên của giao dịch
 * (SC-01, BR12.6). Khóa này không xung đột với kiểm tra khóa ngoại của callback đang ghi nên nhận
 * callback không chờ khóa dòng. Trong giao dịch chỉ dùng MỘT kết nối: không gọi phương thức nào mở
 * kết nối thứ hai.
 */
@Service
public class ServiceCatalog {

  private static final Logger log = LoggerFactory.getLogger(ServiceCatalog.class);

  private static final String VIEW_COLUMNS =
      """
      SELECT s.service_code, s.name, s.description, s.enabled, s.deleted, s.auth_type,
             s.api_key_header_name, (s.api_key_secret IS NOT NULL) AS api_key_configured,
             s.current_version_number, s.created_at, s.updated_at,
             v.transaction_id_path, v.status_path, v.timestamp_path, v.timestamp_format,
             v.timestamp_pattern, v.default_time_zone, v.response_http_status, v.response_body
        FROM services s
        JOIN service_config_versions v
          ON v.service_code = s.service_code AND v.version_number = s.current_version_number
      """;

  private static final String SELECT_VIEW =
      VIEW_COLUMNS + " WHERE lower(s.service_code) = lower(:code)";

  private static final String LIST_ACTIVE =
      VIEW_COLUMNS + " WHERE NOT s.deleted ORDER BY lower(s.service_code), s.service_code";

  private static final String LIST_ALL =
      VIEW_COLUMNS + " ORDER BY lower(s.service_code), s.service_code";

  /** Ảnh dịch vụ của đường nhận callback: nơi DUY NHẤT chọn giá trị khóa API (SD-03). */
  private static final String SELECT_SNAPSHOT =
      """
      SELECT s.service_code, s.enabled, s.deleted, s.auth_type, s.api_key_header_name,
             s.api_key_secret, v.version_id, v.version_number, v.transaction_id_path,
             v.status_path, v.timestamp_path, v.timestamp_format, v.timestamp_pattern,
             v.default_time_zone, v.response_http_status, v.response_body
        FROM services s
        JOIN service_config_versions v
          ON v.service_code = s.service_code AND v.version_number = s.current_version_number
       WHERE lower(s.service_code) = lower(:code)
      """;

  private static final String SET_LOCK_TIMEOUT = "SET LOCAL lock_timeout = '5s'";

  private static final String LOCK_SERVICE =
      """
      SELECT s.service_code, s.name, s.description, s.enabled, s.deleted, s.auth_type,
             s.api_key_header_name, (s.api_key_secret IS NOT NULL) AS api_key_configured,
             s.current_version_number
        FROM services s
       WHERE lower(s.service_code) = lower(:code)
         FOR NO KEY UPDATE
      """;

  private static final String SELECT_VERSION =
      """
      SELECT transaction_id_path, status_path, timestamp_path, timestamp_format, timestamp_pattern,
             default_time_zone, response_http_status, response_body
        FROM service_config_versions
       WHERE service_code = :code AND version_number = :version
      """;

  private static final String INSERT_SERVICE =
      """
      INSERT INTO services (service_code, name, description, enabled, deleted, auth_type,
                            api_key_header_name, api_key_secret, current_version_number,
                            created_at, updated_at)
      VALUES (:code, :name, :description, :enabled, false, :authType, :headerName, :secret, 1,
              :now, :now)
      """;

  private static final String INSERT_VERSION =
      """
      INSERT INTO service_config_versions (service_code, version_number, transaction_id_path,
          status_path, timestamp_path, timestamp_format, timestamp_pattern, default_time_zone,
          response_http_status, response_body, created_at)
      VALUES (:code, :version, :txPath, :statusPath, :tsPath, :tsFormat, :tsPattern, :zone,
              :httpStatus, :body, :now)
      """;

  // Ba biến thể cập nhật (giữ khóa, đặt khóa mới, xóa khóa): không bao giờ ghi enabled/deleted.
  private static final String UPDATE_SERVICE_KEEP_KEY =
      """
      UPDATE services SET name = :name, description = :description, auth_type = :authType,
             api_key_header_name = :headerName, current_version_number = :version,
             updated_at = :now
       WHERE service_code = :code
      """;

  private static final String UPDATE_SERVICE_SET_KEY =
      """
      UPDATE services SET name = :name, description = :description, auth_type = :authType,
             api_key_header_name = :headerName, api_key_secret = :secret,
             current_version_number = :version, updated_at = :now
       WHERE service_code = :code
      """;

  private static final String UPDATE_SERVICE_CLEAR_KEY =
      """
      UPDATE services SET name = :name, description = :description, auth_type = :authType,
             api_key_header_name = NULL, api_key_secret = NULL,
             current_version_number = :version, updated_at = :now
       WHERE service_code = :code
      """;

  private static final String UPDATE_ENABLED =
      "UPDATE services SET enabled = :enabled, updated_at = :now WHERE service_code = :code";

  private static final String UPDATE_DELETED =
      "UPDATE services SET deleted = true, updated_at = :now WHERE service_code = :code";

  private final JdbcClient jdbc;
  private final Clock clock;
  private final ServiceValidator validator;

  public ServiceCatalog(JdbcClient jdbc, Clock clock, ServiceValidator validator) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.validator = validator;
  }

  /** Dòng dịch vụ đã khóa: trạng thái đã ghi bền mới nhất, nền của mọi kiểm tra trong khóa. */
  private record Locked(
      String serviceCode,
      String name,
      String description,
      boolean enabled,
      boolean deleted,
      String authType,
      String headerName,
      boolean keyConfigured,
      int versionNumber) {}

  private OffsetDateTime now() {
    return OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
  }

  /** W11: kiểm tra, rồi ghi Service và ServiceConfigVersion số 1 trong một giao dịch. */
  @Transactional
  public ServiceView create(ServiceCreateRequest request) {
    NewService service = validator.validateCreate(request);
    OffsetDateTime now = now();
    try {
      jdbc.sql(INSERT_SERVICE)
          .param("code", service.serviceCode())
          .param("name", service.name())
          .param("description", service.description())
          .param("enabled", service.enabled())
          .param("authType", service.authType())
          .param("headerName", service.apiKeyHeaderName())
          .param("secret", service.apiKeySecret())
          .param("now", now)
          .update();
    } catch (DuplicateKeyException e) {
      throw new ApiException(
          ErrorCode.SERVICE_CODE_DUPLICATE,
          "Mã dịch vụ đã tồn tại (không phân biệt hoa thường).",
          List.of(),
          LogSanitizer.clean(service.serviceCode()));
    }
    insertVersion(service, 1, now);
    log.info("Service created: service={} version=1", LogSanitizer.clean(service.serviceCode()));
    return view(service.serviceCode());
  }

  /** W12: sửa dịch vụ; toàn bộ phần từ khóa dòng trở đi chạy trong MỘT giao dịch. */
  @Transactional
  public ServiceView update(String serviceCode, ServiceUpdateRequest request) {
    Locked locked = lock(serviceCode);
    NewService next = validator.validateUpdate(request, locked.authType(), locked.keyConfigured());

    VersionFields current = readVersion(locked.serviceCode(), locked.versionNumber());
    boolean newVersion = ServiceConfigComparator.differs(current, versionFields(next));
    boolean serviceChanged =
        !Objects.equals(locked.name(), next.name())
            || !Objects.equals(locked.description(), next.description())
            || !Objects.equals(locked.authType(), next.authType())
            || !Objects.equals(locked.headerName(), next.apiKeyHeaderName())
            || next.apiKeySecret() != null;
    if (!newVersion && !serviceChanged) {
      return view(locked.serviceCode());
    }

    OffsetDateTime now = now();
    int version = newVersion ? locked.versionNumber() + 1 : locked.versionNumber();
    String sql;
    if (!"API_KEY".equals(next.authType())) {
      sql = UPDATE_SERVICE_CLEAR_KEY;
    } else if (next.apiKeySecret() != null) {
      sql = UPDATE_SERVICE_SET_KEY;
    } else {
      sql = UPDATE_SERVICE_KEEP_KEY;
    }
    JdbcClient.StatementSpec update =
        jdbc.sql(sql)
            .param("code", locked.serviceCode())
            .param("name", next.name())
            .param("description", next.description())
            .param("authType", next.authType())
            .param("version", version)
            .param("now", now);
    if (!"API_KEY".equals(next.authType())) {
      update.update();
    } else if (next.apiKeySecret() != null) {
      update
          .param("headerName", next.apiKeyHeaderName())
          .param("secret", next.apiKeySecret())
          .update();
    } else {
      update.param("headerName", next.apiKeyHeaderName()).update();
    }
    if (newVersion) {
      insertVersion(next, version, now, locked.serviceCode());
    }
    log.info(
        "Service updated: service={} version={} newVersion={}",
        LogSanitizer.clean(locked.serviceCode()),
        version,
        newVersion);
    return view(locked.serviceCode());
  }

  /** W13: bật hoặc tắt; giống giá trị hiện tại thì không ghi gì. Không bao giờ sinh phiên bản. */
  @Transactional
  public ServiceView setEnabled(String serviceCode, ServiceEnabledRequest request) {
    Locked locked = lock(serviceCode);
    if (request.enabled() == null) {
      throw new ApiException(
          ErrorCode.VALIDATION_FAILED,
          "Thân yêu cầu không hợp lệ.",
          List.of(new FieldError("enabled", "Trường bắt buộc.")),
          null);
    }
    if (request.enabled() != locked.enabled()) {
      jdbc.sql(UPDATE_ENABLED)
          .param("enabled", request.enabled())
          .param("now", now())
          .param("code", locked.serviceCode())
          .update();
      log.info(
          "Service enabled changed: service={} enabled={}",
          LogSanitizer.clean(locked.serviceCode()),
          request.enabled());
    }
    return view(locked.serviceCode());
  }

  /** W14: xóa mềm một chiều; dữ liệu và lịch sử giữ nguyên. */
  @Transactional
  public void delete(String serviceCode) {
    Locked locked = lock(serviceCode);
    jdbc.sql(UPDATE_DELETED).param("now", now()).param("code", locked.serviceCode()).update();
    log.info("Service deleted: service={}", LogSanitizer.clean(locked.serviceCode()));
  }

  /** W15: danh sách dịch vụ sắp theo mã không phân biệt hoa thường. */
  @Transactional(readOnly = true)
  public List<ServiceView> list(boolean includeDeleted) {
    return jdbc.sql(includeDeleted ? LIST_ALL : LIST_ACTIVE).query(ServiceCatalog::toView).list();
  }

  /** W7: đọc một dịch vụ, không phân biệt hoa thường; dịch vụ đã xóa vẫn trả; 404 nếu không có. */
  public ServiceView get(String serviceCode) {
    if (containsNul(serviceCode)) {
      throw notFound(serviceCode);
    }
    return jdbc.sql(SELECT_VIEW)
        .param("code", serviceCode)
        .query(ServiceCatalog::toView)
        .optional()
        .orElseThrow(() -> notFound(serviceCode));
  }

  /**
   * Ảnh dịch vụ cùng phiên bản hiện hành bằng MỘT truy vấn thường, không khóa (BR14.1, SC-02); cả
   * dịch vụ tắt hoặc đã xóa cũng được trả để người gọi tự quyết định mã từ chối.
   */
  public Optional<ServiceRuntimeSnapshot> findSnapshot(String serviceCode) {
    if (containsNul(serviceCode)) {
      // Mã chứa NUL không bao giờ trùng dịch vụ nào và PostgreSQL không nhận nó làm tham số.
      return Optional.empty();
    }
    return jdbc.sql(SELECT_SNAPSHOT)
        .param("code", serviceCode)
        .query(ServiceCatalog::toSnapshot)
        .optional();
  }

  // ---------- nội bộ ----------

  private static boolean containsNul(String text) {
    return text != null && text.indexOf('\u0000') >= 0;
  }

  private Locked lock(String serviceCode) {
    if (containsNul(serviceCode)) {
      throw notFound(serviceCode);
    }
    jdbc.sql(SET_LOCK_TIMEOUT).update();
    Locked locked =
        jdbc.sql(LOCK_SERVICE)
            .param("code", serviceCode)
            .query(
                (rs, row) ->
                    new Locked(
                        rs.getString("service_code"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getBoolean("enabled"),
                        rs.getBoolean("deleted"),
                        rs.getString("auth_type"),
                        rs.getString("api_key_header_name"),
                        rs.getBoolean("api_key_configured"),
                        rs.getInt("current_version_number")))
            .optional()
            .orElseThrow(() -> notFound(serviceCode));
    if (locked.deleted()) {
      throw notFound(serviceCode);
    }
    return locked;
  }

  private VersionFields readVersion(String serviceCode, int versionNumber) {
    return jdbc.sql(SELECT_VERSION)
        .param("code", serviceCode)
        .param("version", versionNumber)
        .query(
            (rs, row) ->
                new VersionFields(
                    rs.getString("transaction_id_path"),
                    rs.getString("status_path"),
                    rs.getString("timestamp_path"),
                    rs.getString("timestamp_format"),
                    rs.getString("timestamp_pattern"),
                    rs.getString("default_time_zone"),
                    rs.getInt("response_http_status"),
                    rs.getString("response_body")))
        .single();
  }

  private static VersionFields versionFields(NewService service) {
    return new VersionFields(
        service.transactionIdPath(),
        service.statusPath(),
        service.timestampPath(),
        service.timestampFormat(),
        service.timestampPattern(),
        service.defaultTimeZone(),
        service.responseHttpStatus(),
        service.responseBody());
  }

  private void insertVersion(NewService service, int version, OffsetDateTime now) {
    insertVersion(service, version, now, service.serviceCode());
  }

  private void insertVersion(
      NewService service, int version, OffsetDateTime now, String serviceCode) {
    jdbc.sql(INSERT_VERSION)
        .param("code", serviceCode)
        .param("version", version)
        .param("txPath", service.transactionIdPath())
        .param("statusPath", service.statusPath())
        .param("tsPath", service.timestampPath())
        .param("tsFormat", service.timestampFormat())
        .param("tsPattern", service.timestampPattern())
        .param("zone", service.defaultTimeZone())
        .param("httpStatus", service.responseHttpStatus())
        .param("body", service.responseBody())
        .param("now", now)
        .update();
  }

  private ServiceView view(String serviceCode) {
    return jdbc.sql(SELECT_VIEW).param("code", serviceCode).query(ServiceCatalog::toView).single();
  }

  private static ApiException notFound(String serviceCode) {
    return new ApiException(
        ErrorCode.SERVICE_NOT_FOUND,
        "Không tìm thấy dịch vụ.",
        List.of(),
        LogSanitizer.clean(serviceCode));
  }

  private static ServiceView toView(ResultSet rs, int row) throws SQLException {
    String code = rs.getString("service_code");
    return new ServiceView(
        code,
        rs.getString("name"),
        rs.getString("description"),
        rs.getBoolean("enabled"),
        rs.getBoolean("deleted"),
        rs.getString("auth_type"),
        rs.getString("api_key_header_name"),
        rs.getBoolean("api_key_configured"),
        rs.getString("transaction_id_path"),
        rs.getString("status_path"),
        rs.getString("timestamp_path"),
        rs.getString("timestamp_format"),
        rs.getString("timestamp_pattern"),
        rs.getString("default_time_zone"),
        rs.getInt("response_http_status"),
        rs.getString("response_body"),
        "/callbacks/" + code,
        rs.getInt("current_version_number"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static ServiceRuntimeSnapshot toSnapshot(ResultSet rs, int row) throws SQLException {
    return new ServiceRuntimeSnapshot(
        rs.getString("service_code"),
        rs.getBoolean("enabled"),
        rs.getBoolean("deleted"),
        rs.getString("auth_type"),
        rs.getString("api_key_header_name"),
        rs.getString("api_key_secret"),
        rs.getLong("version_id"),
        rs.getInt("version_number"),
        rs.getString("transaction_id_path"),
        rs.getString("status_path"),
        rs.getString("timestamp_path"),
        TimestampFormat.valueOf(rs.getString("timestamp_format")),
        rs.getString("timestamp_pattern"),
        ZoneId.of(rs.getString("default_time_zone")),
        rs.getInt("response_http_status"),
        rs.getString("response_body"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class).toInstant();
  }
}
