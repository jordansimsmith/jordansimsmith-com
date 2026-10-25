package com.jordansimsmith.tcginventory.fetchtcg.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.secrets.FakeSecrets;
import com.jordansimsmith.secrets.Secrets;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetAccountRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateCredentialsRequest;
import com.jordansimsmith.time.FakeClock;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@Testcontainers
public class UpdateCredentialsServiceIntegrationTest {
  private static final String TABLE_NAME = "tcg_inventory";
  private static final String CANDIDATE_TOKEN = "candidate +/?&=";

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbEnhancedClient dynamoDbEnhancedClient;
  private static DynamoDbTable<FetchTcgAccountItem> accountTable;
  private URI baseUri;
  private HttpServer httpServer;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AtomicInteger firebaseRequestCount = new AtomicInteger();
  private final AtomicInteger profileRequestCount = new AtomicInteger();
  private final AtomicReference<String> firebaseResponse =
      new AtomicReference<>("{\"id_token\":\"bearer\",\"refresh_token\":\"rotated-token\"}");
  private final AtomicReference<String> profileResponse =
      new AtomicReference<>("{\"username\":\"Verified Seller\"}");
  private final AtomicInteger firebaseStatus = new AtomicInteger(200);
  private final AtomicInteger profileStatus = new AtomicInteger(200);

  private FakeSecrets secrets;
  private FakeClock clock;
  private FetchTcgAccountRepository accountRepository;
  private String firebaseRequestBody;
  private String profileAuthorization;

  @BeforeAll
  static void setUpBeforeClass() {
    dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(dynamoDbContainer.getEndpoint())
            .region(Region.AP_SOUTHEAST_2)
            .build();
    dynamoDbEnhancedClient =
        DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    accountTable =
        dynamoDbEnhancedClient.table(TABLE_NAME, TableSchema.fromBean(FetchTcgAccountItem.class));
    DynamoDbUtils.createTable(dynamoDbClient, accountTable);
  }

  @AfterAll
  static void tearDownAfterClass() {
    dynamoDbClient.close();
  }

