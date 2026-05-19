package com.jordansimsmith.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.secrets.FakeSecrets;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.policybuilder.iam.IamAction;
import software.amazon.awssdk.policybuilder.iam.IamEffect;
import software.amazon.awssdk.policybuilder.iam.IamPolicy;
import software.amazon.awssdk.policybuilder.iam.IamResource;

public class RequestAuthorizerTest {
  private static final String SECRET_NAME = "test_secret";
  private static final String METHOD_ARN =
      "arn:aws:execute-api:ap-southeast-2:123456789012:abc123/prod/GET/trips";
  private static final String EXPECTED_RESOURCE =
      "arn:aws:execute-api:ap-southeast-2:123456789012:abc123/prod/*/*";

  private FakeSecrets fakeSecrets;
  private ObjectMapper objectMapper;

  private RequestAuthorizer authorizer;

  @BeforeEach
  void setUp() {
    fakeSecrets = new FakeSecrets();
    objectMapper = new ObjectMapper();
    authorizer = new RequestAuthorizer(fakeSecrets, objectMapper);
  }

  @Test
  void authorizeShouldAllowCorrectUser() throws Exception {
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
    var token = basicAuth("alice", "123");

    // act
    var res = authorizer.authorize(token, SECRET_NAME, METHOD_ARN);

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
  void authorizeShouldDenyIncorrectPassword() throws Exception {
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
    var token = basicAuth("alice", "456");

    // act
    var res = authorizer.authorize(token, SECRET_NAME, METHOD_ARN);

    // assert
    assertThat(res.principalId()).isEqualTo("alice");
    assertThat(parsePolicy(res).statements().get(0).effect()).isEqualTo(IamEffect.DENY);
  }

  @Test
  void authorizeShouldDenyUnknownUser() throws Exception {
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
    var token = basicAuth("bob", "456");

    // act
    var res = authorizer.authorize(token, SECRET_NAME, METHOD_ARN);

    // assert
    assertThat(res.principalId()).isEqualTo("bob");
    assertThat(parsePolicy(res).statements().get(0).effect()).isEqualTo(IamEffect.DENY);
  }

  @Test
  void authorizeShouldAllowWhenSecondUserInList() throws Exception {
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
    var token = basicAuth("bob", "bob-pass");

    // act
    var res = authorizer.authorize(token, SECRET_NAME, METHOD_ARN);

    // assert
    assertThat(res.principalId()).isEqualTo("bob");
    assertThat(parsePolicy(res).statements().get(0).effect()).isEqualTo(IamEffect.ALLOW);
  }

  @Test
  void authorizeShouldDenyWhenPasswordSwappedBetweenUsers() throws Exception {
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
    var token = basicAuth("alice", "bob-pass");

    // act
    var res = authorizer.authorize(token, SECRET_NAME, METHOD_ARN);

    // assert
    assertThat(res.principalId()).isEqualTo("alice");
    assertThat(parsePolicy(res).statements().get(0).effect()).isEqualTo(IamEffect.DENY);
  }

  @Test
  void authorizeShouldAllowWhenPasswordContainsColon() throws Exception {
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
    var token = basicAuth("alice", "pass:word:123");

    // act
    var res = authorizer.authorize(token, SECRET_NAME, METHOD_ARN);

    // assert
    assertThat(res.principalId()).isEqualTo("alice");
    assertThat(parsePolicy(res).statements().get(0).effect()).isEqualTo(IamEffect.ALLOW);
  }

  @Test
  void authorizeShouldReturnWildcardResourceForDeepMethodArn() throws Exception {
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
    var token = basicAuth("alice", "123");
    var deepMethodArn =
        "arn:aws:execute-api:ap-southeast-2:123456789012:abc123/prod/DELETE/trips/abc-def";

    // act
    var res = authorizer.authorize(token, SECRET_NAME, deepMethodArn);

    // assert
    assertThat(parsePolicy(res).statements().get(0).resources())
        .contains(IamResource.create(EXPECTED_RESOURCE));
  }

  @Test
  void authorizeShouldThrowWhenAuthorizationHeaderIsNull() {
    // act and assert
    assertThatThrownBy(() -> authorizer.authorize(null, SECRET_NAME, METHOD_ARN))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Unauthorized");
  }

  @Test
  void authorizeShouldThrowWhenAuthorizationHeaderIsEmpty() {
    // act and assert
    assertThatThrownBy(() -> authorizer.authorize("", SECRET_NAME, METHOD_ARN))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Unauthorized");
  }

  @Test
  void authorizeShouldThrowWhenAuthorizationHeaderIsMalformedBase64() {
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

    // act and assert
    assertThatThrownBy(() -> authorizer.authorize("Basic not_base_64!!", SECRET_NAME, METHOD_ARN))
        .isInstanceOf(RuntimeException.class);
  }

  private void seedUsers(String secretBody) {
    fakeSecrets.set(SECRET_NAME, secretBody);
  }

  private static String basicAuth(String user, String password) {
    var credentials = user + ":" + password;
    return "Basic "
        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }

  private IamPolicy parsePolicy(AuthorizerResponse response) throws Exception {
    return IamPolicy.fromJson(objectMapper.writeValueAsString(response.policyDocument()));
  }
}
