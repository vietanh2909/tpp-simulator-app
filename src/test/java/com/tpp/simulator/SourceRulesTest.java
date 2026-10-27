package com.tpp.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Quy tắc tĩnh trên mã nguồn: Callback bất biến (NFR2.6), không xóa vật lý Service, không thực thể
 * JPA, truy vấn tham số hóa (NFR3.8), không câu truy vấn nào chọn khóa API (NFR3.5), không log
 * payload hay header.
 */
class SourceRulesTest {

  private static final Path MAIN = Path.of("src", "main");

  private static List<Path> sources() throws IOException {
    try (Stream<Path> files = Files.walk(MAIN)) {
      return files
          .filter(
              p ->
                  p.toString().endsWith(".java")
                      || p.toString().endsWith(".sql")
                      || p.toString().endsWith(".yml"))
          .toList();
    }
  }

  private static List<String> find(Pattern pattern, boolean javaOnly) throws IOException {
    List<String> hits = new ArrayList<>();
    for (Path file : sources()) {
      if (javaOnly && !file.toString().endsWith(".java")) {
        continue;
      }
      String text = Files.readString(file).replaceAll("\\s+", " ");
      if (pattern.matcher(text).find()) {
        hits.add(file.toString());
      }
    }
    return hits;
  }

  @Test
  void sourcesWereFoundSoTheScansAreMeaningful() throws IOException {
    assertTrue(sources().size() > 20);
  }

  @Test
  void noStatementUpdatesOrDeletesCallbacks() throws IOException {
    Pattern pattern =
        Pattern.compile(
            "(UPDATE\\s+callbacks\\b|DELETE\\s+FROM\\s+callbacks\\b|TRUNCATE\\s+(TABLE\\s+)?callbacks\\b|DROP\\s+TABLE\\s+callbacks\\b)",
            Pattern.CASE_INSENSITIVE);

    assertEquals(List.of(), find(pattern, false));
  }

  @Test
  void noStatementDeletesServicesPhysically() throws IOException {
    Pattern pattern =
        Pattern.compile(
            "(DELETE\\s+FROM\\s+(services|service_config_versions|transactions)\\b|TRUNCATE\\b|DROP\\s+TABLE\\b)",
            Pattern.CASE_INSENSITIVE);

    assertEquals(List.of(), find(pattern, false));
  }

  @Test
  void noJpaEntitiesAreUsed() throws IOException {
    assertEquals(List.of(), find(Pattern.compile("jakarta\\.persistence|@Entity\\b"), true));
  }

  // Việc mỗi lần nhắc đến cột api_key_secret thuộc loại nào do ApiKeyColumnRulesTest (SD-03) kiểm.

  @Test
  void noStatementUpdatesConfigVersionsOrDropsADatabaseOrCreatesAnExtension() throws IOException {
    Pattern pattern =
        Pattern.compile(
            "(UPDATE\\s+service_config_versions\\b|DROP\\s+DATABASE\\b|CREATE\\s+EXTENSION\\b)",
            Pattern.CASE_INSENSITIVE);

    assertEquals(List.of(), find(pattern, false));
  }

  @Test
  void theRateLimitingAndCorsMachineryDoesNotExist() throws IOException {
    Pattern pattern =
        Pattern.compile(
            "(@CrossOrigin|CorsRegistry|addCorsMappings|TOO_MANY_REQUESTS|\\b429\\b|RateLimiter|Bucket4j)");

    assertEquals(List.of(), find(pattern, true));
  }

  @Test
  void errorLoggingNeverPassesAnExceptionAsTheLastArgument() throws IOException {
    Pattern pattern =
        Pattern.compile(
            "log\\.(warn|error)\\([^;]*,\\s*(e|ex|exception|error|throwable)\\s*\\)\\s*;");

    assertEquals(List.of(), find(pattern, true));
  }

  @Test
  void sqlIsNeverBuiltByConcatenatingStringsInTheMainCode() throws IOException {
    Pattern concatenation =
        Pattern.compile(
            "(?i)\\.sql\\(\\s*\"[^\"]*\"\\s*\\+|\"\\s*(SELECT|INSERT|UPDATE|DELETE)\\b[^\"]*\"\\s*\\+");

    assertEquals(List.of(), find(concatenation, true));
  }

  @Test
  void aMigrationIsNeverEditedAfterItWasAppliedSoOnlyNumberedFilesExist() throws IOException {
    try (Stream<Path> files =
        Files.list(MAIN.resolve("resources").resolve("db").resolve("migration"))) {
      List<String> names = files.map(p -> p.getFileName().toString()).toList();
      assertFalse(names.isEmpty());
      for (String name : names) {
        assertTrue(name.matches("V\\d+__[a-z0-9_]+\\.sql"), name);
      }
    }
  }

  @Test
  void logCallsNeverReferencePayloadOrHeaderVariables() throws IOException {
    Pattern pattern =
        Pattern.compile(
            "log\\.(info|warn|error|debug|trace)\\([^;]*\\b(payload|body|headers|rawHeaders|authorization|cookie)\\b",
            Pattern.CASE_INSENSITIVE);

    assertEquals(List.of(), find(pattern, true));
  }
}
