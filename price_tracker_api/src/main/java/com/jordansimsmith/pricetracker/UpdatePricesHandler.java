package com.jordansimsmith.pricetracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.time.Clock;
import java.util.ArrayList;
import java.util.Objects;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class UpdatePricesHandler implements RequestHandler<ScheduledEvent, Void> {
  private final Clock clock;
  private final ChemistWarehouseClient chemistWarehouseClient;
  private final ProductsFactory productsFactory;
  private final DynamoDbTable<PriceTrackerItem> priceTrackerTable;

  public UpdatePricesHandler() {
    this(PriceTrackerFactory.create());
  }

  @VisibleForTesting
  UpdatePricesHandler(PriceTrackerFactory factory) {
    this.clock = factory.clock();
    this.chemistWarehouseClient = factory.chemistWarehouseClient();
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
    for (var product : productsFactory.findChemistWarehouseProducts()) {
      var price = chemistWarehouseClient.getPrice(product.url());
      var priceTrackerItem =
          PriceTrackerItem.create(
              product.url().toString(), product.name(), now.getEpochSecond(), price);
      prices.add(priceTrackerItem);
    }

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
        // TODO: dispatch notification
      }
    }

    for (var price : prices) {
      priceTrackerTable.putItem(price);
    }

    return null;
  }
}
