package com.jordansimsmith.time;

import java.time.Instant;

public interface Clock {
  Instant now();
}