  @BeforeEach
  void setUp() throws IOException {
    DynamoDbUtils.reset(dynamoDbClient);
    secrets = new FakeSecrets();
    secrets.set("tcg_inventory", "{\"alice\":\"old-alice-token\",\"bob\":\"bob-token\"}");
    clock = new FakeClock();
    clock.setTime(Instant.ofEpochSecond(1_700_000_000, 123_000_000));
    accountRepository = new FetchTcgAccountRepository(accountTable);
    firebaseRequestCount.set(0);
    profileRequestCount.set(0);
    firebaseResponse.set("{\"id_token\":\"bearer\",\"refresh_token\":\"rotated-token\"}");
    profileResponse.set("{\"username\":\"Verified Seller\"}");
    firebaseStatus.set(200);
    profileStatus.set(200);
    firebaseRequestBody = null;
    profileAuthorization = null;
    httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    baseUri = URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort());
    httpServer.createContext(
        "/token",
        exchange -> {
          firebaseRequestCount.incrementAndGet();
          firebaseRequestBody =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          respond(exchange, firebaseStatus.get(), firebaseResponse.get());
        });
    httpServer.createContext(
        "/v1/private/profile",
        exchange -> {
          profileRequestCount.incrementAndGet();
          profileAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
          respond(exchange, profileStatus.get(), profileResponse.get());
        });
    httpServer.start();
  }

  @AfterEach
  void tearDown() {
    httpServer.stop(0);
    Thread.interrupted();
  }

  @Test
  void updateCredentialsShouldVerifyCandidateAndPersistRotatedCredentialAndMetadata()
      throws Exception {
    // arrange
    accountRepository.update(
        FetchTcgAccountItem.create("alice", "Old Seller", Instant.ofEpochSecond(1_600_000_000)));
    secrets.set("tcg_inventory", "{\"alice\":\"different-saved-token\",\"bob\":\"bob-token\"}");
    var service = createService(accountRepository, secrets);

    // act
    var response =
        service.updateCredentials(
            new RequestContext("alice"), new UpdateCredentialsRequest(CANDIDATE_TOKEN));

    // assert
    assertThat(response.sellerUsername()).isEqualTo("Verified Seller");
    assertThat(response.credentialsUpdatedAt()).isEqualTo(1_700_000_000L);
    var savedSecret = objectMapper.readTree(secrets.get("tcg_inventory"));
    assertThat(savedSecret.path("alice").asText()).isEqualTo("rotated-token");
    assertThat(savedSecret.path("bob").asText()).isEqualTo("bob-token");
    assertThat(firebaseRequestBody).contains("refresh_token=candidate+%2B%2F%3F%26%3D");
    assertThat(profileAuthorization).isEqualTo("Bearer bearer");
    assertThat(firebaseRequestCount.get()).isEqualTo(1);
    assertThat(profileRequestCount.get()).isEqualTo(1);
    var savedAccount = accountRepository.get("alice");
    assertThat(savedAccount.getSellerUsername()).isEqualTo("Verified Seller");
    assertThat(savedAccount.getCredentialsUpdatedAt())
        .isEqualTo(Instant.ofEpochSecond(1_700_000_000));
    assertThat(
            new GetAccountService(accountRepository)
                .getAccount(new RequestContext("alice"), new GetAccountRequest())
                .sellerUsername())
        .isEqualTo("Verified Seller");
  }

  @Test
  void updateCredentialsShouldRetainCandidateWhenProviderDoesNotRotateIt() throws Exception {
    // arrange
    firebaseResponse.set("{\"id_token\":\"bearer\"}");
    var service = createService(accountRepository, secrets);

    // act
    service.updateCredentials(
        new RequestContext("alice"), new UpdateCredentialsRequest(CANDIDATE_TOKEN));

    // assert
    assertThat(objectMapper.readTree(secrets.get("tcg_inventory")).path("alice").asText())
        .isEqualTo(CANDIDATE_TOKEN);
  }

  @Test
  void updateCredentialsShouldUseContextUserAndPreserveUnrelatedRecords() throws Exception {
    // arrange
    accountRepository.update(
        FetchTcgAccountItem.create("bob", "Bob Seller", Instant.ofEpochSecond(1_600_000_000)));
    dynamoDbClient.putItem(
        PutItemRequest.builder()
            .tableName(TABLE_NAME)
            .item(
                Map.of(
                    FetchTcgAccountItem.PK,
                    AttributeValue.fromS(FetchTcgAccountItem.formatPk("alice")),
                    FetchTcgAccountItem.SK,
                    AttributeValue.fromS("SETTINGS"),
                    "track_orders_after",
                    AttributeValue.fromN("1600000000")))
            .build());
    var service = createService(accountRepository, secrets);

    // act
    service.updateCredentials(
        new RequestContext("alice"), new UpdateCredentialsRequest(CANDIDATE_TOKEN));

    // assert
    assertThat(accountRepository.get("alice").getSellerUsername()).isEqualTo("Verified Seller");
    assertThat(accountRepository.get("bob").getSellerUsername()).isEqualTo("Bob Seller");
    assertThat(objectMapper.readTree(secrets.get("tcg_inventory")).path("bob").asText())
        .isEqualTo("bob-token");
    var settings =
        dynamoDbClient
            .getItem(
                request ->
                    request
                        .tableName(TABLE_NAME)
                        .key(
                            Map.of(
                                FetchTcgAccountItem.PK,
                                AttributeValue.fromS(FetchTcgAccountItem.formatPk("alice")),
                                FetchTcgAccountItem.SK,
                                AttributeValue.fromS("SETTINGS"))))
            .item();
    assertThat(settings.get("track_orders_after").n()).isEqualTo("1600000000");
  }

  @Test
  void updateCredentialsShouldRejectBlankCandidateBeforeProviderCalls() {
    // arrange
    var service = createService(accountRepository, secrets);

    // act & assert
    assertThatThrownBy(
            () ->
                service.updateCredentials(
                    new RequestContext("alice"), new UpdateCredentialsRequest(" \t ")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("refresh_token is required");
    assertThat(firebaseRequestCount.get()).isZero();
    assertThat(profileRequestCount.get()).isZero();
  }

  @Test
  void updateCredentialsShouldLeaveSavedDataUnchangedWhenCandidateIsRejected() throws Exception {
    // arrange
    firebaseStatus.set(400);
    firebaseResponse.set("{\"error\":{\"message\":\"INVALID_REFRESH_TOKEN\"}}");
    accountRepository.update(
        FetchTcgAccountItem.create(
            "alice", "Existing Seller", Instant.ofEpochSecond(1_600_000_000)));
    var service = createService(accountRepository, secrets);

    // act & assert
    assertThatThrownBy(
            () ->
                service.updateCredentials(
                    new RequestContext("alice"), new UpdateCredentialsRequest(CANDIDATE_TOKEN)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("The refresh token was rejected. Check it and try again.");
    assertThat(accountRepository.get("alice").getSellerUsername()).isEqualTo("Existing Seller");
    assertThat(objectMapper.readTree(secrets.get("tcg_inventory")).path("alice").asText())
        .isEqualTo("old-alice-token");
    assertThat(profileRequestCount.get()).isZero();
  }

  @Test
  void updateCredentialsShouldLeaveSavedDataUnchangedWhenProfileIsInvalid() throws Exception {
    // arrange
    profileResponse.set("{\"username\":\" \"}");
    accountRepository.update(
        FetchTcgAccountItem.create(
            "alice", "Existing Seller", Instant.ofEpochSecond(1_600_000_000)));
    var service = createService(accountRepository, secrets);

    // act & assert
    assertThatThrownBy(
            () ->
                service.updateCredentials(
                    new RequestContext("alice"), new UpdateCredentialsRequest(CANDIDATE_TOKEN)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("FetchTCG profile did not include a username");
    assertThat(accountRepository.get("alice").getSellerUsername()).isEqualTo("Existing Seller");
    assertThat(objectMapper.readTree(secrets.get("tcg_inventory")).path("alice").asText())
        .isEqualTo("old-alice-token");
  }

  @Test
  void updateCredentialsShouldTranslateProfileAuthenticationFailure() throws Exception {
    // arrange
    profileStatus.set(403);
    accountRepository.update(
        FetchTcgAccountItem.create(
            "alice", "Existing Seller", Instant.ofEpochSecond(1_600_000_000)));
    var service = createService(accountRepository, secrets);

    // act & assert
    assertThatThrownBy(
            () ->
                service.updateCredentials(
                    new RequestContext("alice"), new UpdateCredentialsRequest(CANDIDATE_TOKEN)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("The refresh token was rejected. Check it and try again.");
    assertThat(accountRepository.get("alice").getSellerUsername()).isEqualTo("Existing Seller");
    assertThat(objectMapper.readTree(secrets.get("tcg_inventory")).path("alice").asText())
        .isEqualTo("old-alice-token");
  }

  @Test
  void updateCredentialsShouldNotWriteMetadataWhenSecretReadFails() {
    // arrange
    var failingSecrets = mock(Secrets.class);
    when(failingSecrets.get("tcg_inventory"))
        .thenThrow(new IllegalStateException("secret service failure " + CANDIDATE_TOKEN));
    var service = createService(accountRepository, failingSecrets);

    // act & assert
    assertThatThrownBy(
            () ->
                service.updateCredentials(
                    new RequestContext("alice"), new UpdateCredentialsRequest(CANDIDATE_TOKEN)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Failed to save FetchTCG credentials")
        .hasMessageNotContaining(CANDIDATE_TOKEN);
    assertThat(accountRepository.get("alice")).isNull();
  }

  @Test
  void updateCredentialsShouldNotWriteMetadataWhenSecretSaveFails() throws Exception {
    // arrange
    var failingSecrets = mock(Secrets.class);
    when(failingSecrets.get("tcg_inventory"))
        .thenReturn("{\"alice\":\"old-alice-token\",\"bob\":\"bob-token\"}");
    doThrow(new IllegalStateException("failure includes " + CANDIDATE_TOKEN))
        .when(failingSecrets)
        .put(eq("tcg_inventory"), anyString());
    var service = createService(accountRepository, failingSecrets);

    // act & assert
    assertThatThrownBy(
            () ->
                service.updateCredentials(
                    new RequestContext("alice"), new UpdateCredentialsRequest(CANDIDATE_TOKEN)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Failed to save FetchTCG credentials")
        .hasMessageNotContaining(CANDIDATE_TOKEN);
    assertThat(accountRepository.get("alice")).isNull();
  }

  @Test
  void updateCredentialsShouldNotOverwriteMalformedSecretJson() {
    // arrange
    secrets.set("tcg_inventory", "not-json");
    var service = createService(accountRepository, secrets);

    // act & assert
    assertThatThrownBy(
            () ->
                service.updateCredentials(
                    new RequestContext("alice"), new UpdateCredentialsRequest(CANDIDATE_TOKEN)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("FetchTCG credential storage is malformed");
    assertThat(secrets.get("tcg_inventory")).isEqualTo("not-json");
    assertThat(accountRepository.get("alice")).isNull();
  }

  @Test
  void updateCredentialsShouldReturnFailureAfterSecretWriteAndRecoverOnFreshSave()
      throws Exception {
    // arrange
    accountRepository.update(
        FetchTcgAccountItem.create(
            "alice", "Existing Seller", Instant.ofEpochSecond(1_600_000_000)));
    var missingTable =
        dynamoDbEnhancedClient.table(
            "missing_fetch_tcg_account_table", TableSchema.fromBean(FetchTcgAccountItem.class));
    var failingService = createService(new FetchTcgAccountRepository(missingTable), secrets);

    // act
    assertThatThrownBy(
            () ->
                failingService.updateCredentials(
                    new RequestContext("alice"), new UpdateCredentialsRequest(CANDIDATE_TOKEN)))
        .isInstanceOf(RuntimeException.class);

    // assert
    assertThat(objectMapper.readTree(secrets.get("tcg_inventory")).path("alice").asText())
        .isEqualTo("rotated-token");
    assertThat(accountRepository.get("alice").getSellerUsername()).isEqualTo("Existing Seller");

    // act
    clock.setTime(Instant.ofEpochSecond(1_700_000_010));
    var response =
        createService(accountRepository, secrets)
            .updateCredentials(
                new RequestContext("alice"), new UpdateCredentialsRequest(CANDIDATE_TOKEN));

    // assert
    assertThat(response.sellerUsername()).isEqualTo("Verified Seller");
    assertThat(accountRepository.get("alice").getCredentialsUpdatedAt())
        .isEqualTo(Instant.ofEpochSecond(1_700_000_010));
  }

  private UpdateCredentialsService createService(
      FetchTcgAccountRepository repository, Secrets credentialStore) {
    return new UpdateCredentialsService(
        repository,
        new HttpFetchTcgTokenExchange(
            baseUri.resolve("/token"), HttpClient.newHttpClient(), objectMapper),
        new HttpFetchTcgClient(baseUri, HttpClient.newHttpClient(), objectMapper, () -> {}),
        credentialStore,
        objectMapper,
        clock);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var responseBody = exchange.getResponseBody()) {
      responseBody.write(bytes);
    }
  }
}
