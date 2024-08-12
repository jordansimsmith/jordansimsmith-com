package com.jordansimsmith.immersiontracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.google.common.annotations.VisibleForTesting;
import java.util.List;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class GetProgressHandler implements RequestHandler<Object, List<ImmersionTrackerItem>> {
  private final DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;

  public GetProgressHandler() {
    this(ImmersionTrackerFactory.create());
  }

  @VisibleForTesting
  GetProgressHandler(ImmersionTrackerFactory factory) {
    this.immersionTrackerTable = factory.immersionTrackerTable();
  }

  @Override
  public List<ImmersionTrackerItem> handleRequest(Object s, Context context) {

    // TODO: auth
    var user = "jordansimsmith";

    var query =
        immersionTrackerTable.query(
            QueryEnhancedRequest.builder()
                .queryConditional(
                    QueryConditional.keyEqualTo(
                        b -> b.partitionValue(ImmersionTrackerItem.formatPk(user))))
                .build());

    return query.items().stream().toList();
  }
}
