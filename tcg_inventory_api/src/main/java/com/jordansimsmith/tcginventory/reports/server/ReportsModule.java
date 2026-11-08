package com.jordansimsmith.tcginventory.reports.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.queue.SqsQueueClient;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.orders.client.OrderService;
import com.jordansimsmith.tcginventory.reports.client.CreateReportRequest;
import com.jordansimsmith.tcginventory.reports.client.CreateReportResponse;
import com.jordansimsmith.tcginventory.reports.client.GetReportRequest;
import com.jordansimsmith.tcginventory.reports.client.GetReportResponse;
import com.jordansimsmith.tcginventory.reports.client.ReportService;
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
public class ReportsModule {
  @Provides
  @Singleton
  ReportService reportService(
      DynamoDbEnhancedClient dynamoDb,
      DynamoDbClient dynamoDbClient,
      @Named("tcgInventoryTableName") String tableName,
      @Named("reportsQueueName") String queueName,
      SqsClient sqsClient,
      ObjectMapper objectMapper,
      Clock clock,
      UlidGenerator ulidGenerator) {
    var reportTable = dynamoDb.table(tableName, TableSchema.fromBean(ReportItem.class));
    var reportRepository = new ReportRepository(reportTable, dynamoDbClient, tableName);
    QueueClient<ReportMessage> reportsQueue =
        SqsQueueClient.create(sqsClient, objectMapper, queueName);
    var createReportService =
        new CreateReportService(reportRepository, reportsQueue, clock, ulidGenerator);
    var getReportService = new GetReportService(reportRepository, objectMapper, clock);
    return new ReportService() {
      @Override
      public CreateReportResponse createReport(RequestContext ctx, CreateReportRequest request)
          throws ServiceException {
        return createReportService.createReport(ctx, request);
      }

      @Override
      public GetReportResponse getReport(RequestContext ctx, GetReportRequest request)
          throws ServiceException {
        return getReportService.getReport(ctx, request);
      }
    };
  }

  @Provides
  @Singleton
  ReportMessageHandler reportMessageHandler(
      DynamoDbEnhancedClient dynamoDb,
      DynamoDbClient dynamoDbClient,
      @Named("tcgInventoryTableName") String tableName,
      ObjectMapper objectMapper,
      Clock clock,
      GamesService gamesService,
      InventoryService inventoryService,
      OrderService orderService) {
    DynamoDbTable<ReportItem> reportTable =
        dynamoDb.table(tableName, TableSchema.fromBean(ReportItem.class));
    var reportRepository = new ReportRepository(reportTable, dynamoDbClient, tableName);
    return new ReportMessageHandler(
        reportRepository, objectMapper, clock, gamesService, inventoryService, orderService);
  }
}
