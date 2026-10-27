package com.tpp.simulator.loadtool;

/** Công cụ nạp từ chối chạy trước khi ghi bất cứ thứ gì (mã thoát 2). Thông điệp an toàn để in. */
public final class LoadRefusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public LoadRefusedException(String message) {
    super(message, null, false, false);
  }
}
