package com.jordansimsmith.tcginventory.scans.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.scans.client.FindScansRequest;
import com.jordansimsmith.tcginventory.scans.client.FindScansResponse;
import com.jordansimsmith.tcginventory.scans.client.GetScanRequest;
import com.jordansimsmith.tcginventory.scans.client.GetScanResponse;
import com.jordansimsmith.tcginventory.scans.client.ScanService;
import dagger.Module;
import dagger.Provides;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Module
public class ScansModule {
  @Provides
  @Singleton
  ScanService scanService(
      DynamoDbEnhancedClient enhancedClient,
      @Named("tcgInventoryTableName") String tableName,
      ObjectMapper objectMapper,
      S3Client s3Client,
      S3Presigner s3Presigner,
      @Named("scanImagesBucket") String bucket) {
    var scanTable = enhancedClient.table(tableName, TableSchema.fromBean(ScanItem.class));
    var scanRowTable = enhancedClient.table(tableName, TableSchema.fromBean(ScanRowItem.class));
    var repository = new ScanReadRepository(scanTable, scanRowTable);
    var findScansService = new FindScansService(repository, objectMapper);
    var getScanService = new GetScanService(repository, s3Client, s3Presigner, bucket);
    return new ScanService() {
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
    };
  }
}
