package com.jordansimsmith.pricetracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.time.FakeClock;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

@Testcontainers
public class UpdateProductJobProcessorIntegrationTest {
  private FakeClock fakeClock;
  private FakePriceClient fakePriceClient;
  private FakeProductsFactory fakeProductsFactory;
  private DynamoDbTable<PriceTrackerItem> priceTrackerTable;
  private UpdateProductJobProcessor processor;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory = PriceTrackerTestFactory.create(dynamoDbContainer.getEndpoint());
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.priceTrackerTable());
  }

  @BeforeEach
  void setUp() {
    var factory = PriceTrackerTestFactory.create(dynamoDbContainer.getEndpoint());
    fakeClock = factory.fakeClock();
    fakePriceClient = factory.fakePriceClient();
    fakeProductsFactory = factory.fakeProductsFactory();
    priceTrackerTable = factory.priceTrackerTable();
    DynamoDbUtils.reset(factory.dynamoDbClient());
    processor =
        new UpdateProductJobProcessor(
            fakeClock, fakePriceClient, fakeProductsFactory, priceTrackerTable);
  }

  @Test
  void processShouldWriteOneSnapshotForTheRequestedProduct() {
    // arrange
    var product =
        new ProductsFactory.Product(
            "product-one", URI.create("https://example.com/one"), "product one");
    fakeProductsFactory.addChemistWarehouseProducts(List.of(product));
    fakePriceClient.setPrice(product.url(), 22.5);
    fakeClock.setTime(Instant.ofEpochSecond(3_000));

    // act
    processor.process(product.id());

    // assert
    var item =
        priceTrackerTable.getItem(
            Key.builder()
                .partitionValue(PriceTrackerItem.formatPk(product.url().toString()))
                .sortValue(PriceTrackerItem.formatSk(fakeClock.now()))
                .build());
    assertThat(item).isNotNull();
    assertThat(item.getName()).isEqualTo(product.name());
    assertThat(item.getUrl()).isEqualTo(product.url().toString());
    assertThat(item.getPrice()).isEqualTo(22.5);
  }

  @Test
  void processShouldSkipSnapshotWhenPriceIsUnavailable() {
    // arrange
    var product =
        new ProductsFactory.Product(
            "product-one", URI.create("https://example.com/one"), "product one");
    fakeProductsFactory.addChemistWarehouseProducts(List.of(product));

    // act
    processor.process(product.id());

    // assert
    var item =
        priceTrackerTable.getItem(
            Key.builder()
                .partitionValue(PriceTrackerItem.formatPk(product.url().toString()))
                .sortValue(PriceTrackerItem.formatSk(fakeClock.now()))
                .build());
    assertThat(item).isNull();
  }

  @Test
  void processShouldFailWhenProductIdIsUnknown() {
    // arrange

    // act / assert
    assertThatThrownBy(() -> processor.process("missing-product"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unknown product ID: missing-product");
  }
}
