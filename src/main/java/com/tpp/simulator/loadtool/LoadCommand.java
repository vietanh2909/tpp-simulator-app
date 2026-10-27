package com.tpp.simulator.loadtool;

import java.io.PrintStream;
import java.sql.SQLException;

/**
 * Trình tự khởi chạy của công cụ nạp (SD-07): tham số → kiểm tra tên database (TRƯỚC mọi kết nối) →
 * tạo database nếu chưa có → nạp. Mã thoát: 0 thành công; 1 lỗi lúc chạy; 2 từ chối trước khi ghi.
 * Mọi phụ thuộc bên ngoài nhận qua hàm tạo để kiểm thử, kể cả khẳng định lệnh bị từ chối không mở
 * kết nối nào.
 */
public final class LoadCommand {

  /** Kiểm tra và tạo database đích qua kết nối quản trị. */
  public interface DatabaseAdmin {
    boolean exists(String name) throws SQLException;

    void create(String name) throws SQLException;
  }

  /** Chạy phần nạp trên database đích (ngữ cảnh Spring riêng). */
  @FunctionalInterface
  public interface Starter {
    LoadReport run(
        LoadParameters parameters, String targetUrl, String user, String password, PrintStream out);
  }

  private final String baseUrl;
  private final String user;
  private final String password;
  private final PrintStream out;
  private final PrintStream err;
  private final DatabaseAdmin admin;
  private final Starter starter;

  public LoadCommand(
      String baseUrl,
      String user,
      String password,
      PrintStream out,
      PrintStream err,
      DatabaseAdmin admin,
      Starter starter) {
    this.baseUrl = baseUrl;
    this.user = user;
    this.password = password;
    this.out = out;
    this.err = err;
    this.admin = admin;
    this.starter = starter;
  }

  /** URL JDBC của cùng máy chủ nhưng database {@code database}; giữ phần tham số sau dấu ?. */
  static String urlFor(String url, String database) {
    int query = url.indexOf('?');
    String head = query >= 0 ? url.substring(0, query) : url;
    String tail = query >= 0 ? url.substring(query) : "";
    int slash = head.lastIndexOf('/');
    return head.substring(0, slash + 1) + database + tail;
  }

  public int run(String[] args) {
    LoadParameters parameters;
    try {
      parameters = LoadParameters.parse(args);
      LoadRunner.validateTarget(parameters);
    } catch (LoadRefusedException e) {
      return refuse(e.getMessage());
    }
    try {
      if (!admin.exists(parameters.database())) {
        admin.create(parameters.database());
        out.println("Đã tạo database " + parameters.database());
      }
    } catch (SQLException | RuntimeException e) {
      return refuse(
          "Không tạo được database "
              + parameters.database()
              + "; hãy tạo thủ công bằng lệnh: CREATE DATABASE \""
              + parameters.database()
              + "\";");
    }
    LoadReport report =
        starter.run(parameters, urlFor(baseUrl, parameters.database()), user, password, out);
    report.print(out, err);
    return report.exitCode();
  }

  private int refuse(String reason) {
    LoadReport.refused(reason).print(out, err);
    return 2;
  }
}
