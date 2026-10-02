package com.jordansimsmith.pricetracker;

import com.jordansimsmith.time.Clock;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

public class UpdateProductJobProcessor {
  private final Clock clock;
  private final PriceClient priceClient;
  private final ProductsFactory productsFactory;
  private final DynamoDbTable<PriceTrackerItem> priceTrackerTable;

  public UpdateProductJobProcessor(
      Clock clock,
      PriceClient priceClient,
      ProductsFactory productsFactory,
      DynamoDbTable<PriceTrackerItem> priceTrackerTable) {
    this.clock = clock;
    this.priceClient = priceClient;
    this.productsFactory = productsFactory;
    this.priceTrackerTable = priceTrackerTable;
  }

  public void process(String productId) {
    var product = productsFactory.getProduct(productId);
    var price = priceClient.getPrice(product.url());
    if (price == null) {
      return;
    }

    priceTrackerTable.putItem(
        PriceTrackerItem.create(product.url().toString(), product.name(), clock.now(), price));
  }
}
