package com.jordansimsmith.tcginventory.publish;

import com.jordansimsmith.tcginventory.BatchResult;
import com.jordansimsmith.tcginventory.Condition;
import com.jordansimsmith.tcginventory.Photos;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgClient;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.time.Clock;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
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
  public static final int BATCH_SIZE = 100;

  private static final Logger LOGGER = LoggerFactory.getLogger(ListingPhaseProcessor.class);

  private final DynamoDbTable<SkuItem> skuTable;
  private final InventoryRepository inventoryRepository;
  private final DynamoDbClient dynamoDbClient;
  private final Clock clock;
  private final FetchTcgClient fetchTcgClient;
  private final S3Client s3Client;

  public ListingPhaseProcessor(
      DynamoDbTable<SkuItem> skuTable,
      InventoryRepository inventoryRepository,
      DynamoDbClient dynamoDbClient,
      Clock clock,
      FetchTcgClient fetchTcgClient,
      S3Client s3Client) {
    this.skuTable = skuTable;
    this.inventoryRepository = inventoryRepository;
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

  private List<SkuItem> loadDirtySkus(String user) {
    var results = new ArrayList<SkuItem>();
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(SkuItem.formatGsi1pk(user))
                        .sortValue(SkuItem.SKU_PREFIX)
                        .build()))
            .build();

    skuTable.index(TcgInventoryTable.GSI1_NAME).query(request).stream()
        .flatMap(page -> page.items().stream())
        .limit(BATCH_SIZE)
        .forEach(results::add);
    return results;
  }

  private InStock loadInStock(String user, String skuId) {
    int count = 0;
    UnitItem first = null;
    for (var item : inventoryRepository.findUnits(user, skuId)) {
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
      String user, String bearerToken, SkuItem sku, InStock inStock, int capturedVersion) {
    var photos =
        inStock.first().getPhotos() == null
            ? new ArrayList<UnitItem.Photo>()
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
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK,
                            AttributeValue.builder()
                                .s(SkuItem.formatPk(user, sku.getSkuId()))
                                .build(),
                        SkuItem.SK,
                            AttributeValue.builder()
                                .s(UnitItem.formatSk(inStock.first().getSequenceNumber()))
                                .build()))
                .updateExpression("SET " + UnitItem.PHOTOS + " = :photos")
                .expressionAttributeValues(Map.of(":photos", toAttributeValue(photos)))
                .build());
      }
      imageUrls.add(photo.getFetchtcgUrl());
    }

    var condition = Condition.valueOf(sku.getCondition()).toFetchtcg();
    var price = new BigDecimal(sku.getSuggestedPrice());
    if (Photos.needsPublishWarning(price, photos.size())) {
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

  private record InStock(int count, UnitItem first) {}

  private static AttributeValue toAttributeValue(List<UnitItem.Photo> photos) {
    var photoValues = new ArrayList<AttributeValue>();
    for (var photo : photos) {
      var values = new HashMap<String, AttributeValue>();
      values.put(UnitItem.Photo.PHOTO_ID, AttributeValue.builder().s(photo.getPhotoId()).build());
      if (photo.getFetchtcgUrl() != null) {
        values.put(
            UnitItem.Photo.FETCHTCG_URL,
            AttributeValue.builder().s(photo.getFetchtcgUrl()).build());
      }
      photoValues.add(AttributeValue.builder().m(values).build());
    }
    return AttributeValue.builder().l(photoValues).build();
  }

  private void delistSku(String user, String bearerToken, SkuItem sku, int capturedVersion) {
    fetchTcgClient.deleteListing(bearerToken, sku.getFetchtcgListingId());
    clearDirtyRemoveSnapshot(user, sku.getSkuId(), capturedVersion);
  }

  private void clearDirtyWithSnapshot(
      String user, String skuId, int capturedVersion, int listingId, int quantity, String price) {
    var skuPk = SkuItem.formatPk(user, skuId);
    var cleanGsi1pk = SkuItem.USER_PREFIX + user + "#CLEAN";

    try {
      dynamoDbClient.updateItem(
          UpdateItemRequest.builder()
              .tableName(TcgInventoryTable.TABLE_NAME)
              .key(
                  Map.of(
                      SkuItem.PK, AttributeValue.builder().s(skuPk).build(),
                      SkuItem.SK, AttributeValue.builder().s(SkuItem.formatSk()).build()))
              .updateExpression(
                  "SET "
                      + SkuItem.DIRTY
                      + " = :clean, "
                      + SkuItem.GSI1PK
                      + " = :gsi1pk, "
                      + SkuItem.FETCHTCG_LISTING_ID
                      + " = :listingId, "
                      + SkuItem.LAST_PUBLISHED_QUANTITY
                      + " = :qty, "
                      + SkuItem.LAST_PUBLISHED_PRICE
                      + " = :price, "
                      + SkuItem.LAST_PUBLISHED_AT
                      + " = :now")
              .conditionExpression(
                  SkuItem.DIRTY + " = :dirty AND " + SkuItem.VERSION + " = :version")
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
    var skuPk = SkuItem.formatPk(user, skuId);
    var cleanGsi1pk = SkuItem.USER_PREFIX + user + "#CLEAN";

    try {
      dynamoDbClient.updateItem(
          UpdateItemRequest.builder()
              .tableName(TcgInventoryTable.TABLE_NAME)
              .key(
                  Map.of(
                      SkuItem.PK, AttributeValue.builder().s(skuPk).build(),
                      SkuItem.SK, AttributeValue.builder().s(SkuItem.formatSk()).build()))
              .updateExpression(
                  "SET "
                      + SkuItem.DIRTY
                      + " = :clean, "
                      + SkuItem.GSI1PK
                      + " = :gsi1pk REMOVE "
                      + SkuItem.FETCHTCG_LISTING_ID
                      + ", "
                      + SkuItem.LAST_PUBLISHED_QUANTITY
                      + ", "
                      + SkuItem.LAST_PUBLISHED_PRICE
                      + ", "
                      + SkuItem.LAST_PUBLISHED_AT)
              .conditionExpression(
                  SkuItem.DIRTY + " = :dirty AND " + SkuItem.VERSION + " = :version")
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
    var skuPk = SkuItem.formatPk(user, skuId);
    var cleanGsi1pk = SkuItem.USER_PREFIX + user + "#CLEAN";

    try {
      dynamoDbClient.updateItem(
          UpdateItemRequest.builder()
              .tableName(TcgInventoryTable.TABLE_NAME)
              .key(
                  Map.of(
                      SkuItem.PK, AttributeValue.builder().s(skuPk).build(),
                      SkuItem.SK, AttributeValue.builder().s(SkuItem.formatSk()).build()))
              .updateExpression(
                  "SET " + SkuItem.DIRTY + " = :clean, " + SkuItem.GSI1PK + " = :gsi1pk")
              .conditionExpression(
                  SkuItem.DIRTY + " = :dirty AND " + SkuItem.VERSION + " = :version")
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
