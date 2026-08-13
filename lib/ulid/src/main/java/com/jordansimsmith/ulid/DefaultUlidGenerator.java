package com.jordansimsmith.ulid;

import com.jordansimsmith.time.Clock;
import java.security.SecureRandom;

public class DefaultUlidGenerator implements UlidGenerator {
  private static final char[] CROCKFORD_BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Clock clock;

  public DefaultUlidGenerator(Clock clock) {
    this.clock = clock;
  }

  @Override
  public String generate() {
    long timestamp = clock.now().toEpochMilli();
    var sb = new StringBuilder(26);

    for (int i = 9; i >= 0; i--) {
      sb.append(CROCKFORD_BASE32[(int) ((timestamp >> (i * 5)) & 0x1F)]);
    }

    byte[] randomBytes = new byte[10];
    RANDOM.nextBytes(randomBytes);
    long hi = 0;
    for (int i = 0; i < 5; i++) {
      hi = (hi << 8) | (randomBytes[i] & 0xFF);
    }
    long lo = 0;
    for (int i = 5; i < 10; i++) {
      lo = (lo << 8) | (randomBytes[i] & 0xFF);
    }
    for (int i = 7; i >= 0; i--) {
      sb.append(CROCKFORD_BASE32[(int) ((hi >> (i * 5)) & 0x1F)]);
    }
    for (int i = 7; i >= 0; i--) {
      sb.append(CROCKFORD_BASE32[(int) ((lo >> (i * 5)) & 0x1F)]);
    }

    return sb.toString();
  }
}
