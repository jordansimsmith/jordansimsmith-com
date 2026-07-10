package com.jordansimsmith.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.secrets.FakeSecrets;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.policybuilder.iam.IamAction;
import software.amazon.awssdk.policybuilder.iam.IamEffect;
import software.amazon.awssdk.policybuilder.iam.IamPolicy;
import software.amazon.awssdk.policybuilder.iam.IamResource;

public class AuthHandlerTest {
  private static final String SECRET_NAME = "auth_api";
  private static final String METHOD_ARN =
      "arn:aws:execute-api:ap-southeast-2:123456789012:abc123/prod/GET/trips";
  private static final String EXPECTED_RESOURCE =
      "arn:aws:execute-api:ap-southeast-2:123456789012:abc123/prod/*/*";

  private FakeSecrets fakeSecrets;
  private ObjectMapper objectMapper;

  private AuthHandler authHandler;

  @BeforeEach
  void setUp() {
    var factory = AuthTestFactory.create();
    fakeSecrets = factory.fakeSecrets();
    objectMapper = factory.objectMapper();
    authHandler = new AuthHandler(factory);
  }

  @Test
  void handleRequestShouldAllowCorrectUser() throws Exception {
    // arrange
    seedUsers(
        """
        {
          "users": [
            {
              "user": "alice",
              "password": "123"
            }
          ]
        }
        """);
    var event = event(basicAuth("alice", "123"), METHOD_ARN);

    // act
    var res = authHandler.handleRequest(event, null);

    // assert
    assertThat(res.principalId()).isEqualTo("alice");
    var policy = parsePolicy(res);
    assertThat(policy.version()).isEqualTo("2012-10-17");
    assertThat(policy.statements()).hasSize(1);
    var statement = policy.statements().get(0);
    assertThat(statement.actions()).contains(IamAction.create("execute-api:Invoke"));
    assertThat(statement.effect()).isEqualTo(IamEffect.ALLOW);
    assertThat(statement.resources()).contains(IamResource.create(EXPECTED_RESOURCE));
  }

  @Test
  void handleRequestShouldDenyIncorrectPassword() throws Exception {
    // arrange
    seedUsers(
        """
        {
          "users": [
            {
              "user": "alice",
              "password": "123"
            }
          ]
        }
        """);
    var event = event(basicAuth("alice", "456"), METHOD_ARN);

    // act
    var res = authHandler.handleRequest(event, null);

    // assert
    assertThat(res.principalId()).isEqualTo("alice");
    assertThat(parsePolicy(res).statements().get(0).effect()).isEqualTo(IamEffect.DENY);
  }

  @Test
  void handleRequestShouldDenyUnknownUser() throws Exception {
    // arrange
    seedUsers(
        """
        {
          "users": [
            {
              "user": "alice",
              "password": "123"
            }
          ]
        }
        """);
    var event = event(basicAuth("bob", "456"), METHOD_ARN);

    // act
    var res = authHandler.handleRequest(event, null);

    // assert
    assertThat(res.principalId()).isEqualTo("bob");
    assertThat(parsePolicy(res).statements().get(0).effect()).isEqualTo(IamEffect.DENY);
  }

  @Test
  void handleRequestShouldAllowWhenSecondUserInList() throws Exception {
    // arrange
    seedUsers(
        """
        {
          "users": [
            {
              "user": "alice",
              "password": "alice-pass"
            },
            {
              "user": "bob",
              "password": "bob-pass"
            }
          ]
        }
        """);
    var event = event(basicAuth("bob", "bob-pass"), METHOD_ARN);

    // act
    var res = authHandler.handleRequest(event, null);

    // assert
    assertThat(res.principalId()).isEqualTo("bob");
    assertThat(parsePolicy(res).statements().get(0).effect()).isEqualTo(IamEffect.ALLOW);
  }

  @Test
  void handleRequestShouldDenyWhenPasswordSwappedBetweenUsers() throws Exception {
    // arrange
    seedUsers(
        """
        {
          "users": [
            {
              "user": "alice",
              "password": "alice-pass"
            },
            {
              "user": "bob",
              "password": "bob-pass"
            }
          ]
        }
        """);
    var event = event(basicAuth("alice", "bob-pass"), METHOD_ARN);

    // act
    var res = authHandler.handleRequest(event, null);

    // assert
    assertThat(res.principalId()).isEqualTo("alice");
    assertThat(parsePolicy(res).statements().get(0).effect()).isEqualTo(IamEffect.DENY);
  }

  @Test
  void handleRequestShouldAllowWhenPasswordContainsColon() throws Exception {
    // arrange
    seedUsers(
        """
        {
          "users": [
            {
              "user": "alice",
              "password": "pass:word:123"
            }
          ]
        }
        """);
    var event = event(basicAuth("alice", "pass:word:123"), METHOD_ARN);

    // act
    var res = authHandler.handleRequest(event, null);

    // assert
    assertThat(res.principalId()).isEqualTo("alice");
    assertThat(parsePolicy(res).statements().get(0).effect()).isEqualTo(IamEffect.ALLOW);
  }

  @Test
  void handleRequestShouldReturnWildcardResourceForDeepMethodArn() throws Exception {
    // arrange
    seedUsers(
        """
        {
          "users": [
            {
              "user": "alice",
              "password": "123"
            }
          ]
        }
        """);
    var deepMethodArn =
        "arn:aws:execute-api:ap-southeast-2:123456789012:abc123/prod/DELETE/trips/abc-def";
    var event = event(basicAuth("alice", "123"), deepMethodArn);

    // act
    var res = authHandler.handleRequest(event, null);

    // assert
    assertThat(parsePolicy(res).statements().get(0).resources())
        .contains(IamResource.create(EXPECTED_RESOURCE));
  }

  @Test
  void handleRequestShouldThrowWhenAuthorizationHeaderIsMissing() {
    // arrange
    var event = new AuthHandler.AuthorizerEvent(Map.of(), Map.of(), METHOD_ARN);

    // act and assert
    assertThatThrownBy(() -> authHandler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Unauthorized");
  }

  @Test
  void handleRequestShouldThrowWhenAuthorizationHeaderIsEmpty() {
    // arrange
    var event = event("", METHOD_ARN);

    // act and assert
    assertThatThrownBy(() -> authHandler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Unauthorized");
  }

  @Test
  void handleRequestShouldThrowWhenAuthorizationHeaderIsMalformedBase64() {
    // arrange
    seedUsers(
        """
        {
          "users": [
            {
              "user": "alice",
              "password": "123"
            }
          ]
        }
        """);
    var event = event("Basic not_base_64!!", METHOD_ARN);

    // act and assert
    assertThatThrownBy(() -> authHandler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class);
  }

  private void seedUsers(String secretBody) {
    fakeSecrets.set(SECRET_NAME, secretBody);
  }

  private static AuthHandler.AuthorizerEvent event(String authorizationHeader, String methodArn) {
    return new AuthHandler.AuthorizerEvent(
        Map.of("Authorization", authorizationHeader), Map.of(), methodArn);
  }

  private static String basicAuth(String user, String password) {
    var credentials = user + ":" + password;
    return "Basic "
        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }

  private IamPolicy parsePolicy(AuthHandler.AuthorizerResponse response) throws Exception {
    return IamPolicy.fromJson(objectMapper.writeValueAsString(response.policyDocument()));
  }
}
