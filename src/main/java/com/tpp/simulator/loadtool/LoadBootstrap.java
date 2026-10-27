package com.tpp.simulator.loadtool;

import com.tpp.simulator.catalog.ServiceCatalog;
import com.tpp.simulator.ingestion.CallbackIngestionService;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Phần mỏng nối công cụ nạp với thế giới thật: dựng ngữ cảnh Spring không có web trỏ vào database
 * đích, kết nối quản trị để tạo database, và lắp {@link LoadCommand}. Giữ nhỏ vì phần này chỉ chạy
 * được với PostgreSQL thật (LC-04).
 */
public final class LoadBootstrap {

  private LoadBootstrap() {}

  /** Dựng ngữ cảnh không web với nguồn thuộc tính ưu tiên cao nhất (pool 2 kết nối, log WARN). */
  public static ConfigurableApplicationContext start(String url, String user, String password) {
    boolean setLevel = System.getProperty("logging.level.root") == null;
    if (setLevel) {
      System.setProperty("logging.level.root", "WARN");
    }
    try {
      return build(url, user, password).run();
    } finally {
      if (setLevel) {
        System.clearProperty("logging.level.root");
      }
    }
  }

  private static SpringApplicationBuilder build(String url, String user, String password) {
    return new SpringApplicationBuilder(LoadToolContext.class)
        .web(WebApplicationType.NONE)
        .bannerMode(Banner.Mode.OFF)
        .initializers(
            context ->
                context
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(
                        new MapPropertySource(
                            "loadTool",
                            Map.of(
                                "spring.datasource.url", url,
                                "spring.datasource.username", user,
                                "spring.datasource.password", password,
                                "spring.datasource.hikari.maximum-pool-size", "2"))));
  }

  /** Nạp trong một ngữ cảnh Spring mới; từ chối nếu database đang kết nối không phải đích. */
  static LoadReport load(
      LoadParameters parameters, String url, String user, String password, PrintStream out) {
    try (ConfigurableApplicationContext context = start(url, user, password)) {
      JdbcClient jdbc = context.getBean(JdbcClient.class);
      String current = jdbc.sql("SELECT current_database()").query(String.class).single();
      if (!current.equals(parameters.database())) {
        return LoadReport.refused("Ngữ cảnh đang kết nối database khác database đích; dừng.");
      }
      return new LoadRunner(
              context.getBean(ServiceCatalog.class),
              context.getBean(CallbackIngestionService.class),
              jdbc,
              context.getBean(Clock.class),
              out)
          .run(parameters);
    }
  }

  /**
   * Lệnh thật: đọc kết nối từ biến môi trường của chính ứng dụng, tạo database qua kết nối quản
   * trị.
   */
  public static LoadCommand command(Map<String, String> env, PrintStream out, PrintStream err) {
    String base =
        env.getOrDefault("SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/tpp_simulator");
    String user = env.getOrDefault("SPRING_DATASOURCE_USERNAME", "tpp");
    String password = env.getOrDefault("SPRING_DATASOURCE_PASSWORD", "tpp");
    return new LoadCommand(
        base,
        user,
        password,
        out,
        err,
        new JdbcDatabaseAdmin(LoadCommand.urlFor(base, "postgres"), user, password),
        LoadBootstrap::load);
  }

  /** Kiểm tra và tạo database qua kết nối quản trị; tên đã qua {@link DatabaseNameGuard}. */
  private record JdbcDatabaseAdmin(String url, String user, String password)
      implements LoadCommand.DatabaseAdmin {

    @Override
    public boolean exists(String name) throws SQLException {
      try (Connection connection = DriverManager.getConnection(url, user, password);
          PreparedStatement statement =
              connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
        statement.setString(1, name);
        try (ResultSet rows = statement.executeQuery()) {
          return rows.next();
        }
      }
    }

    @Override
    public void create(String name) throws SQLException {
      DatabaseNameGuard.validate(name);
      try (Connection connection = DriverManager.getConnection(url, user, password);
          Statement statement = connection.createStatement()) {
        statement.execute("CREATE DATABASE \"" + name + "\"");
      }
    }
  }
}
