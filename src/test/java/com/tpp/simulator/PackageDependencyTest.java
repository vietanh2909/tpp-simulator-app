package com.tpp.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phụ thuộc gói MỘT CHIỀU (logical-components.md, ADR-001, ADR-008), kiểm bằng cách đọc các lệnh
 * import trong mã nguồn (không thêm thư viện): web -> ingestion/catalog/ledger; ingestion ->
 * catalog/mapping/ledger; mapping và ledger không phụ thuộc gói ứng dụng nào khác ngoài config;
 * catalog -> mapping chỉ từ U2. {@code config} được mọi gói đọc nhưng không phụ thuộc gói nào.
 */
class PackageDependencyTest {

  private static final Path MAIN = Path.of("src", "main", "java", "com", "tpp", "simulator");

  private static final Map<String, Set<String>> ALLOWED =
      Map.of(
          "web", Set.of("ingestion", "catalog", "ledger", "config", "mapping"),
          "ingestion", Set.of("catalog", "mapping", "ledger", "config"),
          "catalog", Set.of("config", "mapping"),
          "mapping", Set.of(),
          "ledger", Set.of("config"),
          "config", Set.of(),
          "loadtool", Set.of("ingestion", "catalog", "ledger", "mapping", "config"));

  private static final List<String> SPRING_STEREOTYPES =
      List.of(
          "@Component",
          "@Service",
          "@Repository",
          "@Configuration",
          "@Controller",
          "@RestController",
          "@SpringBootApplication",
          "@EnableAutoConfiguration");

  private static final Pattern REFERENCE = Pattern.compile("com\\.tpp\\.simulator\\.([a-z]+)\\.");

  private static List<Path> javaFiles(String packageName) throws IOException {
    try (Stream<Path> files = Files.walk(MAIN.resolve(packageName))) {
      return files.filter(p -> p.toString().endsWith(".java")).toList();
    }
  }

  @Test
  void everyApplicationPackageExists() {
    // loadtool nằm trong ALLOWED nên cũng phải tồn tại.
    for (String name : ALLOWED.keySet()) {
      assertTrue(Files.isDirectory(MAIN.resolve(name)), name);
    }
  }

  @Test
  void packagesOnlyDependOnTheAllowedOnes() throws IOException {
    List<String> violations = new ArrayList<>();
    for (Map.Entry<String, Set<String>> entry : ALLOWED.entrySet()) {
      String pkg = entry.getKey();
      for (Path file : javaFiles(pkg)) {
        Set<String> used = new HashSet<>();
        for (String line : Files.readAllLines(file)) {
          if (line.startsWith("package ")) {
            continue;
          }
          Matcher m = REFERENCE.matcher(line);
          while (m.find()) {
            used.add(m.group(1));
          }
        }
        used.remove(pkg);
        used.removeAll(entry.getValue());
        if (!used.isEmpty()) {
          violations.add(file.getFileName() + " (" + pkg + ") -> " + used);
        }
      }
    }
    assertEquals(List.of(), violations);
  }

  @Test
  void theDependencyGraphHasNoCycles() {
    for (String start : ALLOWED.keySet()) {
      Set<String> visited = new HashSet<>();
      List<String> stack = new ArrayList<>(ALLOWED.get(start));
      while (!stack.isEmpty()) {
        String next = stack.remove(stack.size() - 1);
        assertFalse(next.equals(start), "Vòng phụ thuộc quanh " + start);
        if (visited.add(next)) {
          stack.addAll(ALLOWED.get(next));
        }
      }
    }
  }

  @Test
  void mappingEngineIsPureAndKnowsNothingAboutDatabaseOrWeb() throws IOException {
    for (Path file : javaFiles("mapping")) {
      String text = Files.readString(file);
      assertFalse(text.contains("java.sql"), file.toString());
      assertFalse(text.contains("org.springframework.jdbc"), file.toString());
      assertFalse(text.contains("org.springframework.web"), file.toString());
      assertFalse(text.contains("jakarta.servlet"), file.toString());
    }
  }

