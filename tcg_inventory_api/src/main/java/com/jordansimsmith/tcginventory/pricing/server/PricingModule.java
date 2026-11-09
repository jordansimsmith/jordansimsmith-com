package com.jordansimsmith.tcginventory.pricing.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.queue.SqsQueueClient;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.pricing.client.CreateRepricingRequest;
import com.jordansimsmith.tcginventory.pricing.client.CreateRepricingResponse;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceRequest;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceResponse;
import com.jordansimsmith.tcginventory.pricing.client.GetPricingRequest;
import com.jordansimsmith.tcginventory.pricing.client.GetPricingResponse;
import com.jordansimsmith.tcginventory.pricing.client.GetRepricingRequest;
import com.jordansimsmith.tcginventory.pricing.client.GetRepricingResponse;
import com.jordansimsmith.tcginventory.pricing.client.PricingService;
import com.jordansimsmith.tcginventory.pricing.client.UpdatePricingRequest;
import com.jordansimsmith.tcginventory.pricing.client.UpdatePricingResponse;
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
import software.amazon.awssdk.services.sqs.SqsClient;

@Module
public class PricingModule {
  @Provides
  @Singleton
  RepricingRepository repricingRepository(
      DynamoDbEnhancedClient dynamoDb,
      DynamoDbClient dynamoDbClient,
      @Named("tcgInventoryTableName") String tableName) {
    DynamoDbTable<RepricingItem> repricingTable =
        dynamoDb.table(tableName, TableSchema.fromBean(RepricingItem.class));
    return new RepricingRepository(repricingTable, dynamoDbClient, tableName);
  }

  @Provides
  @Singleton
  QueueClient<RepricingMessage> pricingQueue(
      SqsClient sqsClient, ObjectMapper objectMapper, @Named("pricingQueueName") String queueName) {
    return SqsQueueClient.create(sqsClient, objectMapper, queueName);
  }

  @Provides
  @Singleton
  RepricingMessageHandler repricingMessageHandler(
      RepricingRepository repricingRepository,
      QueueClient<RepricingMessage> pricingQueue,
      Clock clock,
      InventoryService inventoryService,
      PricingService pricingService) {
    return new RepricingMessageHandler(
        repricingRepository, pricingQueue, clock, inventoryService, pricingService);
  }

  @Provides
  @Singleton
  PricingService pricingService(
      DynamoDbEnhancedClient dynamoDb,
      @Named("tcgInventoryTableName") String tableName,
      FetchTcgService fetchTcgService,
      RepricingRepository repricingRepository,
      QueueClient<RepricingMessage> pricingQueue,
      Clock clock,
      UlidGenerator ulidGenerator) {
    DynamoDbTable<PricingItem> pricingTable =
        dynamoDb.table(tableName, TableSchema.fromBean(PricingItem.class));
    var pricingRepository = new PricingRepository(pricingTable);
    var getPriceService = new GetPriceService(pricingRepository, fetchTcgService);
    var getPricingService = new GetPricingService(pricingRepository);
    var updatePricingService = new UpdatePricingService(pricingRepository);
    var createRepricingService =
        new CreateRepricingService(repricingRepository, pricingQueue, clock, ulidGenerator);
    var getRepricingService = new GetRepricingService(repricingRepository);
    return new PricingService() {
      @Override
      public GetPricingResponse getPricing(RequestContext ctx, GetPricingRequest request)
          throws ServiceException {
        return getPricingService.getPricing(ctx, request);
      }

      @Override
      public UpdatePricingResponse updatePricing(RequestContext ctx, UpdatePricingRequest request)
          throws ServiceException {
        return updatePricingService.updatePricing(ctx, request);
      }

      @Override
      public GetPriceResponse getPrice(RequestContext ctx, GetPriceRequest request)
          throws ServiceException {
        return getPriceService.getPrice(ctx, request);
      }

      @Override
      public CreateRepricingResponse createRepricing(
          RequestContext ctx, CreateRepricingRequest request) throws ServiceException {
        return createRepricingService.createRepricing(ctx, request);
      }

      @Override
      public GetRepricingResponse getRepricing(RequestContext ctx, GetRepricingRequest request)
          throws ServiceException {
        return getRepricingService.getRepricing(ctx, request);
      }
    };
  }
}
