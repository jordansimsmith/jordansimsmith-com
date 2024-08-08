package com.jordansimsmith.immersiontracker;

import javax.inject.Inject;

public class Hello {

  @Inject
  public Hello() {}

  public String hello() {
    return "hello, world";
  }
}
