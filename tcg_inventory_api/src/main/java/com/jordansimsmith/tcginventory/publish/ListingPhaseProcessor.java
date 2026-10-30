package com.jordansimsmith.tcginventory.publish;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.Condition;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.fetchtcg.client.CreateListingImageRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.DeleteListingRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateListingRequest;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.photos.client.GetPhotoBytesRequest;
import com.jordansimsmith.tcginventory.photos.client.PhotosService;
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

public class ListingPhaseProcessor {
  public static final int BATCH_SIZE = 100;

  private static final Logger LOGGER = LoggerFactory.getLogger(ListingPhaseProcessor.class);

  private final DynamoDbTable<SkuItem> skuTable;
  private final InventoryService inventoryService;
  private final DynamoDbClient dynamoDbClient;
  private final Clock clock;
  private final FetchTcgService fetchTcgService;
  private final PhotosService photosService;

  public ListingPhaseProcessor(
      DynamoDbTable<SkuItem> skuTable,
      InventoryService inventoryService,
      DynamoDbClient dynamoDbClient,
      Clock clock,
      FetchTcgService fetchTcgService,
      PhotosService photosService) {
    this.skuTable = skuTable;
    this.inventoryService = inventoryService;
    this.dynamoDbClient = dynamoDbClient;
    this.clock = clock;
    this.fetchTcgService = fetchTcgService;
    this.photosService = photosService;
  }

  public JobProcessor.SuccessJobResult process(String user, int continuation)
      throws ServiceException {
    var dirtySkus = loadDirtySkus(user);
    int processed = 0;

    for (var sku : dirtySkus) {
      var skuId = sku.getSkuId();
      var capturedVersion = sku.getVersion();
      var inStock = loadInStock(user, skuId);

      if (inStock.count() > 0) {
        publishSku(user, sku, inStock, capturedVersion);
      } else if (sku.getFetchtcgListingId() != null) {
        delistSku(user, sku, capturedVersion);
      } else {
        clearDirty(user, skuId, capturedVersion);
      }
      processed++;
    }

    return new JobProcessor.SuccessJobResult(
        continuation + processed,
        dirtySkus.size() < BATCH_SIZE ? null : Integer.toString(continuation + processed));
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

  private InStock loadInStock(String user, String skuId) throws ServiceException {
    var ctx = new RequestContext(user);
    var units = new ArrayList<Unit>();
    String continuation = null;
    do {
      var response =
          inventoryService.findUnits(ctx, new FindUnitsRequest(skuId, 100, continuation));
      units.addAll(response.units());
      continuation = response.nextContinuation();
    } while (continuation != null);

    var inStockUnits = units.stream().filter(unit -> unit.status() == UnitStatus.IN_STOCK).toList();
    return new InStock(
        inStockUnits.size(), inStockUnits.isEmpty() ? null : inStockUnits.getFirst());
  }

  private void publishSku(String user, SkuItem sku, InStock inStock, int capturedVersion)
      throws ServiceException {
    var photos = inStock.first().photos();
    var imageUrls = new ArrayList<String>();
    var ctx = new RequestContext(user);
    for (var photoId : photos) {
      var bytes = photosService.getPhotoBytes(ctx, new GetPhotoBytesRequest(photoId));
      var imageResponse =
          fetchTcgService.createListingImage(
              ctx, new CreateListingImageRequest(bytes.photoBytes(), photoId + ".jpg"));
      imageUrls.add(imageResponse.imageUrl());
    }

    var condition = Condition.valueOf(sku.getCondition()).toFetchtcg();
    var price = new BigDecimal(sku.getSuggestedPrice());
    if (price.compareTo(new BigDecimal("50")) >= 0 && photos.isEmpty()) {
      LOGGER.warn("upserting photo-less sku {} at price {} (>= NZ$50)", sku.getSkuId(), price);
    }

    var frontImage = imageUrls.isEmpty() ? null : imageUrls.get(0);
    List<String> additionalImages =
        imageUrls.size() <= 1 ? List.of() : imageUrls.subList(1, imageUrls.size());
    var updateRequest =
        new UpdateListingRequest(
            sku.getFetchtcgCardId(),
            condition,
            inStock.count(),
            price,
            frontImage,
            additionalImages);

    var response = fetchTcgService.updateListing(new RequestContext(user), updateRequest);

    clearDirtyWithSnapshot(
        user,
        sku.getSkuId(),
        capturedVersion,
        response.listingId(),
        inStock.count(),
        price.toPlainString());
  }

  private record InStock(int count, Unit first) {}

  private void delistSku(String user, SkuItem sku, int capturedVersion) throws ServiceException {
    fetchTcgService.deleteListing(
        new RequestContext(user), new DeleteListingRequest(sku.getFetchtcgListingId()));
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
