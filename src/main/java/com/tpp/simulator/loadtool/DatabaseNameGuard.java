package com.tpp.simulator.loadtool;

import java.util.regex.Pattern;

/**
 * Chặn công cụ nạp trước MỌI kết nối (SD-07, BR22.2): chỉ nhận tên database kết thúc bằng {@code
 * _load}, gồm chữ thường, chữ số và gạch dưới, bắt đầu bằng chữ cái, dài tối đa 63 ký tự. Tập ký tự
 * chặt này cũng là lý do {@code CREATE DATABASE "<tên>"} an toàn.
 */
public final class DatabaseNameGuard {

  /** Tên mặc định của database nạp. */
  public static final String DEFAULT_DATABASE = "tpp_simulator_load";

  private static final Pattern VALID = Pattern.compile("^[a-z][a-z0-9_]*_load$");
  private static final int MAX_LENGTH = 63;

  private DatabaseNameGuard() {}

  /**
   * @throws LoadRefusedException nếu tên không phải database nạp hợp lệ
   */
  public static void validate(String name) {
    if (name == null
        || name.isEmpty()
        || name.length() > MAX_LENGTH
        || !VALID.matcher(name).matches()) {
      throw new LoadRefusedException(
          "Tên database nạp phải kết thúc bằng _load, chỉ gồm chữ thường, chữ số, gạch dưới,"
              + " bắt đầu bằng chữ cái và dài tối đa 63 ký tự (ví dụ "
              + DEFAULT_DATABASE
              + ").");
    }
  }
}
