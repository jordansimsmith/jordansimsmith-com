package com.jordansimsmith.tcginventory;

import com.jordansimsmith.time.Clock;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

public class ListingPhaseProcessor {
  static final int BATCH_SIZE = 100;

  private static final Logger LOGGER = LoggerFactory.getLogger(ListingPhaseProcessor.class);

  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final DynamoDbClient dynamoDbClient;
  private final Clock clock;
  private final FetchTcgClient fetchTcgClient;
  private final S3Client s3Client;

  public ListingPhaseProcessor(
      DynamoDbTable<TcgInventoryItem> tcgInventoryTable,
      DynamoDbClient dynamoDbClient,
      Clock clock,
      FetchTcgClient fetchTcgClient,
      S3Client s3Client) {
    this.tcgInventoryTable = tcgInventoryTable;
    this.dynamoDbClient = dynamoDbClient;
    this.clock = clock;
    this.fetchTcgClient = fetchTcgClient;
    this.s3Client = s3Client;
  }

  public BatchResult process(String user, String bearerToken, int continuation) {
    var dirtySkus = loadDirtySkus(user);
    int processed = 0;

    for (var sku : dirtySkus) {
      var skuId = sku.getSkuId();
      var capturedVersion = sku.getVersion();
      var inStock = loadInStock(user, skuId);

      if (inStock.count() > 0) {
        publishSku(user, bearerToken, sku, inStock, capturedVersion);
      } else if (sku.getFetchtcgListingId() != null) {
        delistSku(user, bearerToken, sku, capturedVersion);
      } else {
        clearDirty(user, skuId, capturedVersion);
      }
      processed++;
    }

    return new BatchResult(continuation + processed, dirtySkus.size() < BATCH_SIZE);
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
        .limit(BATCH_SIZE)
        .forEach(results::add);
    return results;
  }

  private InStock loadInStock(String user, String skuId) {
    int count = 0;
    TcgInventoryItem first = null;
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
        if (first == null) {
          first = item;
        }
        count++;
      }
    }
    return new InStock(count, first);
  }

  private void publishSku(
      String user, String bearerToken, TcgInventoryItem sku, InStock inStock, int capturedVersion) {
    var photos =
        inStock.first().getPhotos() == null
            ? new ArrayList<TcgInventoryItem.Photo>()
            : new ArrayList<>(inStock.first().getPhotos());
    var imageUrls = new ArrayList<String>();
    for (var photo : photos) {
      if (photo.getFetchtcgUrl() == null) {
        var bytes =
            s3Client
                .getObjectAsBytes(
                    GetObjectRequest.builder()
                        .bucket(Photos.BUCKET)
                        .key(Photos.key(user, photo.getPhotoId()))
                        .build())
                .asByteArray();
        photo.setFetchtcgUrl(
            fetchTcgClient.uploadListingImage(bearerToken, bytes, photo.getPhotoId() + ".jpg"));
        dynamoDbClient.updateItem(
            UpdateItemRequest.builder()
                .tableName(TcgInventoryItem.TABLE_NAME)
                .key(
                    Map.of(
                        TcgInventoryItem.PK,
                            AttributeValue.builder()
                                .s(TcgInventoryItem.formatSkuPk(user, sku.getSkuId()))
                                .build(),
                        TcgInventoryItem.SK,
                            AttributeValue.builder()
                                .s(
                                    TcgInventoryItem.formatUnitSk(
                                        inStock.first().getSequenceNumber()))
                                .build()))
                .updateExpression("SET " + TcgInventoryItem.PHOTOS + " = :photos")
                .expressionAttributeValues(Map.of(":photos", Photos.toAttributeValue(photos)))
                .build());
      }
      imageUrls.add(photo.getFetchtcgUrl());
    }

    var condition = Condition.valueOf(sku.getCondition()).toFetchtcg();
    var price = new BigDecimal(sku.getSuggestedPrice());
    if (Photos.needsPublishWarning(price, photos)) {
      LOGGER.warn("upserting photo-less sku {} at price {} (>= NZ$50)", sku.getSkuId(), price);
    }

    var frontImage = imageUrls.isEmpty() ? null : imageUrls.get(0);
    List<String> additionalImages =
        imageUrls.size() <= 1 ? List.of() : imageUrls.subList(1, imageUrls.size());
    var upsertRequest =
        new FetchTcgClient.UpsertListingRequest(
            sku.getFetchtcgCardId(),
            condition,
            inStock.count(),
            price,
            frontImage,
            additionalImages);

    var response = fetchTcgClient.upsertListing(bearerToken, upsertRequest);

    clearDirtyWithSnapshot(
        user,
        sku.getSkuId(),
        capturedVersion,
        response.listingId(),
        inStock.count(),
        price.toPlainString());
  }

  private record InStock(int count, TcgInventoryItem first) {}

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
