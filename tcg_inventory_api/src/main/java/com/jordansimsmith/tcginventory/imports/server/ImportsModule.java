package com.jordansimsmith.tcginventory.imports.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.imports.client.FindImportsRequest;
import com.jordansimsmith.tcginventory.imports.client.FindImportsResponse;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import dagger.Module;
import dagger.Provides;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

@Module
public class ImportsModule {
  @Provides
  @Singleton
  ImportService importService(
      DynamoDbEnhancedClient dynamoDbEnhancedClient,
      @Named("tcgInventoryTableName") String tableName,
      ObjectMapper objectMapper) {
    DynamoDbTable<ImportItem> importTable =
        dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(ImportItem.class));
    var importReadRepository = new ImportReadRepository(importTable);
    var findImportsService = new FindImportsService(importReadRepository, objectMapper);
    return new ImportService() {
      @Override
      public FindImportsResponse findImports(RequestContext ctx, FindImportsRequest request)
          throws ServiceException {
        return findImportsService.findImports(ctx, request);
      }
    };
  }
}
