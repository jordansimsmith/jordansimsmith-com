package com.jordansimsmith.tcginventory.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.secrets.FakeSecrets;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.fetchtcg.server.FetchTcgAccountItem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

@Testcontainers
public class SettingsHandlerIntegrationTest {
  private static final String ALLOWED_ORIGIN = "https://tcg-inventory.jordansimsmith.com";
  private ObjectMapper objectMapper;
  private FakeSecrets fakeSecrets;
  private UpdateSettingsHandler updateSettingsHandler;
  private GetSettingsHandler getSettingsHandler;
  private DynamoDbTable<SettingsItem> settingsTable;
  private DynamoDbTable<JobItem> jobTable;
  private DynamoDbTable<FetchTcgAccountItem> accountTable;

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
    objectMapper = factory.objectMapper();
    fakeSecrets = factory.fakeSecrets();
    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeSecrets.set("tcg_inventory", "{\"jordan\":\"existing-token\"}");
    settingsTable = factory.settingsTable();
    jobTable = TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), JobItem.class);
    accountTable =
        TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), FetchTcgAccountItem.class);
    updateSettingsHandler = new UpdateSettingsHandler(factory);
    getSettingsHandler = new GetSettingsHandler(factory);
  }

  @Test
  void getSettingsShouldReturnOnlyNullableTrackOrdersAfter() throws Exception {
    var response = getSettingsHandler.handleRequest(buildEvent("jordan", null), null);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8")
        .containsEntry("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
    assertThat(objectMapper.readTree(response.getBody()))
        .isEqualTo(objectMapper.readTree("{\"track_orders_after\":null}"));
  }

  @Test
  void updateSettingsShouldStoreAndReturnTrackOrdersAfter() throws Exception {
    var response =
        updateSettingsHandler.handleRequest(
            buildEvent("jordan", "{\"track_orders_after\":1723363200}"), null);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8")
        .containsEntry("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
    assertThat(objectMapper.readTree(response.getBody()))
        .isEqualTo(objectMapper.readTree("{\"track_orders_after\":1723363200}"));
    assertThat(fakeSecrets.get("tcg_inventory")).isEqualTo("{\"jordan\":\"existing-token\"}");

    var getResponse = getSettingsHandler.handleRequest(buildEvent("jordan", null), null);
    assertThat(objectMapper.readTree(getResponse.getBody()))
        .isEqualTo(objectMapper.readTree("{\"track_orders_after\":1723363200}"));
  }

  @Test
  void updateSettingsShouldPreserveLegacyTimestampAndOtherUsers() {
    var originalTimestamp = Instant.ofEpochSecond(1700000000);
    settingsTable.putItem(SettingsItem.create("jordan", originalTimestamp));
    settingsTable.putItem(SettingsItem.create("alice", Instant.ofEpochSecond(1700000100)));
    accountTable.putItem(
        FetchTcgAccountItem.create("jordan", "verified-seller", originalTimestamp));

    updateSettingsHandler.handleRequest(
        buildEvent("jordan", "{\"track_orders_after\":1723363200}"), null);

    var jordanSettings = settingsTable.getItem(r -> r.key(key("jordan")));
    var aliceSettings = settingsTable.getItem(r -> r.key(key("alice")));
    assertThat(jordanSettings.getUpdatedAt()).isEqualTo(originalTimestamp);
    assertThat(jordanSettings.getTrackOrdersAfter()).isEqualTo(Instant.ofEpochSecond(1723363200));
    assertThat(aliceSettings.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1700000100));
    assertThat(accountTable.getItem(r -> r.key(accountKey("jordan"))).getSellerUsername())
        .isEqualTo("verified-seller");
  }

  @Test
  void updateSettingsShouldRejectUnknownFieldsWithoutWriting() throws Exception {
    settingsTable.putItem(SettingsItem.create("jordan", Instant.ofEpochSecond(1700000000)));
    accountTable.putItem(
        FetchTcgAccountItem.create("jordan", "verified-seller", Instant.ofEpochSecond(1700000000)));
    var originalSecret = fakeSecrets.get("tcg_inventory");
    var bodies =
        new String[] {
          "{\"unexpected\":\"submitted-secret\"}",
          "{\"unexpected\":\"submitted-secret\",\"track_orders_after\":1723363200}"
        };

    for (var body : bodies) {
      var response = updateSettingsHandler.handleRequest(buildEvent("jordan", body), null);
      assertThat(response.getStatusCode()).isEqualTo(400);
      assertThat(response.getBody()).doesNotContain("submitted-secret");
      assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
          .isEqualTo("Invalid request body");
      assertThat(fakeSecrets.get("tcg_inventory")).isEqualTo(originalSecret);
      assertThat(settingsTable.getItem(r -> r.key(key("jordan"))).getTrackOrdersAfter()).isNull();
      assertThat(accountTable.getItem(r -> r.key(accountKey("jordan"))).getSellerUsername())
          .isEqualTo("verified-seller");
    }
  }

  @Test
  void updateSettingsShouldReturnSafeBadRequestForInvalidBodiesAndMissingDates() throws Exception {
    var invalidBodies =
        new String[] {null, "", "{\"value\":\"submitted-secret\"", "[]", "\"value\"", "{}"};

    for (var body : invalidBodies) {
      var response = updateSettingsHandler.handleRequest(buildEvent("jordan", body), null);
      assertThat(response.getStatusCode()).isEqualTo(400);
      assertThat(response.getBody()).doesNotContain("submitted-secret");
      assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
          .isIn("Invalid request body", "track_orders_after is required");
    }

    var nullDateResponse =
        updateSettingsHandler.handleRequest(
            buildEvent("jordan", "{\"track_orders_after\":null}"), null);
    assertThat(nullDateResponse.getStatusCode()).isEqualTo(400);
    assertThat(nullDateResponse.getBody())
        .isEqualTo("{\"message\":\"track_orders_after is required\"}");
  }

  @Test
  void updateSettingsShouldPreserveActiveJobGuardAndAllowTerminalJobs() throws Exception {
    jobTable.putItem(JobItem.create("jordan", "queued-job", "publish", null, null, Instant.now()));
    var queuedResponse =
        updateSettingsHandler.handleRequest(
            buildEvent("jordan", "{\"track_orders_after\":1723363200}"), null);
    assertThat(queuedResponse.getStatusCode()).isEqualTo(409);

    var queuedJob = jobTable.getItem(r -> r.key(jobKey("queued-job")));
    queuedJob.setStatus("succeeded");
    jobTable.putItem(queuedJob);

    jobTable.putItem(JobItem.create("jordan", "running-job", "publish", null, null, Instant.now()));
    var runningResponse =
        updateSettingsHandler.handleRequest(
            buildEvent("jordan", "{\"track_orders_after\":1723363200}"), null);
    assertThat(runningResponse.getStatusCode()).isEqualTo(409);

    var runningJob = jobTable.getItem(r -> r.key(jobKey("running-job")));
    runningJob.setStatus("failed");
    jobTable.putItem(runningJob);
    var terminalResponse =
        updateSettingsHandler.handleRequest(
            buildEvent("jordan", "{\"track_orders_after\":1723363200}"), null);
    assertThat(terminalResponse.getStatusCode()).isEqualTo(200);
  }

  @Test
  void settingsShouldRemainIsolatedByUser() throws Exception {
    updateSettingsHandler.handleRequest(
        buildEvent("jordan", "{\"track_orders_after\":1723363200}"), null);

    var jordanResponse = getSettingsHandler.handleRequest(buildEvent("jordan", null), null);
    var aliceResponse = getSettingsHandler.handleRequest(buildEvent("alice", null), null);
    assertThat(objectMapper.readTree(jordanResponse.getBody()).get("track_orders_after").asLong())
        .isEqualTo(1723363200);
    assertThat(objectMapper.readTree(aliceResponse.getBody()).get("track_orders_after").isNull())
        .isTrue();
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

  private Key key(String user) {
    return Key.builder()
        .partitionValue(SettingsItem.formatPk(user))
        .sortValue(SettingsItem.formatSk())
        .build();
  }

  private Key accountKey(String user) {
    return Key.builder()
        .partitionValue(FetchTcgAccountItem.formatPk(user))
        .sortValue(FetchTcgAccountItem.formatSk())
        .build();
  }

  private Key jobKey(String jobId) {
    return Key.builder()
        .partitionValue(JobItem.formatPk("jordan"))
        .sortValue(JobItem.formatSk(jobId))
        .build();
  }
}
