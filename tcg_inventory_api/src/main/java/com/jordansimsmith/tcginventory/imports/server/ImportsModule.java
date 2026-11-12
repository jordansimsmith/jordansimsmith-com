package com.jordansimsmith.tcginventory.imports.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.imports.client.CreateImportRowPhotoRequest;
import com.jordansimsmith.tcginventory.imports.client.CreateImportRowPhotoResponse;
import com.jordansimsmith.tcginventory.imports.client.DeleteImportRequest;
import com.jordansimsmith.tcginventory.imports.client.DeleteImportResponse;
import com.jordansimsmith.tcginventory.imports.client.DeleteImportRowPhotoRequest;
import com.jordansimsmith.tcginventory.imports.client.DeleteImportRowPhotoResponse;
import com.jordansimsmith.tcginventory.imports.client.DeleteImportRowRequest;
import com.jordansimsmith.tcginventory.imports.client.DeleteImportRowResponse;
import com.jordansimsmith.tcginventory.imports.client.FindImportsRequest;
import com.jordansimsmith.tcginventory.imports.client.FindImportsResponse;
import com.jordansimsmith.tcginventory.imports.client.GetImportRequest;
import com.jordansimsmith.tcginventory.imports.client.GetImportResponse;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import com.jordansimsmith.tcginventory.imports.client.UpdateImportRowRequest;
import com.jordansimsmith.tcginventory.imports.client.UpdateImportRowResponse;
import com.jordansimsmith.tcginventory.photos.client.PhotosService;
import com.jordansimsmith.tcginventory.pricing.client.PricingService;
import com.jordansimsmith.time.Clock;
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
      ObjectMapper objectMapper,
      PhotosService photosService) {
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
    var deleteImportRowService =
        new DeleteImportRowService(importReadRepository, importMutationRepository);
    var deleteImportService =
        new DeleteImportService(importReadRepository, importMutationRepository);
    var createImportRowPhotoService =
        new CreateImportRowPhotoService(
            importReadRepository, importMutationRepository, photosService);
    var deleteImportRowPhotoService =
        new DeleteImportRowPhotoService(
            importReadRepository, importMutationRepository, photosService);
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

      @Override
      public DeleteImportRowResponse deleteImportRow(
          RequestContext ctx, DeleteImportRowRequest request) throws ServiceException {
        return deleteImportRowService.deleteImportRow(ctx, request);
      }

      @Override
      public DeleteImportResponse deleteImport(RequestContext ctx, DeleteImportRequest request)
          throws ServiceException {
        return deleteImportService.deleteImport(ctx, request);
      }

      @Override
      public CreateImportRowPhotoResponse createImportRowPhoto(
          RequestContext ctx, CreateImportRowPhotoRequest request) throws ServiceException {
        return createImportRowPhotoService.createImportRowPhoto(ctx, request);
      }

      @Override
      public DeleteImportRowPhotoResponse deleteImportRowPhoto(
          RequestContext ctx, DeleteImportRowPhotoRequest request) throws ServiceException {
        return deleteImportRowPhotoService.deleteImportRowPhoto(ctx, request);
      }
    };
  }

  @Provides
  @Singleton
  AppraisalMessageHandler appraisalMessageHandler(
      DynamoDbEnhancedClient dynamoDbEnhancedClient,
      DynamoDbClient dynamoDbClient,
      @Named("tcgInventoryTableName") String tableName,
      FetchTcgService fetchTcgService,
      PricingService pricingService,
      QueueClient<ImportMessage> importsQueue,
      Clock clock) {
    var importTable =
        dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(ImportItem.class));
    var importRowTable =
        dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(ImportRowItem.class));
    var importReadRepository = new ImportReadRepository(importTable, importRowTable);
    var importMutationRepository = new ImportMutationRepository(dynamoDbClient, tableName);
    var cardMatcher = new FetchTcgCardMatcher(fetchTcgService);
    var resolver = new MagicTheGatheringAppraisalResolver(fetchTcgService, cardMatcher);
    var resolverFactory = new AppraisalResolverFactory(resolver);
    return new AppraisalMessageHandler(
        importReadRepository,
        importMutationRepository,
        resolverFactory,
        pricingService,
        new AppraisalPolicy(),
        importsQueue,
        clock);
  }
}
