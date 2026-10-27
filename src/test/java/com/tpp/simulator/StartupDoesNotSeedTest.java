package com.tpp.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.tpp.simulator.support.AbstractIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** BR21.5, NFR7.22: ứng dụng không bao giờ tự tạo dịch vụ nào lúc khởi động. */
class StartupDoesNotSeedTest extends AbstractIntegrationTest {

  @Test
  void anEmptyDatabaseStaysEmptyAfterStartup() {
    // Lớp cơ sở đã dọn dữ liệu sau khi ứng dụng khởi động; danh sách phải rỗng.
    assertEquals("[]", get("/api/services?includeDeleted=true").body());
    assertEquals(0, count("services"));
    assertEquals(0, count("service_config_versions"));
  }

  @Test
  void noMigrationInsertsData() throws IOException {
    Pattern insert = Pattern.compile("INSERT\\s+INTO", Pattern.CASE_INSENSITIVE);
    try (Stream<Path> files = Files.list(Path.of("src", "main", "resources", "db", "migration"))) {
      for (Path file : files.toList()) {
        assertFalse(insert.matcher(Files.readString(file)).find(), file.toString());
      }
    }
  }

  @Test
  void noMainCodeInsertsAServiceOutsideTheCatalog() throws IOException {
    try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String name = file.getFileName().toString();
        if (!name.equals("ServiceCatalog.java")) {
          assertFalse(
              Pattern.compile("INSERT\\s+INTO\\s+services", Pattern.CASE_INSENSITIVE)
                  .matcher(Files.readString(file))
                  .find(),
              name);
        }
      }
    }
  }
}
