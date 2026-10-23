package com.jordansimsmith.tcginventory.pricing;

import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class PricingRepository {
  private final DynamoDbTable<PricingItem> pricingTable;
  private final DynamoDbClient dynamoDbClient;

  public PricingRepository(TcgInventoryFactory factory) {
    this.pricingTable =
        TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), PricingItem.class);
    this.dynamoDbClient = factory.dynamoDbClient();
  }

  @Nullable
  public PricingItem get(String user) {
    var key =
        Key.builder()
            .partitionValue(PricingItem.formatPk(user))
            .sortValue(PricingItem.formatSk())
            .build();
    return pricingTable.getItem(request -> request.key(key).consistentRead(true));
  }

  public void update(PricingItem pricingItem) {
    var key =
        Map.of(
            PricingItem.PK, AttributeValue.fromS(pricingItem.getPk()),
            PricingItem.SK, AttributeValue.fromS(pricingItem.getSk()));
    var requestBuilder =
        UpdateItemRequest.builder()
            .tableName(TcgInventoryTable.TABLE_NAME)
            .key(key)
            .expressionAttributeNames(
                Map.of("#user", PricingItem.USER, "#price_floor", PricingItem.PRICE_FLOOR))
            .expressionAttributeValues(
                Map.of(":user", AttributeValue.fromS(pricingItem.getUser())));

    if (pricingItem.getPriceFloor() == null) {
      requestBuilder.updateExpression("SET #user = :user REMOVE #price_floor");
    } else {
      requestBuilder
          .updateExpression("SET #user = :user, #price_floor = :price_floor")
          .expressionAttributeValues(
              Map.of(
                  ":user",
                  AttributeValue.fromS(pricingItem.getUser()),
                  ":price_floor",
                  AttributeValue.fromS(pricingItem.getPriceFloor())));
    }

    dynamoDbClient.updateItem(requestBuilder.build());
  }
}
