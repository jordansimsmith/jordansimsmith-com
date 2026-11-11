package com.jordansimsmith.tcginventory.imports.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.imports.client.FindImportsRequest;
import com.jordansimsmith.tcginventory.imports.client.FindImportsResponse;
import com.jordansimsmith.tcginventory.imports.client.GetImportRequest;
import com.jordansimsmith.tcginventory.imports.client.GetImportResponse;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import com.jordansimsmith.tcginventory.imports.client.UpdateImportRowRequest;
import com.jordansimsmith.tcginventory.imports.client.UpdateImportRowResponse;
import dagger.Module;
import dagger.Provides;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Module
public class ImportsModule {
  @Provides
  @Singleton
  ImportService importService(
      DynamoDbEnhancedClient dynamoDbEnhancedClient,
      DynamoDbClient dynamoDbClient,
      @Named("tcgInventoryTableName") String tableName,
      ObjectMapper objectMapper) {
    var importTable =
        dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(ImportItem.class));
    var importRowTable =
        dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(ImportRowItem.class));
    var importReadRepository = new ImportReadRepository(importTable, importRowTable);
    var importMutationRepository = new ImportMutationRepository(dynamoDbClient, tableName);
    var findImportsService = new FindImportsService(importReadRepository, objectMapper);
    var getImportService = new GetImportService(importReadRepository);
    var updateImportRowService =
        new UpdateImportRowService(importReadRepository, importMutationRepository);
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

      @Override
      public UpdateImportRowResponse updateImportRow(
          RequestContext ctx, UpdateImportRowRequest request) throws ServiceException {
        return updateImportRowService.updateImportRow(ctx, request);
      }
    };
  }
}
