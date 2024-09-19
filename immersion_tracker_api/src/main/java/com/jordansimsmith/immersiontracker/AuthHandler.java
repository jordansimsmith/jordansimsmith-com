package com.jordansimsmith.immersiontracker;

import static com.jordansimsmith.immersiontracker.AuthHandler.AuthorizerEvent;
import static com.jordansimsmith.immersiontracker.AuthHandler.AuthorizerResponse;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.jordansimsmith.secrets.Secrets;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import software.amazon.awssdk.policybuilder.iam.IamEffect;
import software.amazon.awssdk.policybuilder.iam.IamPolicy;
import software.amazon.awssdk.policybuilder.iam.IamStatement;

public class AuthHandler implements RequestHandler<AuthorizerEvent, AuthorizerResponse> {
  @VisibleForTesting static final String USERS_SECRET = "immersion_tracker_api_users";

  private final Secrets secrets;
  private final ObjectMapper objectMapper;

  public record AuthorizerEvent(String authorizationToken, String methodArn) {}

  public record AuthorizerResponse(String principalId, Object policyDocument) {}

  private record User(
      @JsonProperty("user") String user, @JsonProperty("password") String password) {}

  public AuthHandler() {
    this(ImmersionTrackerFactory.create());
  }

  @VisibleForTesting
  AuthHandler(ImmersionTrackerFactory factory) {
    this.secrets = factory.secrets();
    this.objectMapper = factory.objectMapper();
  }

  @Override
  public AuthorizerResponse handleRequest(AuthorizerEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public AuthorizerResponse doHandleRequest(AuthorizerEvent event, Context context)
      throws Exception {
    var token = event.authorizationToken();
    if (Strings.isNullOrEmpty(token)) {
      throw new RuntimeException("Unauthorized");
    }

    var base64 = token.replace("Basic ", "");
    var bytes = Base64.getDecoder().decode(base64);
    var credentials = new String(bytes, StandardCharsets.UTF_8).split(":", 2);
    var user = new User(credentials[0], credentials[1]);

    var secret = secrets.get(USERS_SECRET);
    var users = objectMapper.readValue(secret, User[].class);

    if (Arrays.asList(users).contains(user)) {
      return response(user.user, IamEffect.ALLOW, event.methodArn());
    }

    return response(user.user, IamEffect.DENY, event.methodArn());
  }

  private AuthorizerResponse response(String principal, IamEffect effect, String resource)
      throws JsonProcessingException {
    var policy =
        IamPolicy.builder()
            .version("2012-10-17")
            .addStatement(
                IamStatement.builder()
                    .addAction("execute-api:Invoke")
                    .effect(effect)
                    .addResource(resource)
                    .build())
            .build()
            .toJson();
    var policyMap = objectMapper.readValue(policy, LinkedHashMap.class);

    return new AuthorizerResponse(principal, policyMap);
  }
}
