package com.jordansimsmith.tcginventory.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@Testcontainers
public class PricingRepositoryIntegrationTest {
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static final URI UNUSED_S3_ENDPOINT = URI.create("http://localhost:1");

  private TcgInventoryFactory factory;
  private DynamoDbTable<PricingItem> pricingTable;
  private PricingRepository pricingRepository;

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), UNUSED_S3_ENDPOINT);
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.tableDefinition());
  }

  @BeforeEach
  void setUp() {
    factory = TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), UNUSED_S3_ENDPOINT);
    DynamoDbUtils.reset(factory.dynamoDbClient());
    pricingTable = TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), PricingItem.class);
    pricingRepository = new PricingRepository(factory);
  }

  @Test
  void getShouldReturnNullWhenRecordDoesNotExist() {
    // arrange

    // act
    var item = pricingRepository.get("jordan");

    // assert
    assertThat(item).isNull();
  }

  @Test
  void getShouldReturnItemWhenFloorIsAbsent() {
    // arrange
    pricingTable.putItem(PricingItem.create("jordan", null));

    // act
    var item = pricingRepository.get("jordan");

    // assert
    assertThat(item).isNotNull();
    assertThat(item.getUser()).isEqualTo("jordan");
    assertThat(item.getPriceFloor()).isNull();
  }

  @Test
  void updateShouldPersistTheProvidedItem() {
    // arrange
    var item = PricingItem.create("jordan", "0.50");

    // act
    pricingRepository.update(item);

    // assert
    var storedItem = pricingRepository.get("jordan");
    assertThat(storedItem).isNotNull();
    assertThat(storedItem.getUser()).isEqualTo("jordan");
    assertThat(storedItem.getPriceFloor()).isEqualTo("0.50");
  }

  @Test
  void updateShouldPersistZeroAsProvided() {
    // arrange
    var item = PricingItem.create("jordan", "0.00");

    // act
    pricingRepository.update(item);

    // assert
    var storedItem = pricingRepository.get("jordan");
    assertThat(storedItem).isNotNull();
    assertThat(storedItem.getPriceFloor()).isEqualTo("0.00");
  }

  @Test
  void updateShouldRemoveTheProvidedNullFloor() {
    // arrange
    pricingRepository.update(PricingItem.create("jordan", "1.00"));

    // act
    pricingRepository.update(PricingItem.create("jordan", null));

    // assert
    var storedItem = pricingRepository.get("jordan");
    assertThat(storedItem).isNotNull();
    assertThat(storedItem.getUser()).isEqualTo("jordan");
    assertThat(storedItem.getPriceFloor()).isNull();
  }

  @Test
  void updateShouldBeIsolatedByUserAndPreserveOtherAttributes() {
    // arrange
    var jordanKey =
        Map.of(
            PricingItem.PK, AttributeValue.fromS(PricingItem.formatPk("jordan")),
            PricingItem.SK, AttributeValue.fromS(PricingItem.formatSk()));
    factory
        .dynamoDbClient()
        .putItem(
            PutItemRequest.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
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
    pricingRepository.update(PricingItem.create("alice", "0.75"));
    pricingRepository.update(PricingItem.create("jordan", "0.50"));

    // assert
    var storedJordanItem = pricingRepository.get("jordan");
    var storedAliceItem = pricingRepository.get("alice");
    assertThat(storedJordanItem).isNotNull();
    assertThat(storedAliceItem).isNotNull();
    assertThat(storedJordanItem.getPriceFloor()).isEqualTo("0.50");
    assertThat(storedAliceItem.getPriceFloor()).isEqualTo("0.75");
    var jordanItem =
        factory
            .dynamoDbClient()
            .getItem(
                GetItemRequest.builder()
                    .tableName(TcgInventoryTable.TABLE_NAME)
                    .key(jordanKey)
                    .consistentRead(true)
                    .build())
            .item();
    assertThat(jordanItem.get("other_attribute").s()).isEqualTo("preserved");
  }
}