  private static String mainText(Path file) throws IOException {
    return Files.readString(file);
  }

  private static List<Path> allMainJava() throws IOException {
    try (Stream<Path> files = Files.walk(MAIN)) {
      return files.filter(p -> p.toString().endsWith(".java")).toList();
    }
  }

  @Test
  void nothingOutsideTheLoadToolPackageMentionsItNotEvenTheApplicationClassOrYaml()
      throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path file : allMainJava()) {
      if (!file.startsWith(MAIN.resolve("loadtool"))
          && mainText(file).contains("com.tpp.simulator.loadtool")) {
        violations.add(file.toString());
      }
    }
    try (Stream<Path> files = Files.walk(Path.of("src", "main", "resources"))) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        if (Files.readString(file).contains("loadtool")) {
          violations.add(file.toString());
        }
      }
    }
    assertEquals(List.of(), violations);
  }

  @Test
  void loadToolSourcesCarryNoSpringStereotypesSoTheApplicationNeverRegistersThem()
      throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path file : javaFiles("loadtool")) {
      String text = Files.readString(file);
      for (String stereotype : SPRING_STEREOTYPES) {
        if (text.contains(stereotype)) {
          violations.add(file.getFileName() + " " + stereotype);
        }
      }
    }
    assertEquals(List.of(), violations);
  }

  @Test
  void receiveForLoadAppearsOnlyInIngestionAndLoadTool() throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path file : allMainJava()) {
      boolean allowed =
          file.startsWith(MAIN.resolve("ingestion")) || file.startsWith(MAIN.resolve("loadtool"));
      if (!allowed && mainText(file).contains("receiveForLoad")) {
        violations.add(file.toString());
      }
    }
    assertEquals(List.of(), violations);
  }

  @Test
  void onlyTheIngestionServiceCatchesStoreFailures() throws IOException {
    Pattern catches =
        Pattern.compile("catch\\s*\\([^)]*(DataAccessException|TransactionException|SQLException)");
    List<String> violations = new ArrayList<>();
    for (Path file : allMainJava()) {
      String name = file.getFileName().toString();
      // Ngoại lệ duy nhất: kết nối quản trị tạo database của công cụ nạp (không phải đường ghi).
      boolean allowed = name.equals("LoadCommand.java");
      if (!allowed && catches.matcher(mainText(file)).find()) {
        violations.add(name);
      }
    }
    assertEquals(List.of(), violations);
  }

  @Test
  void theCallbackControllerOnlyMapsResultsAndNeverTouchesTheStore() throws IOException {
    String text = mainText(MAIN.resolve("web").resolve("CallbackController.java"));

    assertFalse(
        Pattern.compile("catch\\s*\\([^)]*(Exception|SQLException|Throwable)")
            .matcher(text)
            .find());
    for (String forbidden :
        List.of(
            "CallbackLedger",
            "JdbcClient",
            "DataSource",
            "ServiceCatalog",
            "PlatformTransactionManager")) {
      assertFalse(text.contains(forbidden), forbidden);
    }
    assertFalse(text.contains("receiveForLoad"));
  }

  @Test
  void theMappingTestControllerHasNoDataAccessBeans() throws IOException {
    String text = mainText(MAIN.resolve("web").resolve("MappingTestController.java"));

    for (String forbidden :
        List.of(
            "JdbcClient",
            "DataSource",
            "CallbackLedger",
            "ServiceCatalog",
            "PlatformTransactionManager",
            "@Transactional")) {
      assertFalse(text.contains(forbidden), forbidden);
    }
  }

  @Test
  void onlyTheWebPackageKnowsAboutServletsAndSpringWebBinding() throws IOException {
    for (String pkg : List.of("catalog", "ledger", "ingestion", "mapping", "loadtool")) {
      for (Path file : javaFiles(pkg)) {
        String text = Files.readString(file);
        assertFalse(text.contains("jakarta.servlet"), file.toString());
        assertFalse(text.contains("org.springframework.web.bind"), file.toString());
      }
    }
  }
}
