package com.jordansimsmith.tcginventory.publish.server;

import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.listings.client.ListingService;
import com.jordansimsmith.tcginventory.orders.client.OrderService;
import com.jordansimsmith.tcginventory.publish.client.CreatePublishRequest;
import com.jordansimsmith.tcginventory.publish.client.CreatePublishResponse;
import com.jordansimsmith.tcginventory.publish.client.GetPublishRequest;
import com.jordansimsmith.tcginventory.publish.client.GetPublishResponse;
import com.jordansimsmith.tcginventory.publish.client.PublishService;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import dagger.Module;
import dagger.Provides;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Module
public class PublishModule {
  @Provides
  @Singleton
  PublishRepository publishRepository(
      DynamoDbEnhancedClient dynamoDb,
      DynamoDbClient dynamoDbClient,
      @Named("tcgInventoryTableName") String tableName) {
    DynamoDbTable<PublishItem> publishTable =
        dynamoDb.table(tableName, TableSchema.fromBean(PublishItem.class));
    return new PublishRepository(publishTable, dynamoDbClient, tableName);
  }

  @Provides
  @Singleton
  PublishService publishService(
      PublishRepository publishRepository,
      QueueClient<PublishMessage> publishQueue,
      Clock clock,
      UlidGenerator ulidGenerator,
      InventoryService inventoryService) {
    var createPublishService =
        new CreatePublishService(publishRepository, publishQueue, clock, ulidGenerator);
    var getPublishService = new GetPublishService(publishRepository, inventoryService);
    return new PublishService() {
      @Override
      public CreatePublishResponse createPublish(RequestContext ctx, CreatePublishRequest request)
          throws ServiceException {
        return createPublishService.createPublish(ctx, request);
      }

      @Override
      public GetPublishResponse getPublish(RequestContext ctx, GetPublishRequest request)
          throws ServiceException {
        return getPublishService.getPublish(ctx, request);
      }
    };
  }

  @Provides
  @Singleton
  PublishMessageHandler publishMessageHandler(
      PublishRepository publishRepository,
      QueueClient<PublishMessage> publishQueue,
      Clock clock,
      OrderService orderService,
      InventoryService inventoryService,
      ListingService listingService) {
    return new PublishMessageHandler(
        publishRepository, publishQueue, clock, orderService, inventoryService, listingService);
  }
}
