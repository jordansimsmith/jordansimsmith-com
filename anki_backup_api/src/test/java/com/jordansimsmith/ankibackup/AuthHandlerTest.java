package com.jordansimsmith.ankibackup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.secrets.FakeSecrets;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.policybuilder.iam.IamEffect;
import software.amazon.awssdk.policybuilder.iam.IamPolicy;

public class AuthHandlerTest {
  private FakeSecrets fakeSecrets;
  private ObjectMapper objectMapper;
  private AuthHandler authHandler;

  @BeforeEach
  void setUp() {
    var factory = AnkiBackupTestFactory.create(URI.create("unused"), URI.create("unused"));
    fakeSecrets = factory.fakeSecrets();
    objectMapper = factory.objectMapper();
    authHandler = new AuthHandler(factory);
  }

  @Test
  void handleRequestShouldAllowWhenPasswordContainsColon() throws Exception {
    // arrange
    var secret =
        """
        {
          "users": [
            {
              "user": "alice",
              "password": "pass:word:123"
            }
          ]
        }
        """;
    fakeSecrets.set(AuthHandler.SECRET, secret);
    var token =
        "Basic "
            + Base64.getEncoder()
                .encodeToString("alice:pass:word:123".getBytes(StandardCharsets.UTF_8));
    var event = new AuthHandler.AuthorizerEvent(Map.of("Authorization", token), Map.of(), "method");

    // act
    var res = authHandler.handleRequest(event, null);

    // assert
    assertThat(res.principalId()).isEqualTo("alice");
    var policy = IamPolicy.fromJson(objectMapper.writeValueAsString(res.policyDocument()));
    assertThat(policy.statements().get(0).effect()).isEqualTo(IamEffect.ALLOW);
  }

  @Test
  void handleRequestShouldAllowWhenSecondUserInList() throws Exception {
    // arrange
    var secret =
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
        """;
    fakeSecrets.set(AuthHandler.SECRET, secret);
    var token =
        "Basic "
            + Base64.getEncoder().encodeToString("bob:bob-pass".getBytes(StandardCharsets.UTF_8));
    var event = new AuthHandler.AuthorizerEvent(Map.of("Authorization", token), Map.of(), "method");

    // act
    var res = authHandler.handleRequest(event, null);

    // assert
    assertThat(res.principalId()).isEqualTo("bob");
    var policy = IamPolicy.fromJson(objectMapper.writeValueAsString(res.policyDocument()));
    assertThat(policy.statements().get(0).effect()).isEqualTo(IamEffect.ALLOW);
  }

  @Test
  void handleRequestShouldDenyWhenPasswordSwappedBetweenUsers() throws Exception {
    // arrange
    var secret =
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
        """;
    fakeSecrets.set(AuthHandler.SECRET, secret);
    var token =
        "Basic "
            + Base64.getEncoder().encodeToString("alice:bob-pass".getBytes(StandardCharsets.UTF_8));
    var event = new AuthHandler.AuthorizerEvent(Map.of("Authorization", token), Map.of(), "method");

    // act
    var res = authHandler.handleRequest(event, null);

    // assert
    assertThat(res.principalId()).isEqualTo("alice");
    var policy = IamPolicy.fromJson(objectMapper.writeValueAsString(res.policyDocument()));
    assertThat(policy.statements().get(0).effect()).isEqualTo(IamEffect.DENY);
  }

  @Test
  void secretConstantShouldMatchExpectedValue() {
    assertThat(AuthHandler.SECRET).isEqualTo("anki_backup_api");
  }
}
