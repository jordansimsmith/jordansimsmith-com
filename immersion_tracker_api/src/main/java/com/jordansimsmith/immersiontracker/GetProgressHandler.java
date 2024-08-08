package com.jordansimsmith.immersiontracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

public class GetProgressHandler implements RequestHandler<Object, String> {
  private final Hello hello;

  public GetProgressHandler() {
    this.hello = DaggerImmersionTrackerComponent.create().hello();
  }

  @Override
  public String handleRequest(Object s, Context context) {
    return hello.hello();
  }
}
