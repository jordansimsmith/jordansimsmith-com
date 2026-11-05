package com.jordansimsmith.tcginventory.orders.server;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.orders.client.GetOrderSettingsRequest;
import com.jordansimsmith.tcginventory.orders.client.GetOrderSettingsResponse;
import com.jordansimsmith.tcginventory.orders.client.OrderService;
import com.jordansimsmith.tcginventory.orders.client.UpdateOrderSettingsRequest;
import com.jordansimsmith.tcginventory.orders.client.UpdateOrderSettingsResponse;
import dagger.Module;
import dagger.Provides;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class OrdersModule {
  @Provides
  @Singleton
  OrderService orderService(
      DynamoDbEnhancedClient dynamoDbEnhancedClient,
      @Named("tcgInventoryTableName") String tableName) {
    DynamoDbTable<OrderSettingsItem> settingsTable =
        dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(OrderSettingsItem.class));
    var settingsRepository = new OrderSettingsRepository(settingsTable);
    var getOrderSettingsService = new GetOrderSettingsService(settingsRepository);
    var updateOrderSettingsService = new UpdateOrderSettingsService(settingsRepository);
    return new OrderService() {
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
    };
  }
}
