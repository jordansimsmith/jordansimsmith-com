package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.secrets.FakeSecrets;
import com.jordansimsmith.time.FakeClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class SettingsHandlerIntegrationTest {
  private FakeClock fakeClock;
  private FakeSecrets fakeSecrets;
  private ObjectMapper objectMapper;

  private UpdateSettingsHandler updateSettingsHandler;
  private GetSettingsHandler getSettingsHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory = TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint());
    var table = factory.tcgInventoryTable();
    DynamoDbUtils.createTable(factory.dynamoDbClient(), table);
  }

  @BeforeEach
  void setUp() {
    var factory = TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    fakeSecrets = factory.fakeSecrets();
    objectMapper = factory.objectMapper();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeSecrets.set("tcg_inventory", "{}");

    updateSettingsHandler = new UpdateSettingsHandler(factory);
    getSettingsHandler = new GetSettingsHandler(factory);
  }

  @Test
  void updateSettingsShouldStoreCredentialAndUpdateMetadata() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var event = buildEvent("jordan", "{\"refresh_token\": \"my-secret-token\"}");

    // act
    var response = updateSettingsHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("credential_set").asBoolean()).isTrue();
    assertThat(body.get("updated_at").asLong()).isEqualTo(1700000000);
    assertThat(body.get("track_orders_after").isNull()).isTrue();

    var secretJson = fakeSecrets.get("tcg_inventory");
    var secretNode = objectMapper.readTree(secretJson);
    assertThat(secretNode.get("jordan").asText()).isEqualTo("my-secret-token");
  }

  @Test
  void getSettingsShouldReturnCredentialSetAfterUpdate() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    updateSettingsHandler.handleRequest(
        buildEvent("jordan", "{\"refresh_token\": \"token\"}"), null);

    // act
    var response = getSettingsHandler.handleRequest(buildEvent("jordan", null), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("credential_set").asBoolean()).isTrue();
    assertThat(body.get("updated_at").asLong()).isEqualTo(1700000000);
    assertThat(body.get("track_orders_after").isNull()).isTrue();
  }

  @Test
  void getSettingsShouldReturnNotSetWhenNeverWritten() throws Exception {
    // act
    var response = getSettingsHandler.handleRequest(buildEvent("jordan", null), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("credential_set").asBoolean()).isFalse();
    assertThat(body.get("updated_at").isNull()).isTrue();
    assertThat(body.get("track_orders_after").isNull()).isTrue();
  }

  @Test
  void updateSettingsShouldUpdateTimestampOnSubsequentWrite() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    updateSettingsHandler.handleRequest(
        buildEvent("jordan", "{\"refresh_token\": \"token1\"}"), null);

    fakeClock.setTime(Instant.ofEpochSecond(1700001000));
    updateSettingsHandler.handleRequest(
        buildEvent("jordan", "{\"refresh_token\": \"token2\"}"), null);

    // act
    var response = getSettingsHandler.handleRequest(buildEvent("jordan", null), null);

    // assert
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("updated_at").asLong()).isEqualTo(1700001000);

    var secretNode = objectMapper.readTree(fakeSecrets.get("tcg_inventory"));
    assertThat(secretNode.get("jordan").asText()).isEqualTo("token2");
  }

  @Test
  void updateSettingsShouldNotExposeTokenInResponse() throws Exception {
    // arrange
    var event = buildEvent("jordan", "{\"refresh_token\": \"super-secret-value\"}");

    // act
    var response = updateSettingsHandler.handleRequest(event, null);

    // assert
    assertThat(response.getBody()).doesNotContain("super-secret-value");
  }

  @Test
  void getSettingsShouldNotExposeTokenInResponse() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    updateSettingsHandler.handleRequest(
        buildEvent("jordan", "{\"refresh_token\": \"hidden-token\"}"), null);

    // act
    var response = getSettingsHandler.handleRequest(buildEvent("jordan", null), null);

    // assert
    assertThat(response.getBody()).doesNotContain("hidden-token");
  }

  @Test
  void updateSettingsShouldReturnBadRequestWhenNoFieldsProvided() throws Exception {
    // arrange
    var event = buildEvent("jordan", "{}");

    // act
    var response = updateSettingsHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void updateSettingsShouldPreserveOtherUsersInSecret() throws Exception {
    // arrange
    fakeSecrets.set("tcg_inventory", "{\"alice\": \"alice-token\"}");
    var event = buildEvent("jordan", "{\"refresh_token\": \"jordan-token\"}");

    // act
    updateSettingsHandler.handleRequest(event, null);

    // assert
    var secretNode = objectMapper.readTree(fakeSecrets.get("tcg_inventory"));
    assertThat(secretNode.get("jordan").asText()).isEqualTo("jordan-token");
    assertThat(secretNode.get("alice").asText()).isEqualTo("alice-token");
  }

  @Test
  void updateSettingsShouldStoreTrackOrdersAfterWithoutAffectingCredential() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var event = buildEvent("jordan", "{\"track_orders_after\": 1723363200}");

    // act
    var response = updateSettingsHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("credential_set").asBoolean()).isFalse();
    assertThat(body.get("updated_at").isNull()).isTrue();
    assertThat(body.get("track_orders_after").asLong()).isEqualTo(1723363200);
  }

  @Test
  void updateSettingsShouldPreserveTrackOrdersAfterWhenUpdatingCredential() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    updateSettingsHandler.handleRequest(
        buildEvent("jordan", "{\"track_orders_after\": 1723363200}"), null);

    fakeClock.setTime(Instant.ofEpochSecond(1700001000));
    var event = buildEvent("jordan", "{\"refresh_token\": \"new-token\"}");

    // act
    var response = updateSettingsHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("credential_set").asBoolean()).isTrue();
    assertThat(body.get("updated_at").asLong()).isEqualTo(1700001000);
    assertThat(body.get("track_orders_after").asLong()).isEqualTo(1723363200);
  }

  @Test
  void updateSettingsShouldPreserveCredentialWhenUpdatingTrackOrdersAfter() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    updateSettingsHandler.handleRequest(
        buildEvent("jordan", "{\"refresh_token\": \"my-token\"}"), null);

    var event = buildEvent("jordan", "{\"track_orders_after\": 1723363200}");

    // act
    var response = updateSettingsHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("credential_set").asBoolean()).isTrue();
    assertThat(body.get("updated_at").asLong()).isEqualTo(1700000000);
    assertThat(body.get("track_orders_after").asLong()).isEqualTo(1723363200);
  }

  @Test
  void getSettingsShouldReturnTrackOrdersAfter() throws Exception {
    // arrange
    updateSettingsHandler.handleRequest(
        buildEvent("jordan", "{\"track_orders_after\": 1723363200}"), null);

    // act
    var response = getSettingsHandler.handleRequest(buildEvent("jordan", null), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("track_orders_after").asLong()).isEqualTo(1723363200);
  }

  private APIGatewayV2HTTPEvent buildEvent(String user, String body) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", authHeader))
        .withBody(body)
        .build();
  }
}
