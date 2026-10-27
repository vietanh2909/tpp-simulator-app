package com.tpp.simulator.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Đồng hồ thay thế được: mặc định theo giờ thật, hoặc cố định ở một thời điểm do kiểm thử đặt. */
public class TestClock extends Clock {

  private volatile Instant fixed;

  /** Cố định đồng hồ tại thời điểm cho trước. */
  public void set(Instant instant) {
    this.fixed = instant;
  }

  /** Quay lại giờ thật. */
  public void reset() {
    this.fixed = null;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    Instant value = fixed;
    return value != null ? value : Instant.now();
  }
}
