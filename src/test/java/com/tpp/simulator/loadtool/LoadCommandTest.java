package com.tpp.simulator.loadtool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SD-07, LC-04: trình tự khởi chạy, mã thoát và việc lệnh bị từ chối không mở kết nối nào. */
class LoadCommandTest {

  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
  private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
  private final List<String> calls = new ArrayList<>();

  private boolean databaseExists;
  private boolean creationFails;
  private LoadReport report = LoadReport.success(10, 3, 10, Duration.ofSeconds(2));
  private String lastUrl;

  private LoadCommand command() {
    LoadCommand.DatabaseAdmin admin =
        new LoadCommand.DatabaseAdmin() {
          @Override
          public boolean exists(String name) {
            calls.add("exists:" + name);
            return databaseExists;
          }

          @Override
          public void create(String name) throws SQLException {
            calls.add("create:" + name);
            if (creationFails) {
              throw new SQLException("no permission");
            }
          }
        };
    return new LoadCommand(
        "jdbc:postgresql://localhost:5432/tpp_simulator?sslmode=disable",
        "u",
        "p",
        out,
        err,
        admin,
        (parameters, url, user, password, stream) -> {
          calls.add("run:" + parameters.database());
          lastUrl = url;
          return report;
        });
  }

  private String stdout() {
    return outBytes.toString(StandardCharsets.UTF_8);
  }

  private String stderr() {
    return errBytes.toString(StandardCharsets.UTF_8);
  }

  @Test
  void aRefusedDatabaseNameNeverOpensAnyConnection() {
    for (String database : List.of("tpp_simulator", "tpp_simulator_test", "x", "a;b_load")) {
      calls.clear();

      int exit = command().run(new String[] {"--database=" + database});

      assertEquals(2, exit, database);
      assertEquals(List.of(), calls, database);
    }
    assertTrue(stderr().contains("Load refused"), stderr());
  }

  @Test
  void badParametersAreRefusedWithExitTwoAndNoConnection() {
    int exit = command().run(new String[] {"--callbacks=zero"});

    assertEquals(2, exit);
    assertEquals(List.of(), calls);
    assertTrue(stderr().contains("--callbacks"), stderr());
  }

  @Test
  void aMissingDatabaseIsCreatedThenLoaded() {
    databaseExists = false;

    int exit = command().run(new String[] {"--callbacks=10"});

    assertEquals(0, exit);
    assertEquals(
        List.of("exists:tpp_simulator_load", "create:tpp_simulator_load", "run:tpp_simulator_load"),
        calls);
    assertEquals("jdbc:postgresql://localhost:5432/tpp_simulator_load?sslmode=disable", lastUrl);
    assertTrue(stdout().contains("Load finished: callbacks=10 transactions=3"), stdout());
  }

  @Test
  void anExistingDatabaseIsNotCreatedAgain() {
    databaseExists = true;

    int exit = command().run(new String[] {"--database=perf_load"});

    assertEquals(0, exit);
    assertEquals(List.of("exists:perf_load", "run:perf_load"), calls);
  }

  @Test
  void aDatabaseThatCannotBeCreatedIsRefusedWithTheManualCommand() {
    creationFails = true;

    int exit = command().run(new String[0]);

    assertEquals(2, exit);
    assertFalse(calls.contains("run:tpp_simulator_load"));
    assertTrue(stderr().contains("CREATE DATABASE \"tpp_simulator_load\";"), stderr());
    assertFalse(stderr().contains("no permission"), stderr());
  }

  @Test
  void aFailedRunPrintsTheReportToStderrAndReturnsItsExitCode() {
    databaseExists = true;
    report =
        LoadReport.failed(42, 9, 41, Duration.ofSeconds(1), "writeStatus=ACCEPTED_WRITE_FAILED");

    int exit = command().run(new String[0]);

    assertEquals(1, exit);
    assertTrue(stderr().contains("Load failed: writeStatus=ACCEPTED_WRITE_FAILED"), stderr());
    assertTrue(stderr().contains("callbacksInDatabase=42"), stderr());
    assertEquals("", stdout());
  }

  @Test
  void aRefusedRunFromTheStarterReturnsTwo() {
    databaseExists = true;
    report = LoadReport.refused("Dịch vụ LOAD_01 đã có");

    assertEquals(2, command().run(new String[0]));
    assertTrue(stderr().contains("LOAD_01"), stderr());
  }

  @Test
  void urlForReplacesOnlyTheDatabaseName() {
    assertEquals(
        "jdbc:postgresql://h:5432/postgres",
        LoadCommand.urlFor("jdbc:postgresql://h:5432/app", "postgres"));
    assertEquals(
        "jdbc:postgresql://h:5432/x_load?a=b&c=d",
        LoadCommand.urlFor("jdbc:postgresql://h:5432/app?a=b&c=d", "x_load"));
  }
}
