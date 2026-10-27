package com.tpp.simulator.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class DbErrorsTest {

  private static final Logger LOG = LoggerFactory.getLogger(DbErrorsTest.class);

  @Test
  void findsSqlStateInTheCauseChain() {
    Exception error = new RuntimeException("wrapper", new SQLException("raw detail", "23505"));

    assertEquals("23505", DbErrors.sqlState(error));
  }

  @Test
  void returnsDashWhenNoSqlExceptionIsPresent() {
    assertEquals("-", DbErrors.sqlState(new IllegalStateException("x")));
  }

  @Test
  void logsOnlyExceptionClassAndSqlStateNeverTheRawMessage(CapturedOutput output) {
    Exception error =
        new RuntimeException(
            "failed", new SQLException("Detail: row (secret-payload-123) violates", "22P05"));

    DbErrors.logWriteFailure(LOG, "THU_HO\nFAKE", error);

    String text = output.getAll();
    assertTrue(text.contains("exception=java.lang.RuntimeException"));
    assertTrue(text.contains("sqlState=22P05"));
    assertTrue(text.contains("service=THU_HO FAKE"));
    assertFalse(text.contains("secret-payload-123"));
  }
}
