package com.jordansimsmith.immersiontracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

public class GetProgressHandler implements RequestHandler<Object, String> {
  @Override
  public String handleRequest(Object s, Context context) {
    return "hello, world";
  }
}
