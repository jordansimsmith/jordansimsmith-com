package com.jordansimsmith.pricetracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.notifications.FakeNotificationPublisher;
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
public class SendDigestJobProcessorIntegrationTest {
  private FakeClock fakeClock;
  private FakeNotificationPublisher fakeNotificationPublisher;
  private FakeProductsFactory fakeProductsFactory;
  private DynamoDbTable<PriceTrackerItem> priceTrackerTable;
  private DynamoDbTable<DigestCheckpointItem> digestCheckpointTable;
  private SendDigestJobProcessor processor;

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
    fakeNotificationPublisher = factory.fakeNotificationPublisher();
    fakeProductsFactory = factory.fakeProductsFactory();
    priceTrackerTable = factory.priceTrackerTable();
    digestCheckpointTable = factory.digestCheckpointTable();
    DynamoDbUtils.reset(factory.dynamoDbClient());
    processor =
        new SendDigestJobProcessor(
            fakeClock,
            fakeNotificationPublisher,
            fakeProductsFactory,
            priceTrackerTable,
            digestCheckpointTable);
  }

  @Test
  void processShouldPublishOneNetDecreasePerProduct() {
    // arrange
    var decreased =
        new ProductsFactory.Product(
            "decreased", URI.create("https://example.com/decreased"), "decreased product");
    var increased =
        new ProductsFactory.Product(
            "increased", URI.create("https://example.com/increased"), "increased product");
    var recovered =
        new ProductsFactory.Product(
            "recovered", URI.create("https://example.com/recovered"), "recovered product");
    var firstSeen =
        new ProductsFactory.Product(
            "first-seen", URI.create("https://example.com/first-seen"), "first-seen product");
    fakeProductsFactory.addChemistWarehouseProducts(
        List.of(decreased, increased, recovered, firstSeen));
    putPrice(decreased, 1_000, 100);
    putPrice(decreased, 2_000, 80);
    putPrice(decreased, 2_500, 70);
    putPrice(increased, 1_000, 50);
    putPrice(increased, 2_000, 60);
    putPrice(recovered, 1_000, 100);
    putPrice(recovered, 2_000, 80);
    putPrice(recovered, 2_500, 110);
    putPrice(firstSeen, 2_000, 5);
    digestCheckpointTable.putItem(DigestCheckpointItem.create(Instant.ofEpochSecond(1_500)));
    fakeClock.setTime(Instant.ofEpochSecond(3_001));

    // act
    processor.process(Instant.ofEpochSecond(3_000));

    // assert
    var notifications = fakeNotificationPublisher.findNotifications(SendDigestJobProcessor.TOPIC);
    assertThat(notifications).hasSize(1);
    assertThat(notifications.getFirst().subject()).isEqualTo("1 price decreased");
    assertThat(notifications.getFirst().message())
        .isEqualTo("decreased product $100.00 -> $70.00 https://example.com/decreased");
    assertThat(getCheckpoint().getProcessedThrough()).isEqualTo(Instant.ofEpochSecond(3_000));
  }

  @Test
  void processShouldAdvanceCheckpointWhenThereAreNoNetDecreases() {
    // arrange
    var product =
        new ProductsFactory.Product(
            "product", URI.create("https://example.com/product"), "product");
    fakeProductsFactory.addChemistWarehouseProducts(List.of(product));
    putPrice(product, 1_000, 100);
    putPrice(product, 2_000, 80);
    putPrice(product, 2_500, 110);
    digestCheckpointTable.putItem(DigestCheckpointItem.create(Instant.ofEpochSecond(1_500)));
    fakeClock.setTime(Instant.ofEpochSecond(3_001));

    // act
    processor.process(Instant.ofEpochSecond(3_000));

    // assert
    assertThat(fakeNotificationPublisher.findNotifications(SendDigestJobProcessor.TOPIC)).isEmpty();
    assertThat(getCheckpoint().getProcessedThrough()).isEqualTo(Instant.ofEpochSecond(3_000));
  }

  @Test
  void processShouldUseOneHourBeforeScheduledTimeForInitialBaseline() {
    // arrange
    var product =
        new ProductsFactory.Product(
            "product", URI.create("https://example.com/product"), "product");
    fakeProductsFactory.addChemistWarehouseProducts(List.of(product));
    putPrice(product, 1_000, 100);
    putPrice(product, 2_000, 80);
    fakeClock.setTime(Instant.ofEpochSecond(5_101));

    // act
    processor.process(Instant.ofEpochSecond(5_100));

    // assert
    var notifications = fakeNotificationPublisher.findNotifications(SendDigestJobProcessor.TOPIC);
    assertThat(notifications).hasSize(1);
    assertThat(notifications.getFirst().message())
        .isEqualTo("product $100.00 -> $80.00 https://example.com/product");
  }

  @Test
  void processShouldNotAdvanceCheckpointWhenNotificationFails() {
    // arrange
    var product =
        new ProductsFactory.Product(
            "product", URI.create("https://example.com/product"), "product");
    fakeProductsFactory.addChemistWarehouseProducts(List.of(product));
    putPrice(product, 1_000, 100);
    putPrice(product, 2_000, 80);
    digestCheckpointTable.putItem(DigestCheckpointItem.create(Instant.ofEpochSecond(1_500)));
    fakeClock.setTime(Instant.ofEpochSecond(3_001));
    var failure = new RuntimeException("SNS unavailable");
    fakeNotificationPublisher.failWith(failure);

    // act / assert
    assertThatThrownBy(() -> processor.process(Instant.ofEpochSecond(3_000))).isSameAs(failure);
    assertThat(getCheckpoint().getProcessedThrough()).isEqualTo(Instant.ofEpochSecond(1_500));
  }

  private void putPrice(ProductsFactory.Product product, long epochSecond, double price) {
    priceTrackerTable.putItem(
        PriceTrackerItem.create(
            product.url().toString(), product.name(), Instant.ofEpochSecond(epochSecond), price));
  }

  private DigestCheckpointItem getCheckpoint() {
    return digestCheckpointTable.getItem(
        Key.builder()
            .partitionValue(DigestCheckpointItem.PK_VALUE)
            .sortValue(DigestCheckpointItem.SK_VALUE)
            .build());
  }
}
