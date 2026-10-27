package com.tpp.simulator.support;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * MỘT PostgreSQL 16 dùng chung cho cả bộ kiểm thử (singleton, khởi động một lần, dọn khi JVM thoát
 * nhờ Ryuk).
 *
 * <p>Mặc định chạy container Testcontainers (cần Docker). Máy không có Docker có thể trỏ vào một
 * PostgreSQL có sẵn bằng biến môi trường {@code TPP_TEST_DB_URL}, {@code TPP_TEST_DB_USER}, {@code
 * TPP_TEST_DB_PASSWORD}. Vì kiểm thử xóa sạch dữ liệu, tên cơ sở dữ liệu ngoài bắt buộc chứa chữ
 * "test"; nếu không, kiểm thử từ chối chạy.
 */
public final class TestDatabase {

  public static final String URL;
  public static final String USERNAME;
  public static final String PASSWORD;

  /** Chỉ khác null khi dùng container; null khi dùng cơ sở dữ liệu ngoài. */
  private static final PostgreSQLContainer<?> CONTAINER;

  static {
    String externalUrl = System.getenv("TPP_TEST_DB_URL");
    if (externalUrl != null && !externalUrl.isBlank()) {
      String name = externalUrl.substring(externalUrl.lastIndexOf('/') + 1);
      int query = name.indexOf('?');
      if (query >= 0) {
        name = name.substring(0, query);
      }
      if (!name.toLowerCase(java.util.Locale.ROOT).contains("test")) {
        throw new IllegalStateException(
            "TPP_TEST_DB_URL phải trỏ vào cơ sở dữ liệu có tên chứa 'test' vì kiểm thử xóa sạch dữ liệu.");
      }
      CONTAINER = null;
      URL = externalUrl;
      USERNAME = System.getenv().getOrDefault("TPP_TEST_DB_USER", "tpp");
      PASSWORD = System.getenv().getOrDefault("TPP_TEST_DB_PASSWORD", "tpp");
    } else {
      CONTAINER =
          new PostgreSQLContainer<>("postgres:16")
              .withDatabaseName("tpp_simulator_test")
              .withUsername("tpp")
              .withPassword("tpp");
      CONTAINER.start();
      URL = CONTAINER.getJdbcUrl();
      USERNAME = CONTAINER.getUsername();
      PASSWORD = CONTAINER.getPassword();
    }
  }

  private TestDatabase() {}

  /**
   * Host và cổng của cơ sở dữ liệu, dạng "host:port" (dùng cho proxy TCP của kiểm thử độ tin cậy).
   */
  public static String hostAndPort() {
    String withoutPrefix = URL.substring("jdbc:postgresql://".length());
    return withoutPrefix.substring(0, withoutPrefix.indexOf('/'));
  }

  /** Phần đường dẫn và tham số của URL (bắt đầu bằng '/'), dùng để dựng URL qua proxy. */
  public static String databasePath() {
    String withoutPrefix = URL.substring("jdbc:postgresql://".length());
    return withoutPrefix.substring(withoutPrefix.indexOf('/'));
  }
}
