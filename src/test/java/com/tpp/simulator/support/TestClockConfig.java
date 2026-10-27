package com.tpp.simulator.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Thay bean {@code Clock} bằng {@link TestClock} cho mọi kiểm thử tích hợp (dùng chung một ngữ cảnh
 * Spring).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

  @Bean
  @Primary
  public TestClock testClock() {
    return new TestClock();
  }
}
