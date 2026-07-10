package com.jordansimsmith.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.jordansimsmith.secrets.Secrets;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.policybuilder.iam.IamEffect;
import software.amazon.awssdk.policybuilder.iam.IamPolicy;
import software.amazon.awssdk.policybuilder.iam.IamStatement;

public class AuthHandler
    implements RequestHandler<AuthHandler.AuthorizerEvent, AuthHandler.AuthorizerResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(AuthHandler.class);
  private static final String SECRET = "auth_api";

  public record AuthorizerEvent(
      Map<String, String> headers, Map<String, String> queryStringParameters, String methodArn) {}

  public record AuthorizerResponse(String principalId, Object policyDocument) {}

  private record User(
      @JsonProperty("user") String user, @JsonProperty("password") String password) {}

  private final Secrets secrets;
  private final ObjectMapper objectMapper;

  public AuthHandler() {
    this(AuthFactory.create());
  }

  @VisibleForTesting
  AuthHandler(AuthFactory factory) {
    this.secrets = factory.secrets();
    this.objectMapper = factory.objectMapper();
  }

  @Override
  public AuthorizerResponse handleRequest(AuthorizerEvent event, Context context) {
    try {
      return authorize(event.headers().get("Authorization"), event.methodArn());
    } catch (RuntimeException e) {
      LOGGER.error("Runtime error during authorization", e);
      throw e;
    } catch (Exception e) {
      LOGGER.error("Error during authorization", e);
      throw new RuntimeException(e);
    }
  }

  private AuthorizerResponse authorize(String authorizationHeader, String methodArn)
      throws Exception {
    if (Strings.isNullOrEmpty(authorizationHeader)) {
      throw new RuntimeException("Unauthorized");
    }

    var base64 = authorizationHeader.substring("Basic".length()).trim();
    var bytes = Base64.getDecoder().decode(base64);
    var credentials = new String(bytes, StandardCharsets.UTF_8).split(":", 2);
    var user = new User(credentials[0], credentials[1]);

    var secret = secrets.get(SECRET);
    var users = objectMapper.treeToValue(objectMapper.readTree(secret).get("users"), User[].class);

    var effect = Arrays.asList(users).contains(user) ? IamEffect.ALLOW : IamEffect.DENY;
    return buildResponse(user.user(), effect, methodArn);
  }

  private AuthorizerResponse buildResponse(String principal, IamEffect effect, String methodArn)
      throws Exception {
    // methodArn is arn:aws:execute-api:region:account:apiId/stage/METHOD/path.
    // Broaden to apiId/stage/*/* so the cached policy applies to every endpoint
    // in the same API stage; otherwise authorizer_result_ttl_in_seconds > 0
    // serves a policy scoped to the first methodArn for subsequent requests.
    var stageEnd = methodArn.indexOf('/', methodArn.indexOf('/') + 1);
    var resource = stageEnd > 0 ? methodArn.substring(0, stageEnd) + "/*/*" : methodArn;

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
