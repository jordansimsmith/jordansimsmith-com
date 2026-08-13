package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

@Testcontainers
public class TcgInventoryItemIntegrationTest {
  private DynamoDbTable<TcgInventoryItem> tcgInventoryTable;

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
    tcgInventoryTable = factory.tcgInventoryTable();
    DynamoDbUtils.reset(factory.dynamoDbClient());
  }

  @Test
  void shouldRoundTripSkuItem() {
    // arrange
    var user = "jordan";
    var skuId = "f0a51425-d796-48b8-b68c-bc21fb465c81#normal#NM";

    var item =
        TcgInventoryItem.createSku(
            user,
            skuId,
            "f0a51425-d796-48b8-b68c-bc21fb465c81",
            "normal",
            "NM",
            "Elvish Aberration",
            "a25",
            "Masters 25",
            "167",
            "mtg_167_c_a25_normal",
            null);
    item.setFetchtcgSetId(78);
    item.setVersion(7);
    item.setCreatedAt(Instant.ofEpochSecond(1700000000));
    item.setUpdatedAt(Instant.ofEpochSecond(1700000100));

    // act
    tcgInventoryTable.putItem(item);

    var retrieved =
        tcgInventoryTable.getItem(
            Key.builder().partitionValue(item.getPk()).sortValue(item.getSk()).build());

    // assert
    assertThat(retrieved).isEqualTo(item);
    assertThat(retrieved.getPk())
        .isEqualTo("USER#jordan#SKU#f0a51425-d796-48b8-b68c-bc21fb465c81#normal#NM");
    assertThat(retrieved.getSk()).isEqualTo("SKU");
    assertThat(retrieved.getGsi1pk()).isEqualTo("USER#jordan#DIRTY");
    assertThat(retrieved.getGsi1sk())
        .isEqualTo("SKU#f0a51425-d796-48b8-b68c-bc21fb465c81#normal#NM");
    assertThat(retrieved.getGsi2pk()).isEqualTo("USER#jordan#SKUS");
    assertThat(retrieved.getGsi2sk()).startsWith("NAME#elvish aberration#");
    assertThat(retrieved.getVersion()).isEqualTo(7);
    assertThat(retrieved.getDirty()).isTrue();
  }

  @Test
  void shouldRoundTripUnitItem() {
    // arrange
    var user = "jordan";
    var skuId = "f0a51425-d796-48b8-b68c-bc21fb465c81#normal#NM";

    var item =
        TcgInventoryItem.createUnit(
            user,
            skuId,
            4242,
            "in_stock",
            "01JEXAMPLEULID0000000000",
            Instant.ofEpochSecond(1700000000));

    // act
    tcgInventoryTable.putItem(item);

    var retrieved =
        tcgInventoryTable.getItem(
            Key.builder().partitionValue(item.getPk()).sortValue(item.getSk()).build());

    // assert
    assertThat(retrieved).isEqualTo(item);
    assertThat(retrieved.getSk()).isEqualTo("UNIT#0000004242");
    assertThat(retrieved.getSequenceNumber()).isEqualTo(4242);
    assertThat(retrieved.getStatus()).isEqualTo("in_stock");
    assertThat(retrieved.getImportId()).isEqualTo("01JEXAMPLEULID0000000000");
  }

  @Test
  void shouldRoundTripItemWithNullGsiAttributes() {
    // arrange
    var user = "jordan";

    var item =
        TcgInventoryItem.createImport(
            user,
            "01JEXAMPLEULID0000000000",
            "manabox_export.csv",
            0,
            null,
            Instant.ofEpochSecond(1700000000));

    // act
    tcgInventoryTable.putItem(item);

    var retrieved =
        tcgInventoryTable.getItem(
            Key.builder().partitionValue(item.getPk()).sortValue(item.getSk()).build());

    // assert
    assertThat(retrieved).isEqualTo(item);
    assertThat(retrieved.getGsi1pk()).isNull();
    assertThat(retrieved.getGsi1sk()).isNull();
    assertThat(retrieved.getGsi2pk()).isNull();
    assertThat(retrieved.getGsi2sk()).isNull();
  }
}
