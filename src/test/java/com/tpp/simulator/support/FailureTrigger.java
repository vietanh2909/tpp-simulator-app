package com.tpp.simulator.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Trigger ép lỗi ghi chỉ dùng cho kiểm thử (RD-04). Mỗi trigger chỉ kích hoạt cho MỘT mã dịch vụ
 * của kiểm thử, nên nếu sót lại cũng không chạm dữ liệu của kiểm thử khác. Dùng với
 * try-with-resources; {@link #close()} luôn xóa trigger rồi hàm. {@link #sweep()} xóa mọi trigger
 * và hàm có tiền tố {@code test_fail_} còn sót.
 */
public final class FailureTrigger implements AutoCloseable {

  private static final Pattern SERVICE_CODE = Pattern.compile("^[A-Za-z0-9_-]{1,50}$");
  private static final Pattern GENERATED_NAME = Pattern.compile("^test_fail_[a-z0-9_]+$");
  private static final Set<String> TABLES =
      Set.of("services", "service_config_versions", "callbacks");
  private static final Set<String> EVENTS = Set.of("INSERT", "UPDATE");
  private static final Set<String> OUTCOMES = Set.of("SUCCESS", "MAPPING_ERROR", "REJECTED");

  private final String table;
  private final String triggerName;
  private final String functionName;

  private FailureTrigger(String table, String triggerName, String functionName) {
    this.table = table;
    this.triggerName = triggerName;
    this.functionName = functionName;
  }

  /** Ép lỗi mọi lần {@code event} trên {@code table} của dịch vụ {@code serviceCode}. */
  public static FailureTrigger on(String table, String event, String serviceCode) {
    return on(table, event, serviceCode, null);
  }

  /** Như trên, nhưng với {@code callbacks} có thể chỉ ép lỗi một loại {@code outcome}. */
  public static FailureTrigger on(String table, String event, String serviceCode, String outcome) {
    if (!TABLES.contains(table)
        || !EVENTS.contains(event)
        || !SERVICE_CODE.matcher(serviceCode).matches()
        || (outcome != null && (!OUTCOMES.contains(outcome) || !table.equals("callbacks")))) {
      throw new IllegalArgumentException("Tham số trigger không hợp lệ");
    }
    String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String fn = "test_fail_fn_" + id;
    String trg = "test_fail_trg_" + id;
    String condition = "NEW.service_code = '" + serviceCode + "'";
    if (outcome != null) {
      condition += " AND NEW.outcome = '" + outcome + "'";
    }
    try (Connection connection = open();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE FUNCTION "
              + fn
              + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test failure: %',"
              + " NEW::text; END $$");
      statement.execute(
          "CREATE TRIGGER "
              + trg
              + " BEFORE "
              + event
              + " ON "
              + table
              + " FOR EACH ROW WHEN ("
              + condition
              + ") EXECUTE FUNCTION "
              + fn
              + "()");
    } catch (SQLException e) {
      throw new IllegalStateException("Không tạo được trigger ép lỗi", e);
    }
    return new FailureTrigger(table, trg, fn);
  }

  @Override
  public void close() {
    try (Connection connection = open();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TRIGGER IF EXISTS " + triggerName + " ON " + table);
      statement.execute("DROP FUNCTION IF EXISTS " + functionName + "()");
    } catch (SQLException e) {
      throw new IllegalStateException("Không xóa được trigger ép lỗi", e);
    }
  }

  /** Xóa mọi trigger và hàm {@code test_fail_} còn sót; trả số đối tượng đã xóa. */
  public static int sweep() {
    int dropped = 0;
    try (Connection connection = open();
        Statement statement = connection.createStatement()) {
      List<String[]> triggers = new ArrayList<>();
      try (ResultSet rs =
          statement.executeQuery(
              "SELECT t.tgname, c.relname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid"
                  + " WHERE NOT t.tgisinternal AND t.tgname LIKE 'test\\_fail\\_%'")) {
        while (rs.next()) {
          triggers.add(new String[] {rs.getString(1), rs.getString(2)});
        }
      }
      for (String[] trigger : triggers) {
        if (GENERATED_NAME.matcher(trigger[0]).matches() && TABLES.contains(trigger[1])) {
          statement.execute("DROP TRIGGER IF EXISTS \"" + trigger[0] + "\" ON " + trigger[1]);
          dropped++;
        }
      }
      List<String> functions = new ArrayList<>();
      try (ResultSet rs =
          statement.executeQuery(
              "SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                  + " WHERE n.nspname = current_schema() AND p.proname LIKE 'test\\_fail\\_%'")) {
        while (rs.next()) {
          functions.add(rs.getString(1));
        }
      }
      for (String function : functions) {
        if (GENERATED_NAME.matcher(function).matches()) {
          statement.execute("DROP FUNCTION IF EXISTS \"" + function + "\"() CASCADE");
          dropped++;
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Không quét dọn được trigger ép lỗi", e);
    }
    return dropped;
  }

  /** Số trigger {@code test_fail_} đang tồn tại (cho kiểm thử). */
  public static int count() {
    try (Connection connection = open();
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal AND tgname LIKE"
                    + " 'test\\_fail\\_%'")) {
      rs.next();
      return rs.getInt(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Connection open() throws SQLException {
    return DriverManager.getConnection(
        TestDatabase.URL, TestDatabase.USERNAME, TestDatabase.PASSWORD);
  }
}
