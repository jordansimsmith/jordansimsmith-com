package com.jordansimsmith.immersiontracker;

import static com.jordansimsmith.immersiontracker.AuthHandler.AuthorizerEvent;
import static com.jordansimsmith.immersiontracker.AuthHandler.AuthorizerResponse;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import java.util.LinkedHashMap;
import software.amazon.awssdk.policybuilder.iam.IamEffect;
import software.amazon.awssdk.policybuilder.iam.IamPolicy;
import software.amazon.awssdk.policybuilder.iam.IamStatement;

public class AuthHandler implements RequestHandler<AuthorizerEvent, AuthorizerResponse> {
  private final ObjectMapper objectMapper;

  public record AuthorizerEvent(String authorizationToken, String methodArn) {}

  public record AuthorizerResponse(String principalId, Object policyDocument) {}

  public AuthHandler() {
    this(ImmersionTrackerFactory.create());
  }

  @VisibleForTesting
  AuthHandler(ImmersionTrackerFactory factory) {
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
    var token = event.authorizationToken.replace("Bearer ", "");

    if (Strings.isNullOrEmpty(token)) {
      throw new RuntimeException("Unauthorized");
    }

    if ("jordan".equals(token)) {
      return response(token, IamEffect.ALLOW, event.methodArn());
    }

    return response(token, IamEffect.DENY, event.methodArn());
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
