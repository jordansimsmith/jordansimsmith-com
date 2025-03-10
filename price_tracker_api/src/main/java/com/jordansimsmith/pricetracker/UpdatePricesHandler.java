package com.jordansimsmith.pricetracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.notifications.NotificationPublisher;
import com.jordansimsmith.time.Clock;
import java.util.ArrayList;
import java.util.Objects;
import java.util.StringJoiner;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class UpdatePricesHandler implements RequestHandler<ScheduledEvent, Void> {
  @VisibleForTesting static final String TOPIC = "price_tracker_api_price_updates";

  private final Clock clock;
  private final NotificationPublisher notificationPublisher;
  private final PriceClient priceClient;
  private final ProductsFactory productsFactory;
  private final DynamoDbTable<PriceTrackerItem> priceTrackerTable;

  private record PriceChange(String url, String name, double currentPrice, double previousPrice) {}

  public UpdatePricesHandler() {
    this(PriceTrackerFactory.create());
  }

  @VisibleForTesting
  UpdatePricesHandler(PriceTrackerFactory factory) {
    this.clock = factory.clock();
    this.notificationPublisher = factory.notificationPublisher();
    this.priceClient = factory.priceClient();
    this.productsFactory = factory.productsFactory();
    this.priceTrackerTable = factory.priceTrackerTable();
  }

  @Override
  public Void handleRequest(ScheduledEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private Void doHandleRequest(ScheduledEvent event, Context context) throws Exception {
    var now = clock.now();
    var prices = new ArrayList<PriceTrackerItem>();

    // Process all products from both sources using the unified PriceClient
    var allProducts = new ArrayList<ProductsFactory.Product>();
    allProducts.addAll(productsFactory.findChemistWarehouseProducts());
    allProducts.addAll(productsFactory.findNzProteinProducts());

    for (var product : allProducts) {
      var price = priceClient.getPrice(product.url());
      if (price == null) {
        continue;
      }
      var priceTrackerItem =
          PriceTrackerItem.create(product.url().toString(), product.name(), now, price);
      prices.add(priceTrackerItem);
    }

    var priceChanges = new ArrayList<PriceChange>();
    for (var price : prices) {
      var previousPrice =
          priceTrackerTable
              .query(
                  QueryEnhancedRequest.builder()
                      .queryConditional(
                          QueryConditional.keyEqualTo(
                              Key.builder()
                                  .partitionValue(PriceTrackerItem.formatPk(price.getUrl()))
                                  .build()))
                      .limit(1)
                      .scanIndexForward(false)
                      .build())
              .items()
              .stream()
              .findFirst()
              .orElse(null);

      if (previousPrice == null) {
        continue;
      }

      if (!Objects.equals(previousPrice.getPrice(), price.getPrice())) {
        priceChanges.add(
            new PriceChange(
                price.getUrl(), price.getName(), price.getPrice(), previousPrice.getPrice()));
      }
    }

    if (!priceChanges.isEmpty()) {
      var subject =
          priceChanges.size() == 1
              ? "1 price updated"
              : "%d prices updated".formatted(priceChanges.size());
      var message = new StringJoiner("\r\n\r\n");
      for (var priceChange : priceChanges) {
        var line =
            "%s $%.2f -> $%.2f %s"
                .formatted(
                    priceChange.name,
                    priceChange.previousPrice,
                    priceChange.currentPrice,
                    priceChange.url);
        message.add(line);
      }

      notificationPublisher.publish(TOPIC, subject, message.toString());
    }

    for (var price : prices) {
      priceTrackerTable.putItem(price);
    }

    return null;
  }
}
