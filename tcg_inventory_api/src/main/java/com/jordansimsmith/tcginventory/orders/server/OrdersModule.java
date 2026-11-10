package com.jordansimsmith.tcginventory.orders.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.listings.client.ListingService;
import com.jordansimsmith.tcginventory.orders.client.FindOrdersRequest;
import com.jordansimsmith.tcginventory.orders.client.FindOrdersResponse;
import com.jordansimsmith.tcginventory.orders.client.GetOrderRequest;
import com.jordansimsmith.tcginventory.orders.client.GetOrderResponse;
import com.jordansimsmith.tcginventory.orders.client.GetOrderSettingsRequest;
import com.jordansimsmith.tcginventory.orders.client.GetOrderSettingsResponse;
import com.jordansimsmith.tcginventory.orders.client.OrderService;
import com.jordansimsmith.tcginventory.orders.client.UpdateOrderSettingsRequest;
import com.jordansimsmith.tcginventory.orders.client.UpdateOrderSettingsResponse;
import com.jordansimsmith.tcginventory.orders.client.UpdateOrdersRequest;
import com.jordansimsmith.tcginventory.orders.client.UpdateOrdersResponse;
import com.jordansimsmith.time.Clock;
import dagger.Module;
import dagger.Provides;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Module
public class OrdersModule {
  @Provides
  @Singleton
  OrderReadRepository orderReadRepository(
      DynamoDbEnhancedClient dynamoDbEnhancedClient,
      @Named("tcgInventoryTableName") String tableName) {
    DynamoDbTable<OrderItem> orderTable =
        dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(OrderItem.class));
    DynamoDbTable<OrderRowItem> rowTable =
        dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(OrderRowItem.class));
    return new OrderReadRepository(orderTable, rowTable);
  }

  @Provides
  @Singleton
  OrderMutationRepository orderMutationRepository(
      DynamoDbEnhancedClient dynamoDbEnhancedClient,
      DynamoDbClient dynamoDbClient,
      @Named("tcgInventoryTableName") String tableName,
      Clock clock) {
    DynamoDbTable<OrderItem> orderTable =
        dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(OrderItem.class));
    DynamoDbTable<OrderRowItem> rowTable =
        dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(OrderRowItem.class));
    return new OrderMutationRepository(orderTable, rowTable, dynamoDbClient, tableName, clock);
  }

  @Provides
  @Singleton
  FulfillmentMessageHandler fulfillmentMessageHandler(
      OrderReadRepository orderReadRepository,
      OrderMutationRepository orderMutationRepository,
      InventoryService inventoryService) {
    return new FulfillmentMessageHandler(
        orderReadRepository, orderMutationRepository, inventoryService);
  }

  @Provides
  @Singleton
  OrderService orderService(
      DynamoDbEnhancedClient dynamoDbEnhancedClient,
      @Named("tcgInventoryTableName") String tableName,
      ObjectMapper objectMapper,
      InventoryService inventoryService,
      FetchTcgService fetchTcgService,
      ListingService listingService,
      OrderReadRepository orderReadRepository,
      OrderMutationRepository orderMutationRepository,
      Clock clock) {
    DynamoDbTable<OrderSettingsItem> settingsTable =
        dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(OrderSettingsItem.class));
    var settingsRepository = new OrderSettingsRepository(settingsTable);
    var getOrderSettingsService = new GetOrderSettingsService(settingsRepository);
    var updateOrderSettingsService = new UpdateOrderSettingsService(settingsRepository);
    var findOrdersService = new FindOrdersService(orderReadRepository, objectMapper);
    var getOrderService = new GetOrderService(orderReadRepository, inventoryService);
    var updateOrdersService =
        new UpdateOrdersService(
            orderReadRepository,
            orderMutationRepository,
            settingsRepository,
            fetchTcgService,
            listingService,
            inventoryService,
            clock);
    return new OrderService() {
      @Override
      public FindOrdersResponse findOrders(RequestContext ctx, FindOrdersRequest request)
          throws ServiceException {
        return findOrdersService.findOrders(ctx, request);
      }

      @Override
      public GetOrderResponse getOrder(RequestContext ctx, GetOrderRequest request)
          throws ServiceException {
        return getOrderService.getOrder(ctx, request);
      }

      @Override
      public GetOrderSettingsResponse getOrderSettings(
          RequestContext ctx, GetOrderSettingsRequest request) throws ServiceException {
        return getOrderSettingsService.getOrderSettings(ctx, request);
      }

      @Override
      public UpdateOrderSettingsResponse updateOrderSettings(
          RequestContext ctx, UpdateOrderSettingsRequest request) {
        return updateOrderSettingsService.updateOrderSettings(ctx, request);
      }

      @Override
      public UpdateOrdersResponse updateOrders(RequestContext ctx, UpdateOrdersRequest request)
          throws ServiceException {
        return updateOrdersService.updateOrders(ctx, request);
      }
    };
  }
}
