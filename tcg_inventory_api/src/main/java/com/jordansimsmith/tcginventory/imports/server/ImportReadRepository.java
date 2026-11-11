package com.jordansimsmith.tcginventory.imports.server;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class ImportReadRepository {
  private final DynamoDbTable<ImportItem> importTable;

  public ImportReadRepository(DynamoDbTable<ImportItem> importTable) {
    this.importTable = importTable;
  }

  public ImportPage findImports(
      String user, int limit, @Nullable Map<String, AttributeValue> exclusiveStartKey) {
    var requestBuilder =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(ImportItem.formatPk(user))
                        .sortValue(ImportItem.IMPORT_PREFIX)
                        .build()))
            .scanIndexForward(false)
            .consistentRead(false)
            .limit(limit);
    if (exclusiveStartKey != null) {
      requestBuilder.exclusiveStartKey(exclusiveStartKey);
    }

    var page = importTable.query(requestBuilder.build()).stream().findFirst().orElse(null);
    if (page == null) {
      return new ImportPage(List.of(), null);
    }
    return new ImportPage(page.items(), page.lastEvaluatedKey());
  }

  public record ImportPage(
      List<ImportItem> items, @Nullable Map<String, AttributeValue> lastEvaluatedKey) {}
}
