package com.jordansimsmith.booktracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.jordansimsmith.auth.AuthorizerEvent;
import com.jordansimsmith.auth.AuthorizerResponse;
import com.jordansimsmith.auth.RequestAuthorizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuthHandler implements RequestHandler<AuthorizerEvent, AuthorizerResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(AuthHandler.class);
  private static final String SECRET = "book_tracker_api";

  private final RequestAuthorizer requestAuthorizer;

  public AuthHandler() {
    this.requestAuthorizer = BookTrackerFactory.create().requestAuthorizer();
  }

  @Override
  public AuthorizerResponse handleRequest(AuthorizerEvent event, Context context) {
    try {
      return requestAuthorizer.authorize(
          event.headers().get("Authorization"), SECRET, event.methodArn());
    } catch (RuntimeException e) {
      LOGGER.error("Runtime error during authorization", e);
      throw e;
    } catch (Exception e) {
      LOGGER.error("Error during authorization", e);
      throw new RuntimeException(e);
    }
  }
}
