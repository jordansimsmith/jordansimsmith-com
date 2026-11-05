package com.jordansimsmith.tcginventory.orders.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class OrderSettingsHandlerIntegrationTest {
  private static final String ALLOWED_ORIGIN = "https://tcg-inventory.jordansimsmith.com";
  private ObjectMapper objectMapper;
  private UpdateOrderSettingsHandler updateHandler;
  private GetOrderSettingsHandler getHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(
            dynamoDbContainer.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.tableDefinition());
  }

  @BeforeEach
  void setUp() {
    var factory =
        TcgInventoryTestFactory.create(
            dynamoDbContainer.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);
    DynamoDbUtils.reset(factory.dynamoDbClient());
    objectMapper = factory.objectMapper();
    updateHandler = new UpdateOrderSettingsHandler(factory);
    getHandler = new GetOrderSettingsHandler(factory);
  }

  @Test
  void getOrderSettingsShouldReturnNullWhenUnconfigured() throws Exception {
    var response = getHandler.handleRequest(buildEvent("jordan", null), null);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8")
        .containsEntry("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
    assertThat(objectMapper.readTree(response.getBody()))
        .isEqualTo(objectMapper.readTree("{\"track_orders_after\":null}"));
  }

  @Test
  void updateOrderSettingsShouldReturnNoContentAndPersistCutoff() throws Exception {
    var response =
        updateHandler.handleRequest(
            buildEvent("jordan", "{\"track_orders_after\":1723363200}"), null);

    assertThat(response.getStatusCode()).isEqualTo(204);
    assertThat(response.getBody()).isNull();
    assertThat(response.getHeaders()).containsEntry("Access-Control-Allow-Origin", ALLOWED_ORIGIN);

    var getResponse = getHandler.handleRequest(buildEvent("jordan", null), null);
    assertThat(objectMapper.readTree(getResponse.getBody()))
        .isEqualTo(objectMapper.readTree("{\"track_orders_after\":1723363200}"));

    var nullPatchResponse =
        updateHandler.handleRequest(buildEvent("jordan", "{\"track_orders_after\":null}"), null);
    assertThat(nullPatchResponse.getStatusCode()).isEqualTo(204);

    var omittedPatchResponse = updateHandler.handleRequest(buildEvent("jordan", "{}"), null);
    assertThat(omittedPatchResponse.getStatusCode()).isEqualTo(204);

    var unchangedSettings = getHandler.handleRequest(buildEvent("jordan", null), null);
    assertThat(objectMapper.readTree(unchangedSettings.getBody()))
        .isEqualTo(objectMapper.readTree("{\"track_orders_after\":1723363200}"));
  }

  @Test
  void orderSettingsShouldRemainIsolatedByUser() throws Exception {
    updateHandler.handleRequest(buildEvent("jordan", "{\"track_orders_after\":1723363200}"), null);

    var jordan = getHandler.handleRequest(buildEvent("jordan", null), null);
    var alice = getHandler.handleRequest(buildEvent("alice", null), null);
    assertThat(objectMapper.readTree(jordan.getBody()).get("track_orders_after").asLong())
        .isEqualTo(1723363200);
    assertThat(objectMapper.readTree(alice.getBody()).get("track_orders_after").isNull()).isTrue();
  }

  @Test
  void updateOrderSettingsShouldRejectInvalidBodies() throws Exception {
    for (var body : new String[] {null, "", "{\"value\":\"secret\"", "[]"}) {
      var response = updateHandler.handleRequest(buildEvent("jordan", body), null);
      assertThat(response.getStatusCode()).isEqualTo(400);
      assertThat(response.getBody()).doesNotContain("secret");
    }
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
