package com.tpp.simulator.loadtool;

import com.tpp.simulator.catalog.ServiceCatalog;
import com.tpp.simulator.catalog.ServiceValidator;
import com.tpp.simulator.config.ClockConfig;
import com.tpp.simulator.config.JacksonConfig;
import com.tpp.simulator.ingestion.ApiKeyVerifier;
import com.tpp.simulator.ingestion.CallbackIngestionService;
import com.tpp.simulator.ledger.CallbackLedger;
import com.tpp.simulator.mapping.MappingEngine;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Ngữ cảnh Spring TỐI THIỂU của công cụ nạp (LC-04): chỉ các bean của logic nhận callback cùng
 * nguồn dữ liệu, giao dịch, JDBC, Flyway và Jackson. Lớp này KHÔNG mang annotation đăng ký thành
 * phần của Spring và không nạp gói {@code web} (không controller, bộ lọc hay bộ xử lý lỗi), nên ứng
 * dụng chính, vốn quét toàn bộ {@code com.tpp.simulator}, không bao giờ đăng ký bean của công cụ
 * nạp và không có đường nào gọi nó lúc khởi động (BR22.1).
 */
@Import({
  ServiceCatalog.class,
  ServiceValidator.class,
  MappingEngine.class,
  ApiKeyVerifier.class,
  CallbackIngestionService.class,
  CallbackLedger.class,
  ClockConfig.class,
  JacksonConfig.class
})
@ImportAutoConfiguration({
  DataSourceAutoConfiguration.class,
  DataSourceTransactionManagerAutoConfiguration.class,
  TransactionAutoConfiguration.class,
  JdbcTemplateAutoConfiguration.class,
  JdbcClientAutoConfiguration.class,
  FlywayAutoConfiguration.class,
  JacksonAutoConfiguration.class
})
public class LoadToolContext {}
