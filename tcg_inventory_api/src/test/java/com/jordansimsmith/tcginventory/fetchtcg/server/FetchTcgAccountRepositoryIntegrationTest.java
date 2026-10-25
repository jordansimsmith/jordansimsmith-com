package com.jordansimsmith.tcginventory.fetchtcg.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetAccountRequest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
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
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@Testcontainers
public class FetchTcgAccountRepositoryIntegrationTest {
  private static final String TABLE_NAME = "tcg_inventory";

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbEnhancedClient dynamoDbEnhancedClient;
  private static DynamoDbTable<FetchTcgAccountItem> accountTable;
  private static FetchTcgAccountRepository accountRepository;

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
    accountRepository = new FetchTcgAccountRepository(accountTable);
  }

  @AfterAll
  static void tearDownAfterClass() {
    dynamoDbClient.close();
  }

  @BeforeEach
  void setUp() {
    DynamoDbUtils.reset(dynamoDbClient);
  }

  @Test
  void getShouldReturnNullWhenOnlyLegacySettingsRecordExists() {
    // arrange
    dynamoDbClient.putItem(
        PutItemRequest.builder()
            .tableName(TABLE_NAME)
            .item(
                Map.of(
                    FetchTcgAccountItem.PK,
                    AttributeValue.fromS(FetchTcgAccountItem.formatPk("jordan")),
                    FetchTcgAccountItem.SK,
                    AttributeValue.fromS("SETTINGS"),
                    "updated_at",
                    AttributeValue.fromN("1700000000")))
            .build());

    // act
    var account = accountRepository.get("jordan");
    var service = new GetAccountService(accountRepository);

    // assert
    assertThat(account).isNull();
    assertThatThrownBy(
            () -> service.getAccount(new RequestContext("jordan"), new GetAccountRequest()))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("FetchTCG account is not configured");
  }

  @Test
  void getAccountServiceShouldReturnStoredMetadataWithoutChangingIt() throws ServiceException {
    // arrange
    var updatedAt = Instant.ofEpochSecond(1_800_000_000, 123_456_789);
    var savedAccount = FetchTcgAccountItem.create("jordan", "  Jordan Seller  ", updatedAt);
    accountRepository.update(savedAccount);
    var service = new GetAccountService(accountRepository);

    // act
    var response = service.getAccount(new RequestContext("jordan"), new GetAccountRequest());

    // assert
    assertThat(response.sellerUsername()).isEqualTo("  Jordan Seller  ");
    assertThat(response.credentialsUpdatedAt()).isEqualTo(1_800_000_000L);
    var storedAccount = accountRepository.get("jordan");
    assertThat(storedAccount.getSellerUsername()).isEqualTo("  Jordan Seller  ");
    assertThat(storedAccount.getCredentialsUpdatedAt())
        .isEqualTo(Instant.ofEpochSecond(1_800_000_000));
  }

  @Test
  void getAccountServiceShouldSelectMetadataByContextUser() throws ServiceException {
    // arrange
    accountRepository.update(
        FetchTcgAccountItem.create("alice", "Alice Seller", Instant.ofEpochSecond(1_700_000_000)));
    accountRepository.update(
        FetchTcgAccountItem.create("bob", "Bob Seller", Instant.ofEpochSecond(1_800_000_000)));
    var service = new GetAccountService(accountRepository);

    // act
    var alice = service.getAccount(new RequestContext("alice"), new GetAccountRequest());
    var bob = service.getAccount(new RequestContext("bob"), new GetAccountRequest());

    // assert
    assertThat(alice.sellerUsername()).isEqualTo("Alice Seller");
    assertThat(bob.sellerUsername()).isEqualTo("Bob Seller");
    assertThat(alice.credentialsUpdatedAt()).isEqualTo(1_700_000_000L);
    assertThat(bob.credentialsUpdatedAt()).isEqualTo(1_800_000_000L);
  }

  @Test
  void getAccountServiceShouldReturnNotFoundWhenAccountItemIsAbsent() {
    // arrange
    var service = new GetAccountService(accountRepository);

    // act & assert
    assertThatThrownBy(
            () -> service.getAccount(new RequestContext("jordan"), new GetAccountRequest()))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("FetchTCG account is not configured");
  }

  @Test
  void getAccountServiceShouldFailWhenStoredMetadataIsInvalid() {
    for (var invalidAccount :
        new FetchTcgAccountItem[] {
          FetchTcgAccountItem.create("jordan", null, Instant.ofEpochSecond(1_700_000_000)),
          FetchTcgAccountItem.create("jordan", "", Instant.ofEpochSecond(1_700_000_000)),
          FetchTcgAccountItem.create("jordan", " \t\n ", Instant.ofEpochSecond(1_700_000_000)),
          FetchTcgAccountItem.create("jordan", "Jordan Seller", null)
        }) {
      // arrange
      accountRepository.update(invalidAccount);
      var service = new GetAccountService(accountRepository);

      // act & assert
      assertThatThrownBy(
              () -> service.getAccount(new RequestContext("jordan"), new GetAccountRequest()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("FetchTCG account metadata is incomplete");
    }
  }

  @Test
  void updateShouldPersistMetadataWithExpectedKeysAndEpochSeconds() {
    // arrange
    var updatedAt = Instant.ofEpochSecond(1700000000);
    var account = FetchTcgAccountItem.create("jordan", "Jordan Seller", updatedAt);

    // act
    accountRepository.update(account);
    var storedAccount = accountRepository.get("jordan");
    var rawItem =
        dynamoDbClient
            .getItem(
                GetItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .key(key("jordan"))
                    .consistentRead(true)
                    .build())
            .item();

    // assert
    assertThat(storedAccount).isNotNull();
    assertThat(storedAccount.getPk()).isEqualTo("USER#jordan");
    assertThat(storedAccount.getSk()).isEqualTo("FETCHTCG_ACCOUNT");
    assertThat(storedAccount.getUser()).isEqualTo("jordan");
    assertThat(storedAccount.getSellerUsername()).isEqualTo("Jordan Seller");
    assertThat(storedAccount.getCredentialsUpdatedAt()).isEqualTo(updatedAt);
    assertThat(rawItem.keySet())
        .containsExactlyInAnyOrder("pk", "sk", "user", "seller_username", "credentials_updated_at");
    assertThat(rawItem.get(FetchTcgAccountItem.CREDENTIALS_UPDATED_AT).n()).isEqualTo("1700000000");
  }

  @Test
  void updateShouldReplaceMetadataAndRepeatTheSameWrite() {
    // arrange
    var firstAccount =
        FetchTcgAccountItem.create("jordan", "Old Seller", Instant.ofEpochSecond(1700000000));
    var replacement =
        FetchTcgAccountItem.create("jordan", "New Seller", Instant.ofEpochSecond(1800000000));
    accountRepository.update(firstAccount);

    // act
    accountRepository.update(replacement);
    accountRepository.update(replacement);
    var storedAccount = accountRepository.get("jordan");

    // assert
    assertThat(storedAccount).isNotNull();
    assertThat(storedAccount.getSellerUsername()).isEqualTo("New Seller");
    assertThat(storedAccount.getCredentialsUpdatedAt())
        .isEqualTo(Instant.ofEpochSecond(1800000000));
  }

  @Test
  void accountOperationsShouldIsolateUsersAndPreserveUnrelatedItems() {
    // arrange
    dynamoDbClient.putItem(
        PutItemRequest.builder()
            .tableName(TABLE_NAME)
            .item(
                Map.of(
                    "pk",
                    AttributeValue.fromS("USER#jordan"),
                    "sk",
                    AttributeValue.fromS("SETTINGS"),
                    "updated_at",
                    AttributeValue.fromN("1700000000")))
            .build());

    // act
    accountRepository.update(
        FetchTcgAccountItem.create("jordan", "Jordan Seller", Instant.ofEpochSecond(1700000000)));
    accountRepository.update(
        FetchTcgAccountItem.create("alice", "Alice Seller", Instant.ofEpochSecond(1800000000)));
    var jordanAccount = accountRepository.get("jordan");
    var aliceAccount = accountRepository.get("alice");
    var settingsItem =
        dynamoDbClient
            .getItem(
                GetItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .key(
                        Map.of(
                            "pk", AttributeValue.fromS("USER#jordan"),
                            "sk", AttributeValue.fromS("SETTINGS")))
                    .consistentRead(true)
                    .build())
            .item();

    // assert
    assertThat(jordanAccount).isNotNull();
    assertThat(jordanAccount.getSellerUsername()).isEqualTo("Jordan Seller");
    assertThat(aliceAccount).isNotNull();
    assertThat(aliceAccount.getSellerUsername()).isEqualTo("Alice Seller");
    assertThat(settingsItem.get("updated_at").n()).isEqualTo("1700000000");
  }

  private Map<String, AttributeValue> key(String user) {
    return Map.of(
        FetchTcgAccountItem.PK,
        AttributeValue.fromS(FetchTcgAccountItem.formatPk(user)),
        FetchTcgAccountItem.SK,
        AttributeValue.fromS(FetchTcgAccountItem.formatSk()));
  }
}
