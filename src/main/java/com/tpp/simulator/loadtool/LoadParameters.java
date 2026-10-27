package com.tpp.simulator.loadtool;

/**
 * Tham số của công cụ nạp (BR22.3), tự phân tích dạng {@code --tên=giá trị} và {@code --append},
 * không dùng thư viện đọc tham số.
 *
 * @param callbacks số lần nhận cần nạp
 * @param seed hạt giống cố định để chạy lại cho cùng dữ liệu
 * @param days rải thời điểm nhận trong chừng này ngày gần nhất
 * @param services số dịch vụ {@code LOAD_01} trở đi
 * @param append thêm dữ liệu vào dịch vụ đã có thay vì từ chối
 * @param database tên database đích (phải kết thúc bằng {@code _load})
 */
public record LoadParameters(
    int callbacks, long seed, int days, int services, boolean append, String database) {

  public static final int DEFAULT_CALLBACKS = 100_000;
  public static final long DEFAULT_SEED = 20_261_019L;
  public static final int DEFAULT_DAYS = 30;
  public static final int DEFAULT_SERVICES = 3;

  /**
   * @throws LoadRefusedException nếu tham số lạ, sai kiểu hoặc ngoài khoảng
   */
  public static LoadParameters parse(String[] args) {
    int callbacks = DEFAULT_CALLBACKS;
    long seed = DEFAULT_SEED;
    int days = DEFAULT_DAYS;
    int services = DEFAULT_SERVICES;
    boolean append = false;
    String database = DatabaseNameGuard.DEFAULT_DATABASE;
    for (String arg : args) {
      if (arg.equals("--append")) {
        append = true;
        continue;
      }
      int equals = arg.indexOf('=');
      if (!arg.startsWith("--") || equals < 0) {
        throw new LoadRefusedException("Tham số không nhận ra: " + shorten(arg));
      }
      String name = arg.substring(2, equals);
      String value = arg.substring(equals + 1);
      switch (name) {
        case "callbacks" -> callbacks = positiveInt(name, value);
        case "days" -> days = positiveInt(name, value);
        case "services" -> services = positiveInt(name, value);
        case "seed" -> seed = number(name, value);
        case "database" -> database = value;
        default -> throw new LoadRefusedException("Tham số không nhận ra: --" + shorten(name));
      }
    }
    return new LoadParameters(callbacks, seed, days, services, append, database);
  }

  private static int positiveInt(String name, String value) {
    long parsed = number(name, value);
    if (parsed < 1 || parsed > Integer.MAX_VALUE) {
      throw new LoadRefusedException("--" + name + " phải là số nguyên từ 1 trở lên.");
    }
    return (int) parsed;
  }

  private static long number(String name, String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new LoadRefusedException("--" + name + " phải là số nguyên.");
    }
  }

  private static String shorten(String text) {
    return text.length() > 40 ? text.substring(0, 40) + "..." : text;
  }
}
