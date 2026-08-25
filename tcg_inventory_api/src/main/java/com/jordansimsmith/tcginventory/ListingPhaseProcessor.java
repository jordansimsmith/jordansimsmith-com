package com.jordansimsmith.tcginventory;

import com.jordansimsmith.time.Clock;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class ListingPhaseProcessor {
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final DynamoDbClient dynamoDbClient;
  private final Clock clock;
  private final FetchTcgClient fetchTcgClient;

  public ListingPhaseProcessor(
      DynamoDbTable<TcgInventoryItem> tcgInventoryTable,
      DynamoDbClient dynamoDbClient,
      Clock clock,
      FetchTcgClient fetchTcgClient) {
    this.tcgInventoryTable = tcgInventoryTable;
    this.dynamoDbClient = dynamoDbClient;
    this.clock = clock;
    this.fetchTcgClient = fetchTcgClient;
  }

  public BatchResult process(String user, String bearerToken) {
    var dirtySkus = loadDirtySkus(user);
    int processed = 0;

    for (var sku : dirtySkus) {
      var skuId = sku.getSkuId();
      var capturedVersion = sku.getVersion();
      var inStockCount = countInStockUnits(user, skuId);

      if (inStockCount > 0) {
        publishSku(user, bearerToken, sku, inStockCount, capturedVersion);
      } else if (sku.getFetchtcgListingId() != null) {
        delistSku(user, bearerToken, sku, capturedVersion);
      } else {
        clearDirty(user, skuId, capturedVersion);
      }
      processed++;
    }

    return new BatchResult(processed, true);
  }

  private List<TcgInventoryItem> loadDirtySkus(String user) {
    var results = new ArrayList<TcgInventoryItem>();
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(TcgInventoryItem.formatGsi1pk(user))
                        .sortValue(TcgInventoryItem.SKU_PREFIX)
                        .build()))
            .build();

    tcgInventoryTable.index(TcgInventoryItem.GSI1_NAME).query(request).stream()
        .flatMap(page -> page.items().stream())
        .forEach(results::add);
    return results;
  }

  private int countInStockUnits(String user, String skuId) {
    int count = 0;
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(TcgInventoryItem.formatSkuPk(user, skuId))
                        .sortValue(TcgInventoryItem.UNIT_PREFIX)
                        .build()))
            .build();

    for (var item : tcgInventoryTable.query(request).items()) {
      if ("in_stock".equals(item.getStatus())) {
        count++;
      }
    }
    return count;
  }

  private void publishSku(
      String user,
      String bearerToken,
      TcgInventoryItem sku,
      int inStockCount,
      int capturedVersion) {
    var condition = Condition.valueOf(sku.getCondition()).toFetchtcg();
    var price = new BigDecimal(sku.getSuggestedPrice());

    var upsertRequest =
        new FetchTcgClient.UpsertListingRequest(
            sku.getFetchtcgCardId(), condition, inStockCount, price, null, null);

    var response = fetchTcgClient.upsertListing(bearerToken, upsertRequest);

    clearDirtyWithSnapshot(
        user,
        sku.getSkuId(),
        capturedVersion,
        response.listingId(),
        inStockCount,
        price.toPlainString());
  }

  private void delistSku(
      String user, String bearerToken, TcgInventoryItem sku, int capturedVersion) {
    fetchTcgClient.deleteListing(bearerToken, sku.getFetchtcgListingId());
    clearDirtyRemoveSnapshot(user, sku.getSkuId(), capturedVersion);
  }

  private void clearDirtyWithSnapshot(
      String user, String skuId, int capturedVersion, int listingId, int quantity, String price) {
    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);
    var cleanGsi1pk = TcgInventoryItem.USER_PREFIX + user + "#CLEAN";

    try {
      dynamoDbClient.updateItem(
          UpdateItemRequest.builder()
              .tableName(TcgInventoryItem.TABLE_NAME)
              .key(
                  Map.of(
                      TcgInventoryItem.PK, AttributeValue.builder().s(skuPk).build(),
                      TcgInventoryItem.SK,
                          AttributeValue.builder().s(TcgInventoryItem.formatSkuSk()).build()))
              .updateExpression(
                  "SET "
                      + TcgInventoryItem.DIRTY
                      + " = :clean, "
                      + TcgInventoryItem.GSI1PK
                      + " = :gsi1pk, "
                      + TcgInventoryItem.FETCHTCG_LISTING_ID
                      + " = :listingId, "
                      + TcgInventoryItem.LAST_PUBLISHED_QUANTITY
                      + " = :qty, "
                      + TcgInventoryItem.LAST_PUBLISHED_PRICE
                      + " = :price, "
                      + TcgInventoryItem.LAST_PUBLISHED_AT
                      + " = :now")
              .conditionExpression(
                  TcgInventoryItem.DIRTY
                      + " = :dirty AND "
                      + TcgInventoryItem.VERSION
                      + " = :version")
              .expressionAttributeValues(
                  Map.ofEntries(
                      Map.entry(":clean", AttributeValue.builder().bool(false).build()),
                      Map.entry(":gsi1pk", AttributeValue.builder().s(cleanGsi1pk).build()),
                      Map.entry(
                          ":listingId",
                          AttributeValue.builder().n(String.valueOf(listingId)).build()),
                      Map.entry(
                          ":qty", AttributeValue.builder().n(String.valueOf(quantity)).build()),
                      Map.entry(":price", AttributeValue.builder().s(price).build()),
                      Map.entry(
                          ":now",
                          AttributeValue.builder()
                              .n(String.valueOf(clock.now().getEpochSecond()))
                              .build()),
                      Map.entry(":dirty", AttributeValue.builder().bool(true).build()),
                      Map.entry(
                          ":version",
                          AttributeValue.builder().n(String.valueOf(capturedVersion)).build())))
              .build());
    } catch (ConditionalCheckFailedException e) {
      // version mismatch — SKU stays dirty for the next run
    }
  }

  private void clearDirtyRemoveSnapshot(String user, String skuId, int capturedVersion) {
    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);
    var cleanGsi1pk = TcgInventoryItem.USER_PREFIX + user + "#CLEAN";

    try {
      dynamoDbClient.updateItem(
          UpdateItemRequest.builder()
              .tableName(TcgInventoryItem.TABLE_NAME)
              .key(
                  Map.of(
                      TcgInventoryItem.PK, AttributeValue.builder().s(skuPk).build(),
                      TcgInventoryItem.SK,
                          AttributeValue.builder().s(TcgInventoryItem.formatSkuSk()).build()))
              .updateExpression(
                  "SET "
                      + TcgInventoryItem.DIRTY
                      + " = :clean, "
                      + TcgInventoryItem.GSI1PK
                      + " = :gsi1pk REMOVE "
                      + TcgInventoryItem.FETCHTCG_LISTING_ID
                      + ", "
                      + TcgInventoryItem.LAST_PUBLISHED_QUANTITY
                      + ", "
                      + TcgInventoryItem.LAST_PUBLISHED_PRICE
                      + ", "
                      + TcgInventoryItem.LAST_PUBLISHED_AT)
              .conditionExpression(
                  TcgInventoryItem.DIRTY
                      + " = :dirty AND "
                      + TcgInventoryItem.VERSION
                      + " = :version")
              .expressionAttributeValues(
                  Map.of(
                      ":clean", AttributeValue.builder().bool(false).build(),
                      ":gsi1pk", AttributeValue.builder().s(cleanGsi1pk).build(),
                      ":dirty", AttributeValue.builder().bool(true).build(),
                      ":version",
                          AttributeValue.builder().n(String.valueOf(capturedVersion)).build()))
              .build());
    } catch (ConditionalCheckFailedException e) {
      // version mismatch — SKU stays dirty for the next run
    }
  }

  private void clearDirty(String user, String skuId, int capturedVersion) {
    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);
    var cleanGsi1pk = TcgInventoryItem.USER_PREFIX + user + "#CLEAN";

    try {
      dynamoDbClient.updateItem(
          UpdateItemRequest.builder()
              .tableName(TcgInventoryItem.TABLE_NAME)
              .key(
                  Map.of(
                      TcgInventoryItem.PK, AttributeValue.builder().s(skuPk).build(),
                      TcgInventoryItem.SK,
                          AttributeValue.builder().s(TcgInventoryItem.formatSkuSk()).build()))
              .updateExpression(
                  "SET "
                      + TcgInventoryItem.DIRTY
                      + " = :clean, "
                      + TcgInventoryItem.GSI1PK
                      + " = :gsi1pk")
              .conditionExpression(
                  TcgInventoryItem.DIRTY
                      + " = :dirty AND "
                      + TcgInventoryItem.VERSION
                      + " = :version")
              .expressionAttributeValues(
                  Map.of(
                      ":clean", AttributeValue.builder().bool(false).build(),
                      ":gsi1pk", AttributeValue.builder().s(cleanGsi1pk).build(),
                      ":dirty", AttributeValue.builder().bool(true).build(),
                      ":version",
                          AttributeValue.builder().n(String.valueOf(capturedVersion)).build()))
              .build());
    } catch (ConditionalCheckFailedException e) {
      // version mismatch — SKU stays dirty for the next run
    }
  }
}
