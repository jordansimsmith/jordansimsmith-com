package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.secrets.FakeSecrets;
import com.jordansimsmith.testcontainers.DynamoDbContainer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.policybuilder.iam.IamAction;
import software.amazon.awssdk.policybuilder.iam.IamEffect;
import software.amazon.awssdk.policybuilder.iam.IamPolicy;
import software.amazon.awssdk.policybuilder.iam.IamResource;

@Testcontainers
public class AuthHandlerIntegrationTest {
  private FakeSecrets fakeSecrets;
  private ObjectMapper objectMapper;

  private AuthHandler authHandler;

  @Container DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeEach
  void setUp() {
    var factory = ImmersionTrackerTestFactory.create(dynamoDbContainer.getEndpoint());
    fakeSecrets = factory.fakeSecrets();
    objectMapper = factory.objectMapper();

    authHandler = new AuthHandler(factory);
  }

  @Test
  void handleRequestShouldAllowCorrectUser() throws Exception {
    // arrange
    var secret =
        """
        {
          "users": [
            {
              "user": "alice",
              "password": "123"
            }
          ]
        }
        """;
    fakeSecrets.set(AuthHandler.SECRET, secret);
    var token =
        "Basic " + Base64.getEncoder().encodeToString("alice:123".getBytes(StandardCharsets.UTF_8));
    var headers = Map.of("Authorization", token);
    var queryStringParameters = Map.of("user", "alice");
    var event = new AuthHandler.AuthorizerEvent(headers, queryStringParameters, "method");

    // act
    var res = authHandler.handleRequest(event, null);

    // assert
    assertThat(res.principalId()).isEqualTo("alice");
    var json = objectMapper.writeValueAsString(res.policyDocument());
    var policy = IamPolicy.fromJson(json);
    assertThat(policy.version()).isEqualTo("2012-10-17");
    assertThat(policy.statements()).hasSize(1);
    var statement = policy.statements().get(0);
    assertThat(statement.actions()).contains(IamAction.create("execute-api:Invoke"));
    assertThat(statement.effect()).isEqualTo(IamEffect.ALLOW);
    assertThat(statement.resources()).contains(IamResource.create("method"));
  }

  @Test
  void handleRequestShouldDenyIncorrectPassword() throws Exception {
    // arrange
    var secret =
        """
        {
          "users": [
            {
              "user": "alice",
              "password": "123"
            }
          ]
        }
        """;
    fakeSecrets.set(AuthHandler.SECRET, secret);
    var token =
        "Basic " + Base64.getEncoder().encodeToString("alice:456".getBytes(StandardCharsets.UTF_8));
    var headers = Map.of("Authorization", token);
    var queryStringParameters = Map.of("user", "alice");
    var event = new AuthHandler.AuthorizerEvent(headers, queryStringParameters, "method");

    // act
    var res = authHandler.handleRequest(event, null);

    // assert
    assertThat(res.principalId()).isEqualTo("alice");
    var json = objectMapper.writeValueAsString(res.policyDocument());
    var policy = IamPolicy.fromJson(json);
    assertThat(policy.statements().get(0).effect()).isEqualTo(IamEffect.DENY);
  }

  @Test
  void handleRequestShouldDenyUnknownUser() throws Exception {
    // arrange
    var secret =
        """
        {
          "users": [
            {
              "user": "alice",
              "password": "123"
            }
          ]
        }
        """;
    fakeSecrets.set(AuthHandler.SECRET, secret);
    var token =
        "Basic " + Base64.getEncoder().encodeToString("bob:456".getBytes(StandardCharsets.UTF_8));
    var headers = Map.of("Authorization", token);
    var queryStringParameters = Map.of("user", "bob");
    var event = new AuthHandler.AuthorizerEvent(headers, queryStringParameters, "method");

    // act
    var res = authHandler.handleRequest(event, null);

    // assert
    assertThat(res.principalId()).isEqualTo("bob");
    var json = objectMapper.writeValueAsString(res.policyDocument());
    var policy = IamPolicy.fromJson(json);
    assertThat(policy.statements().get(0).effect()).isEqualTo(IamEffect.DENY);
  }

  @Test
  void handleRequestShouldDenyNonMatchingUser() throws Exception {
    // arrange
    var secret =
        """
        {
          "users": [
            {
              "user": "alice",
              "password": "123"
            }
          ]
        }
        """;
    fakeSecrets.set(AuthHandler.SECRET, secret);
    var token =
        "Basic " + Base64.getEncoder().encodeToString("alice:456".getBytes(StandardCharsets.UTF_8));
    var headers = Map.of("Authorization", token);
    var queryStringParameters = Map.of("user", "bob");
    var event = new AuthHandler.AuthorizerEvent(headers, queryStringParameters, "method");

    // act
    var res = authHandler.handleRequest(event, null);

    // assert
    assertThat(res.principalId()).isEqualTo("alice");
    var json = objectMapper.writeValueAsString(res.policyDocument());
    var policy = IamPolicy.fromJson(json);
    assertThat(policy.statements().get(0).effect()).isEqualTo(IamEffect.DENY);
  }
}
