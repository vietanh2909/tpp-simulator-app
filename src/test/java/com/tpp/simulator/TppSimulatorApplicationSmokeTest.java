package com.tpp.simulator;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Smoke test không cần Docker và không khởi tạo Spring context. */
class TppSimulatorApplicationSmokeTest {

  @Test
  void applicationClassIsAnnotatedAsSpringBootApplication() {
    assertNotNull(TppSimulatorApplication.class.getAnnotation(SpringBootApplication.class));
  }
}
