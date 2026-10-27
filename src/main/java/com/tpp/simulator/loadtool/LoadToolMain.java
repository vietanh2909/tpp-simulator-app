package com.tpp.simulator.loadtool;

/** Điểm vào của công cụ nạp: một dòng, không logic (chạy bằng PropertiesLauncher, xem README). */
public final class LoadToolMain {

  private LoadToolMain() {}

  public static void main(String[] args) {
    System.exit(LoadBootstrap.command(System.getenv(), System.out, System.err).run(args));
  }
}
