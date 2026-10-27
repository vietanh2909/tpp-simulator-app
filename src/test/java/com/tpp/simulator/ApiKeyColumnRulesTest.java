package com.tpp.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * SD-03, NFR3.29: cột {@code api_key_secret} chỉ được nhắc đến ở ba loại, và chỉ trong {@code
 * ServiceCatalog}: (1) câu {@code INSERT INTO services} hoặc {@code UPDATE services} không có
 * {@code RETURNING}; (2) biểu thức {@code IS [NOT] NULL}; (3) đúng MỘT lần trong hằng {@code
 * SELECT_SNAPSHOT}. Thay thế quy tắc "không truy vấn nào chọn cột khóa" của U1. Kiểm thử in bảng
 * (vị trí, loại) để chép vào báo cáo.
 */
class ApiKeyColumnRulesTest {

  private static final Path MAIN = Path.of("src", "main");
  private static final Pattern COLUMN = Pattern.compile("api_key_secret");
  private static final Pattern TEXT_BLOCK =
      Pattern.compile("(\\w+)\\s*=\\s*\"\"\"(.*?)\"\"\"", Pattern.DOTALL);
  private static final Pattern IS_NULL =
      Pattern.compile("^\\s+IS\\s+(NOT\\s+)?NULL\\b", Pattern.CASE_INSENSITIVE);

  record Finding(String file, String location, int category) {}

  private static List<Path> sources() throws IOException {
    try (Stream<Path> files = Files.walk(MAIN)) {
      return files.filter(Files::isRegularFile).toList();
    }
  }

  private static String normalized(String text) {
    return text.replaceAll("\\s+", " ").trim();
  }

  /** Phân loại từng lần xuất hiện; trả danh sách vi phạm trong {@code violations}. */
  private static List<Finding> classify(List<String> violations) throws IOException {
    List<Finding> findings = new ArrayList<>();
    for (Path file : sources()) {
      if (file.getFileName().toString().equals("V1__init_schema.sql")) {
        continue;
      }
      String text = Files.readString(file);
      if (!COLUMN.matcher(text).find()) {
        continue;
      }
      String name = file.getFileName().toString();
      String outsideBlocks = text;
      Matcher blocks = TEXT_BLOCK.matcher(text);
      while (blocks.find()) {
        String constant = blocks.group(1);
        String body = blocks.group(2);
        outsideBlocks = outsideBlocks.replace(body, "");
        String sql = normalized(body);
        Matcher occurrence = COLUMN.matcher(sql);
        while (occurrence.find()) {
          String after = sql.substring(occurrence.end());
          boolean writes =
              (sql.startsWith("INSERT INTO services") || sql.startsWith("UPDATE services"))
                  && !sql.toUpperCase().contains("RETURNING");
          if (IS_NULL.matcher(after).find()) {
            findings.add(new Finding(name, constant, 2));
          } else if (writes) {
            findings.add(new Finding(name, constant, 1));
          } else if (name.equals("ServiceCatalog.java") && constant.equals("SELECT_SNAPSHOT")) {
            findings.add(new Finding(name, constant, 3));
          } else {
            violations.add(name + " " + constant + ": " + sql);
          }
        }
      }
      Matcher rest = COLUMN.matcher(outsideBlocks);
      while (rest.find()) {
        String tail =
            outsideBlocks.substring(rest.end(), Math.min(outsideBlocks.length(), rest.end() + 40));
        String head = outsideBlocks.substring(Math.max(0, rest.start() - 14), rest.start());
        // Chỗ đọc cột từ kết quả của SELECT_SNAPSHOT (cùng một lần đọc, chỉ ở ServiceCatalog).
        boolean snapshotRowMapper =
            name.equals("ServiceCatalog.java") && head.endsWith("rs.getString(\"");
        if (!IS_NULL.matcher(tail).find() && !snapshotRowMapper) {
          violations.add(name + ": api_key_secret ngoài khối SQL cố định");
        }
      }
    }
    return findings;
  }

  @Test
  void everyMentionOfTheColumnIsOneOfTheThreeAllowedKinds() throws IOException {
    List<String> violations = new ArrayList<>();

    List<Finding> findings = classify(violations);

    findings.forEach(
        f ->
            System.out.println(
                "api_key_secret: " + f.file() + " " + f.location() + " loại " + f.category()));
    assertEquals(List.of(), violations);
    assertTrue(findings.size() >= 4, findings.toString());
  }

  @Test
  void onlyServiceCatalogMentionsTheColumn() throws IOException {
    List<String> violations = new ArrayList<>();

    List<String> files = classify(violations).stream().map(Finding::file).distinct().toList();

    assertEquals(List.of("ServiceCatalog.java"), files);
  }

  @Test
  void exactlyOneSelectOfTheValueExistsAndItIsTheSnapshotQuery() throws IOException {
    List<String> violations = new ArrayList<>();

    List<Finding> kind3 = classify(violations).stream().filter(f -> f.category() == 3).toList();

    assertEquals(1, kind3.size());
    assertEquals("SELECT_SNAPSHOT", kind3.get(0).location());
  }

  @Test
  void theColumnIsNeverReturnedByAnyStatement() throws IOException {
    for (Path file : sources()) {
      if (file.getFileName().toString().equals("V1__init_schema.sql")) {
        continue;
      }
      assertTrue(
          !Pattern.compile(
                  "RETURNING[^;\"]*api_key_secret", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
              .matcher(Files.readString(file))
              .find(),
          file.toString());
    }
  }

  @Test
  void packagesOtherThanCatalogNeverMentionTheColumn() throws IOException {
    for (String pkg : List.of("web", "ledger", "ingestion", "loadtool", "mapping", "config")) {
      Path dir =
          MAIN.resolve("java").resolve("com").resolve("tpp").resolve("simulator").resolve(pkg);
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (Stream<Path> files = Files.walk(dir)) {
        for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
          assertTrue(!Files.readString(file).contains("api_key_secret"), file.toString());
        }
      }
    }
  }

  @Test
  void theClassifierItselfCatchesAForbiddenSelect() {
    String forbidden = "X = \"\"\" SELECT s.api_key_secret FROM services s \"\"\"";
    Matcher blocks = TEXT_BLOCK.matcher(forbidden);
    assertTrue(blocks.find());
    String sql = normalized(blocks.group(2));
    Matcher occurrence = COLUMN.matcher(sql);
    assertTrue(occurrence.find());
    assertTrue(!IS_NULL.matcher(sql.substring(occurrence.end())).find());
    assertTrue(!sql.startsWith("INSERT INTO services") && !sql.startsWith("UPDATE services"));
  }
}
