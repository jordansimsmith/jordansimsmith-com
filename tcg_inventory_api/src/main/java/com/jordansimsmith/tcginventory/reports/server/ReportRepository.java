package com.jordansimsmith.tcginventory.reports.server;

import java.time.Instant;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class ReportRepository {
  private final DynamoDbTable<ReportItem> reportTable;
  private final DynamoDbClient dynamoDbClient;
  private final String tableName;

  public ReportRepository(
      DynamoDbTable<ReportItem> reportTable, DynamoDbClient dynamoDbClient, String tableName) {
    this.reportTable = reportTable;
    this.dynamoDbClient = dynamoDbClient;
    this.tableName = tableName;
  }

  @Nullable
  public ReportItem get(String user) {
    var key =
        Key.builder()
            .partitionValue(ReportItem.formatPk(user))
            .sortValue(ReportItem.formatSk())
            .build();
    return reportTable.getItem(
        GetItemEnhancedRequest.builder().key(key).consistentRead(true).build());
  }

  public void createGeneration(String user, String generationId, Instant startedAt) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    ReportItem.PK, AttributeValue.fromS(ReportItem.formatPk(user)),
                    ReportItem.SK, AttributeValue.fromS(ReportItem.formatSk())))
            .conditionExpression(
                "attribute_not_exists(#generation_status) OR #generation_status = :succeeded")
            .updateExpression(
                "SET #user = :user, #generation_id = :generation_id, "
                    + "#generation_status = :queued, #generation_started_at = :started_at "
                    + "REMOVE #generation_finished_at")
            .expressionAttributeNames(
                Map.of(
                    "#user", ReportItem.USER,
                    "#generation_id", ReportItem.GENERATION_ID,
                    "#generation_status", ReportItem.GENERATION_STATUS,
                    "#generation_started_at", ReportItem.GENERATION_STARTED_AT,
                    "#generation_finished_at", ReportItem.GENERATION_FINISHED_AT))
            .expressionAttributeValues(
                Map.of(
                    ":user", AttributeValue.fromS(user),
                    ":generation_id", AttributeValue.fromS(generationId),
                    ":queued", AttributeValue.fromS("queued"),
                    ":succeeded", AttributeValue.fromS("succeeded"),
                    ":started_at", AttributeValue.fromN(Long.toString(startedAt.getEpochSecond()))))
            .build());
  }

  public void startGeneration(String user, String generationId) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    ReportItem.PK, AttributeValue.fromS(ReportItem.formatPk(user)),
                    ReportItem.SK, AttributeValue.fromS(ReportItem.formatSk())))
            .conditionExpression("#generation_id = :generation_id AND #generation_status = :queued")
            .updateExpression("SET #generation_status = :running")
            .expressionAttributeNames(
                Map.of(
                    "#generation_id", ReportItem.GENERATION_ID,
                    "#generation_status", ReportItem.GENERATION_STATUS))
            .expressionAttributeValues(
                Map.of(
                    ":generation_id", AttributeValue.fromS(generationId),
                    ":queued", AttributeValue.fromS("queued"),
                    ":running", AttributeValue.fromS("running")))
            .build());
  }

  public void completeGeneration(
      String user,
      String generationId,
      String reportJson,
      Instant generatedAt,
      Instant finishedAt) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(tableName)
            .key(
                Map.of(
                    ReportItem.PK, AttributeValue.fromS(ReportItem.formatPk(user)),
                    ReportItem.SK, AttributeValue.fromS(ReportItem.formatSk())))
            .conditionExpression(
                "#generation_id = :generation_id AND #generation_status = :running")
            .updateExpression(
                "SET #report = :report, #updated_at = :generated_at, "
                    + "#generation_status = :succeeded, #generation_finished_at = :finished_at")
            .expressionAttributeNames(
                Map.of(
                    "#generation_id", ReportItem.GENERATION_ID,
                    "#generation_status", ReportItem.GENERATION_STATUS,
                    "#report", ReportItem.REPORT,
                    "#updated_at", ReportItem.UPDATED_AT,
                    "#generation_finished_at", ReportItem.GENERATION_FINISHED_AT))
            .expressionAttributeValues(
                Map.of(
                    ":generation_id", AttributeValue.fromS(generationId),
                    ":running", AttributeValue.fromS("running"),
                    ":report", AttributeValue.fromS(reportJson),
                    ":generated_at",
                        AttributeValue.fromN(Long.toString(generatedAt.getEpochSecond())),
                    ":succeeded", AttributeValue.fromS("succeeded"),
                    ":finished_at",
                        AttributeValue.fromN(Long.toString(finishedAt.getEpochSecond()))))
            .build());
  }
}
