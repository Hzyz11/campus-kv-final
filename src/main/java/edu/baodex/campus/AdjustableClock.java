package edu.baodex.campus;

import java.time.*;
import java.util.concurrent.atomic.AtomicLong;

public class AdjustableClock extends Clock {
  final ZoneId zone;
  final AtomicLong offset;

  public AdjustableClock() {
    this(ZoneId.of("Asia/Shanghai"), new AtomicLong());
  }

  private AdjustableClock(ZoneId z, AtomicLong o) {
    zone = z;
    offset = o;
  }

  public ZoneId getZone() {
    return zone;
  }

  public Clock withZone(ZoneId z) {
    return new AdjustableClock(z, offset);
  }

  public Instant instant() {
    return Instant.now().plusSeconds(offset.get());
  }

  public void setOffset(long seconds) {
    if (Math.abs(seconds) > 3600) throw new IllegalArgumentException("offset +/-3600s");
    offset.set(seconds);
  }
}
