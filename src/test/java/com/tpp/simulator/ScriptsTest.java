package com.tpp.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * LC-13, NFR7.22: bốn script chạy tay tồn tại; PowerShell lưu UTF-8 có BOM và chỉ dùng cú pháp của
 * Windows PowerShell 5.1; bash kết thúc dòng LF; không script nào gọi Docker hay công cụ nạp.
 */
class ScriptsTest {

  private static final Path SCRIPTS = Path.of("scripts");
  private static final List<String> EXPECTED =
      List.of(
          "measure-list-latency.ps1",
          "check-concurrency.ps1",
          "create-sample-services.ps1",
          "create-sample-services.sh");

  // Cú pháp chỉ có ở PowerShell 7.
  private static final List<Pattern> POWERSHELL_7 =
      List.of(
          Pattern.compile("\\?\\?"),
          Pattern.compile("\\?\\."),
          Pattern.compile("\\?\\["),
          Pattern.compile("-Parallel\\b"),
          Pattern.compile("&&"),
          Pattern.compile("\\|\\|"),
          Pattern.compile("\\)\\s*\\?\\s*[^\\s:]+\\s*:\\s*[^\\s]"));

  private static List<Path> scripts(String suffix) throws IOException {
    try (Stream<Path> files = Files.list(SCRIPTS)) {
      return files.filter(p -> p.getFileName().toString().endsWith(suffix)).toList();
    }
  }

  @Test
  void theFourScriptsExist() {
    for (String name : EXPECTED) {
      assertTrue(Files.isRegularFile(SCRIPTS.resolve(name)), name);
    }
  }

  @Test
  void everyPowerShellScriptStartsWithAUtf8ByteOrderMark() throws IOException {
    for (Path script : scripts(".ps1")) {
      byte[] bytes = Files.readAllBytes(script);

      assertTrue(bytes.length > 3, script.toString());
      assertEquals((byte) 0xEF, bytes[0], script.toString());
      assertEquals((byte) 0xBB, bytes[1], script.toString());
      assertEquals((byte) 0xBF, bytes[2], script.toString());
    }
  }

  @Test
  void noPowerShellScriptUsesSyntaxThatOnlyPowerShellSevenHas() throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path script : scripts(".ps1")) {
      String text = Files.readString(script, StandardCharsets.UTF_8);
      for (Pattern pattern : POWERSHELL_7) {
        if (pattern.matcher(text).find()) {
          violations.add(script.getFileName() + " " + pattern);
        }
      }
    }
    assertEquals(List.of(), violations);
  }

  @Test
  void everyBashScriptHasUnixLineEndingsAndAShebang() throws IOException {
    for (Path script : scripts(".sh")) {
      String text = Files.readString(script, StandardCharsets.UTF_8);

      assertFalse(text.contains("\r"), script.toString());
      assertTrue(text.startsWith("#!"), script.toString());
    }
  }

  @Test
  void noScriptCallsDockerOrTheLoadTool() throws IOException {
    for (Path script : Stream.concat(scripts(".ps1").stream(), scripts(".sh").stream()).toList()) {
      String text = Files.readString(script, StandardCharsets.UTF_8).toLowerCase();

      assertFalse(text.contains("docker"), script.toString());
      assertFalse(text.contains("loadtool"), script.toString());
      assertFalse(text.contains("propertieslauncher"), script.toString());
    }
  }

  @Test
  void everyScriptTakesTheBackendAddressWithTheDocumentedDefault() throws IOException {
    for (Path script : scripts(".ps1")) {
      String text = Files.readString(script, StandardCharsets.UTF_8);
      assertTrue(text.contains("$BaseUrl = 'http://127.0.0.1:8080'"), script.toString());
    }
    String bash = Files.readString(SCRIPTS.resolve("create-sample-services.sh"));
    assertTrue(bash.contains("http://127.0.0.1:8080"));
  }

  @Test
  void theConcurrencyScriptKeepsItsThresholdAndExitCodes() throws IOException {
    String text =
        Files.readString(SCRIPTS.resolve("check-concurrency.ps1"), StandardCharsets.UTF_8);

    assertTrue(text.contains("$MinInFlight = 25"));
    assertTrue(text.contains("$exitCode = 2"));
    assertTrue(text.contains("INCONCLUSIVE"));
    assertTrue(text.contains("maxInFlight"));
  }

  @Test
  void theLatencyScriptMeasuresExactlyOneScenarioWithTheAgreedSamples() throws IOException {
    String text =
        Files.readString(SCRIPTS.resolve("measure-list-latency.ps1"), StandardCharsets.UTF_8);

    assertTrue(text.contains("[int]$Samples = 200"));
    assertTrue(text.contains("[int]$Warmup = 20"));
    assertTrue(text.contains("$ThresholdMs = 1000"));
    assertTrue(text.contains("$url = $BaseUrl + '/api/transactions?page=1&size=20'"));
    // Chỉ có lần đọc tổng số callback (để in cảnh báo) và đúng một kịch bản được đo.
    List<String> requested = new ArrayList<>();
    var matcher = Pattern.compile("\\$BaseUrl \\+ '([^']+)'").matcher(text);
    while (matcher.find()) {
      requested.add(matcher.group(1));
    }
    assertEquals(
        List.of("/api/callbacks?page=1&size=20", "/api/transactions?page=1&size=20"), requested);
  }
}
