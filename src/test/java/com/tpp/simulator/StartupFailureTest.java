package com.tpp.simulator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * NFR2.5: khi không kết nối được cơ sở dữ liệu, ứng dụng khởi động thất bại rõ ràng (ném ngoại lệ
 * gốc là lỗi kết nối, không treo, không lộ mật khẩu) thay vì chạy một nửa. Không cần Docker hay cơ
 * sở dữ liệu.
 */
class StartupFailureTest {

  private static int closedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  @Test
  void startupFailsFastWithAClearConnectionErrorAndNeverEchoesThePassword() throws Exception {
    int deadPort = closedPort();
    long started = System.nanoTime();

    Throwable failure =
        assertThrows(
            Throwable.class,
            () ->
                // Dùng tham số dòng lệnh (ưu tiên cao hơn application.yml); properties() chỉ là mặc
                // định.
                new SpringApplicationBuilder(TppSimulatorApplication.class)
                    .run(
                        "--spring.datasource.url=jdbc:postgresql://127.0.0.1:" + deadPort + "/none",
                        "--spring.datasource.username=tpp",
                        "--spring.datasource.password=s3cret-pw-do-not-echo",
                        "--spring.datasource.hikari.connection-timeout=2000",
                        "--server.port=0",
                        "--spring.main.banner-mode=off"));

    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
    assertTrue(elapsed.toSeconds() < 60, "khởi động thất bại phải nhanh, không treo: " + elapsed);

    Throwable root = failure;
    boolean sawSqlOrConnect = false;
    while (root != null) {
      assertFalse(
          String.valueOf(root.getMessage()).contains("s3cret-pw-do-not-echo"),
          "thông báo lỗi không được chứa mật khẩu: " + root);
      if (root instanceof SQLException || root instanceof java.net.ConnectException) {
        sawSqlOrConnect = true;
      }
      if (root.getCause() == null || root.getCause() == root) {
        break;
      }
      root = root.getCause();
    }
    assertNotNull(root);
    assertTrue(sawSqlOrConnect, "nguyên nhân gốc phải là lỗi kết nối cơ sở dữ liệu: " + failure);
  }
}
