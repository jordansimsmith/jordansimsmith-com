package com.jordansimsmith.pricetracker;

import static org.assertj.core.api.Assertions.*;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.notifications.FakeNotificationPublisher;
import com.jordansimsmith.testcontainers.DynamoDbContainer;
import com.jordansimsmith.time.FakeClock;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

@Testcontainers
public class UpdatePricesHandlerIntegrationTest {
  private FakeClock fakeClock;
  private FakeNotificationPublisher fakeNotificationPublisher;
  private FakeChemistWarehouseClient fakeChemistWarehouseClient;
  private FakeProductsFactory fakeProductsFactory;
  private DynamoDbTable<PriceTrackerItem> priceTrackerTable;

  private UpdatePricesHandler updatePricesHandler;

  @Container DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeEach
  void setUp() {
    var factory = PriceTrackerTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    fakeNotificationPublisher = factory.fakeNotificationPublisher();
    fakeChemistWarehouseClient = factory.fakeChemistWarehouseClient();
    fakeProductsFactory = factory.fakeProductsFactory();
    priceTrackerTable = factory.priceTrackerTable();

    DynamoDbUtils.createTable(factory.dynamoDbClient(), priceTrackerTable);

    updatePricesHandler = new UpdatePricesHandler(factory);
  }

  @Test
  void handleRequestShouldUpdatePrices() {
    // arrange
    var product1 = new ProductsFactory.Product(URI.create("product1.com"), "product 1");
    var product2 = new ProductsFactory.Product(URI.create("product2.com"), "product 2");
    var product3 = new ProductsFactory.Product(URI.create("product3.com"), "product 3");
    fakeProductsFactory.addProducts(List.of(product1, product2, product3));

    var product1Price = 22.50;
    var product2Price = 18.00;
    var product3Price = 23.79;
    fakeChemistWarehouseClient.setPrice(product1.url(), product1Price);
    fakeChemistWarehouseClient.setPrice(product2.url(), product2Price);
    fakeChemistWarehouseClient.setPrice(product3.url(), product3Price);

    var product1History1 =
        PriceTrackerItem.create(
            product1.url().toString(), product1.name(), Instant.ofEpochSecond(1_000), 21.30);
    var product1History2 =
        PriceTrackerItem.create(
            product1.url().toString(), product1.name(), Instant.ofEpochSecond(2_000), 25.80);
    var product2History1 =
        PriceTrackerItem.create(
            product2.url().toString(),
            product2.name(),
            Instant.ofEpochSecond(2_000),
            product2Price);
    priceTrackerTable.putItem(product1History1);
    priceTrackerTable.putItem(product1History2);
    priceTrackerTable.putItem(product2History1);

    fakeClock.setTime(3_000_000);

    // act
    updatePricesHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    var product1New =
        priceTrackerTable.getItem(
            Key.builder()
                .partitionValue(PriceTrackerItem.formatPk(product1.url().toString()))
                .sortValue(PriceTrackerItem.formatSk(fakeClock.now()))
                .build());
    assertThat(product1New).isNotNull();
    assertThat(product1New.getName()).isEqualTo(product1.name());
    assertThat(product1New.getUrl()).isEqualTo(product1.url().toString());
    assertThat(product1New.getPrice()).isEqualTo(product1Price);
    assertThat(product1New.getTimestamp()).isEqualTo(fakeClock.now());

    var product2New =
        priceTrackerTable.getItem(
            Key.builder()
                .partitionValue(PriceTrackerItem.formatPk(product2.url().toString()))
                .sortValue(PriceTrackerItem.formatSk(fakeClock.now()))
                .build());
    assertThat(product2New).isNotNull();
    assertThat(product2New.getName()).isEqualTo(product2.name());
    assertThat(product2New.getUrl()).isEqualTo(product2.url().toString());
    assertThat(product2New.getPrice()).isEqualTo(product2Price);
    assertThat(product2New.getTimestamp()).isEqualTo(fakeClock.now());

    var product3New =
        priceTrackerTable.getItem(
            Key.builder()
                .partitionValue(PriceTrackerItem.formatPk(product3.url().toString()))
                .sortValue(PriceTrackerItem.formatSk(fakeClock.now()))
                .build());
    assertThat(product3New).isNotNull();
    assertThat(product3New.getName()).isEqualTo(product3.name());
    assertThat(product3New.getUrl()).isEqualTo(product3.url().toString());
    assertThat(product3New.getPrice()).isEqualTo(product3Price);
    assertThat(product3New.getTimestamp()).isEqualTo(fakeClock.now());

    var notifications = fakeNotificationPublisher.findNotifications(UpdatePricesHandler.TOPIC);
    assertThat(notifications.size()).isEqualTo(1);
    var notification = notifications.get(0);
    assertThat(notification.subject()).isEqualTo("1 price updated");
    assertThat(notification.message()).isEqualTo("product 1 $25.80 -> $22.50 product1.com");
  }
}
