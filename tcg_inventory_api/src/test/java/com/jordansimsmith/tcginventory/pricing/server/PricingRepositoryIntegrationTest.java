package com.jordansimsmith.tcginventory.pricing.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import java.util.Map;
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
class PricingRepositoryIntegrationTest {
  private static final String TABLE_NAME = "pricing_repository_test";
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbTable<PricingItem> pricingTable;
  private PricingRepository pricingRepository;

  @BeforeAll
  static void setUpBeforeClass() {
    dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(dynamoDbContainer.getEndpoint())
            .region(Region.AP_SOUTHEAST_2)
            .build();
    var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    pricingTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(PricingItem.class));
    DynamoDbUtils.createTable(dynamoDbClient, pricingTable);
  }

  @BeforeEach
  void setUp() {
    DynamoDbUtils.reset(dynamoDbClient);
    pricingRepository = new PricingRepository(pricingTable);
  }

  @Test
  void getShouldReturnNullForMissingRecord() {
    // act
    var item = pricingRepository.get("jordan");

    // assert
    assertThat(item).isNull();
  }

  @Test
  void updateShouldSaveFloorAndPreserveUnmappedAttributes() {
    // arrange
    dynamoDbClient.putItem(
        PutItemRequest.builder()
            .tableName(TABLE_NAME)
            .item(
                Map.of(
                    PricingItem.PK,
                    AttributeValue.fromS(PricingItem.formatPk("jordan")),
                    PricingItem.SK,
                    AttributeValue.fromS(PricingItem.formatSk()),
                    PricingItem.USER,
                    AttributeValue.fromS("jordan"),
                    "other_attribute",
                    AttributeValue.fromS("preserved")))
            .build());

    // act
    pricingRepository.update(PricingItem.create("jordan", "0.50"));

    // assert
    var item = pricingRepository.get("jordan");
    assertThat(item).isNotNull();
    assertThat(item.getPriceFloor()).isEqualTo("0.50");
    assertThat(
            dynamoDbClient
                .getItem(
                    GetItemRequest.builder()
                        .tableName(TABLE_NAME)
                        .key(
                            Map.of(
                                PricingItem.PK,
                                AttributeValue.fromS(PricingItem.formatPk("jordan")),
                                PricingItem.SK,
                                AttributeValue.fromS(PricingItem.formatSk())))
                        .consistentRead(true)
                        .build())
                .item())
        .containsEntry("other_attribute", AttributeValue.fromS("preserved"));
  }

  @Test
  void updateShouldKeepUsersIsolated() {
    // arrange
    pricingRepository.update(PricingItem.create("alice", "0.00"));
    pricingRepository.update(PricingItem.create("bob", "0.75"));

    // act
    var alice = pricingRepository.get("alice");
    var bob = pricingRepository.get("bob");

    // assert
    assertThat(alice.getPriceFloor()).isEqualTo("0.00");
    assertThat(bob.getPriceFloor()).isEqualTo("0.75");
  }
}
