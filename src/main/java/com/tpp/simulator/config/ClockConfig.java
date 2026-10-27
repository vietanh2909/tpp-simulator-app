package com.tpp.simulator.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Đồng hồ hệ thống UTC; là bean để kiểm thử thay thế được. */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  public Clock clock() {
    return Clock.systemUTC();
  }
}
