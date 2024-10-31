package com.jordansimsmith.pricetracker;

import static org.assertj.core.api.Assertions.*;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.jordansimsmith.testcontainers.DynamoDbContainer;
import com.jordansimsmith.time.FakeClock;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

@Testcontainers
public class UpdatePricesHandlerIntegrationTest {
  private FakeClock fakeClock;
  private FakeChemistWarehouseClient fakeChemistWarehouseClient;
  private FakeProductsFactory fakeProductsFactory;
  private DynamoDbTable<PriceTrackerItem> priceTrackerTable;

  private UpdatePricesHandler updatePricesHandler;

  @Container DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeEach
  void setUp() {
    var factory = PriceTrackerTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    fakeChemistWarehouseClient = factory.fakeChemistWarehouseClient();
    fakeProductsFactory = factory.fakeProductsFactory();

    var dynamoDbClient = factory.dynamoDbClient();
    priceTrackerTable = factory.priceTrackerTable();
    priceTrackerTable.createTable();
    try (var waiter = DynamoDbWaiter.builder().client(dynamoDbClient).build()) {
      var res =
          waiter
              .waitUntilTableExists(b -> b.tableName(priceTrackerTable.tableName()).build())
              .matched();
      res.response().orElseThrow();
    }

    updatePricesHandler = new UpdatePricesHandler(factory);
  }

  @Test
  void handleRequestShouldUpdatePrices() {
    // arrange
    var product1 = new ProductsFactory.Product(URI.create("product1.com"), "product 1");
    var product2 = new ProductsFactory.Product(URI.create("product2.com"), "product 2");
    fakeProductsFactory.addProducts(List.of(product1, product2));

    var product1Price = 22.50;
    var product2Price = 18.00;
    fakeChemistWarehouseClient.setPrice(product1.url(), product1Price);
    fakeChemistWarehouseClient.setPrice(product2.url(), product2Price);

    var product1History1 =
        PriceTrackerItem.create(product1.url().toString(), product1.name(), 1_000, 21.30);
    var product1History2 =
        PriceTrackerItem.create(product1.url().toString(), product1.name(), 2_000, 25.80);
    priceTrackerTable.putItem(product1History1);
    priceTrackerTable.putItem(product1History2);

    fakeClock.setTime(3_000_000);

    // act
    updatePricesHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    var product1New =
        priceTrackerTable.getItem(
            Key.builder()
                .partitionValue(PriceTrackerItem.formatPk(product1.url().toString()))
                .sortValue(PriceTrackerItem.formatSk(fakeClock.now().getEpochSecond()))
                .build());
    assertThat(product1New).isNotNull();
    assertThat(product1New.getName()).isEqualTo(product1.name());
    assertThat(product1New.getUrl()).isEqualTo(product1.url().toString());
    assertThat(product1New.getPrice()).isEqualTo(product1Price);
    assertThat(product1New.getTimestamp()).isEqualTo(fakeClock.now().getEpochSecond());

    var product2New =
        priceTrackerTable.getItem(
            Key.builder()
                .partitionValue(PriceTrackerItem.formatPk(product2.url().toString()))
                .sortValue(PriceTrackerItem.formatSk(fakeClock.now().getEpochSecond()))
                .build());
    assertThat(product2New).isNotNull();
    assertThat(product2New.getName()).isEqualTo(product2.name());
    assertThat(product2New.getUrl()).isEqualTo(product2.url().toString());
    assertThat(product2New.getPrice()).isEqualTo(product2Price);
    assertThat(product2New.getTimestamp()).isEqualTo(fakeClock.now().getEpochSecond());

    // TODO(assert notification dispatched for product 1)
  }
}
