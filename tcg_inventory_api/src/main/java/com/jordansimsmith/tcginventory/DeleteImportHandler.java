package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class DeleteImportHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(DeleteImportHandler.class);
  private static final String DELETABLE_STATUS = "review";

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;

  public DeleteImportHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  DeleteImportHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryTable = factory.tcgInventoryTable();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing delete import request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    var importId = event.getPathParameters().get("import_id");

    var importKey =
        Key.builder()
            .partitionValue(TcgInventoryItem.formatUserPk(user))
            .sortValue(TcgInventoryItem.formatImportSk(importId))
            .build();

    var importItem = tcgInventoryTable.getItem(importKey);
    if (importItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    if (!DELETABLE_STATUS.equals(importItem.getStatus())) {
      return httpResponseFactory.conflict(new ErrorResponse("import is not in a deletable status"));
    }

    // delete all rows
    var rowQueryConditional =
        QueryConditional.sortBeginsWith(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatImportRowPk(user, importId))
                .sortValue(TcgInventoryItem.ROW_PREFIX)
                .build());

    var rowRequest =
        QueryEnhancedRequest.builder()
            .queryConditional(rowQueryConditional)
            .scanIndexForward(true)
            .build();

    tcgInventoryTable.query(rowRequest).stream()
        .flatMap(page -> page.items().stream())
        .forEach(
            rowItem ->
                tcgInventoryTable.deleteItem(
                    Key.builder()
                        .partitionValue(rowItem.getPk())
                        .sortValue(rowItem.getSk())
                        .build()));

    // delete import item
    tcgInventoryTable.deleteItem(importKey);

    return httpResponseFactory.noContent();
  }
}
