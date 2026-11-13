package com.jordansimsmith.tcginventory.scans.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.scans.client.CreateScanRequest;
import com.jordansimsmith.tcginventory.scans.client.CreateScanResponse;
import com.jordansimsmith.tcginventory.scans.client.DeleteScanRequest;
import com.jordansimsmith.tcginventory.scans.client.DeleteScanResponse;
import com.jordansimsmith.tcginventory.scans.client.DeleteScanRowRequest;
import com.jordansimsmith.tcginventory.scans.client.DeleteScanRowResponse;
import com.jordansimsmith.tcginventory.scans.client.FindScansRequest;
import com.jordansimsmith.tcginventory.scans.client.FindScansResponse;
import com.jordansimsmith.tcginventory.scans.client.GetScanRequest;
import com.jordansimsmith.tcginventory.scans.client.GetScanResponse;
import com.jordansimsmith.tcginventory.scans.client.ScanService;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import dagger.Module;
import dagger.Provides;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Module
public class ScansModule {
  @Provides
  @Singleton
  ScanService scanService(
      DynamoDbEnhancedClient enhancedClient,
      DynamoDbClient dynamoDbClient,
      @Named("tcgInventoryTableName") String tableName,
      ObjectMapper objectMapper,
      Clock clock,
      UlidGenerator ulidGenerator,
      GamesService gamesService,
      S3Client s3Client,
      S3Presigner s3Presigner,
      @Named("scanImagesBucket") String bucket) {
    var scanTable = enhancedClient.table(tableName, TableSchema.fromBean(ScanItem.class));
    var scanRowTable = enhancedClient.table(tableName, TableSchema.fromBean(ScanRowItem.class));
    var readRepository = new ScanReadRepository(scanTable, scanRowTable);
    var mutationRepository =
        new ScanMutationRepository(scanTable, scanRowTable, dynamoDbClient, clock, tableName);
    var createScanService =
        new CreateScanService(
            mutationRepository, clock, ulidGenerator, s3Presigner, gamesService, bucket);
    var findScansService = new FindScansService(readRepository, objectMapper);
    var getScanService = new GetScanService(readRepository, s3Client, s3Presigner, bucket);
    var deleteScanRowService =
        new DeleteScanRowService(readRepository, mutationRepository, s3Client, bucket);
    var deleteScanService =
        new DeleteScanService(readRepository, mutationRepository, s3Client, bucket);
    return new ScanService() {
      @Override
      public CreateScanResponse createScan(RequestContext ctx, CreateScanRequest request)
          throws ServiceException {
        return createScanService.createScan(ctx, request);
      }

      @Override
      public FindScansResponse findScans(RequestContext ctx, FindScansRequest request)
          throws ServiceException {
        return findScansService.findScans(ctx, request);
      }

      @Override
      public GetScanResponse getScan(RequestContext ctx, GetScanRequest request)
          throws ServiceException {
        return getScanService.getScan(ctx, request);
      }

      @Override
      public DeleteScanRowResponse deleteScanRow(RequestContext ctx, DeleteScanRowRequest request)
          throws ServiceException {
        return deleteScanRowService.deleteScanRow(ctx, request);
      }

      @Override
      public DeleteScanResponse deleteScan(RequestContext ctx, DeleteScanRequest request)
          throws ServiceException {
        return deleteScanService.deleteScan(ctx, request);
      }
    };
  }
}
