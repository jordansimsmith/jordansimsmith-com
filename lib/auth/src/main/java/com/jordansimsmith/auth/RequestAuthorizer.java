package com.jordansimsmith.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.jordansimsmith.secrets.Secrets;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import javax.annotation.Nullable;
import software.amazon.awssdk.policybuilder.iam.IamEffect;
import software.amazon.awssdk.policybuilder.iam.IamPolicy;
import software.amazon.awssdk.policybuilder.iam.IamStatement;

public class RequestAuthorizer {
  private final Secrets secrets;
  private final ObjectMapper objectMapper;

  RequestAuthorizer(Secrets secrets, ObjectMapper objectMapper) {
    this.secrets = secrets;
    this.objectMapper = objectMapper;
  }

  public AuthorizerResponse authorize(
      @Nullable String authorizationHeader, String secretName, String methodArn) throws Exception {
    if (Strings.isNullOrEmpty(authorizationHeader)) {
      throw new RuntimeException("Unauthorized");
    }

    var base64 = authorizationHeader.substring("Basic".length()).trim();
    var bytes = Base64.getDecoder().decode(base64);
    var credentials = new String(bytes, StandardCharsets.UTF_8).split(":", 2);
    var user = new User(credentials[0], credentials[1]);

    var secret = secrets.get(secretName);
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

  private record User(
      @JsonProperty("user") String user, @JsonProperty("password") String password) {}
}
