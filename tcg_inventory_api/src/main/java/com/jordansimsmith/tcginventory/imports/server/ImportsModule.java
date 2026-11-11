package com.jordansimsmith.tcginventory.imports.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.imports.client.FindImportsRequest;
import com.jordansimsmith.tcginventory.imports.client.FindImportsResponse;
import com.jordansimsmith.tcginventory.imports.client.GetImportRequest;
import com.jordansimsmith.tcginventory.imports.client.GetImportResponse;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import dagger.Module;
import dagger.Provides;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class ImportsModule {
  @Provides
  @Singleton
  ImportService importService(
      DynamoDbEnhancedClient dynamoDbEnhancedClient,
      @Named("tcgInventoryTableName") String tableName,
      ObjectMapper objectMapper) {
    var importTable =
        dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(ImportItem.class));
    var importRowTable =
        dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(ImportRowItem.class));
    var importReadRepository = new ImportReadRepository(importTable, importRowTable);
    var findImportsService = new FindImportsService(importReadRepository, objectMapper);
    var getImportService = new GetImportService(importReadRepository);
    return new ImportService() {
      @Override
      public FindImportsResponse findImports(RequestContext ctx, FindImportsRequest request)
          throws ServiceException {
        return findImportsService.findImports(ctx, request);
      }

      @Override
      public GetImportResponse getImport(RequestContext ctx, GetImportRequest request)
          throws ServiceException {
        return getImportService.getImport(ctx, request);
      }
    };
  }
}
