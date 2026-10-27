package com.tpp.simulator.loadtool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.tpp.simulator.catalog.ServiceCatalog;
import com.tpp.simulator.ingestion.CallbackIngestionService;
import com.tpp.simulator.support.TestDatabase;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.RestController;

/**
 * LC-04, BR22.1: ngữ cảnh Spring của công cụ nạp có đủ bean cho {@code LoadRunner}, không có web,
 * và trỏ đúng database được chỉ định (kiểm thử chỉ nạp bean, không ghi).
 */
class LoadToolContextTest {

  @Test
  void theContextHasTheBeansTheRunnerNeedsAndNoWebParts() {
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    Level before = root.getLevel();
    try (ConfigurableApplicationContext context =
        LoadBootstrap.start(TestDatabase.URL, TestDatabase.USERNAME, TestDatabase.PASSWORD)) {
      assertNotNull(context.getBean(ServiceCatalog.class));
      assertNotNull(context.getBean(CallbackIngestionService.class));
      assertNotNull(context.getBean(JdbcClient.class));
      assertFalse(context instanceof ServletWebServerApplicationContext);
      assertEquals(0, context.getBeansWithAnnotation(RestController.class).size());
      assertEquals(0, context.getBeanNamesForType(jakarta.servlet.Filter.class).length);
      assertTrue(
          context
              .getBean(JdbcClient.class)
              .sql("SELECT current_database()")
              .query(String.class)
              .single()
              .contains("test"));
    } finally {
      root.setLevel(before);
    }
    assertEquals(null, System.getProperty("logging.level.root"));
  }

  @Test
  void thePoolOfTheLoadToolHasTwoConnections() {
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    Level before = root.getLevel();
    try (ConfigurableApplicationContext context =
        LoadBootstrap.start(TestDatabase.URL, TestDatabase.USERNAME, TestDatabase.PASSWORD)) {
      com.zaxxer.hikari.HikariDataSource dataSource =
          context.getBean(com.zaxxer.hikari.HikariDataSource.class);

      assertEquals(2, dataSource.getMaximumPoolSize());
    } finally {
      root.setLevel(before);
    }
  }
}
