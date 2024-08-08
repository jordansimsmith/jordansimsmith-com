package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.testcontainers.DynamoDbContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

@Testcontainers
public class GetProgressHandlerIntegrationTest {

  private DynamoDbClient dynamodbClient;
  private DynamoDbEnhancedClient dynamoDbEnhancedClient;

  private GetProgressHandler getProgressHandler;

  @Container DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeEach
  void setUp() {
    dynamodbClient =
        DynamoDbClient.builder().endpointOverride(dynamoDbContainer.getEndpoint()).build();
    dynamoDbEnhancedClient =
        DynamoDbEnhancedClient.builder().dynamoDbClient(dynamodbClient).build();

    var req =
        CreateTableRequest.builder()
            .tableName("my_table")
            .keySchema(KeySchemaElement.builder().keyType(KeyType.HASH).attributeName("pk").build())
            .attributeDefinitions(
                AttributeDefinition.builder()
                    .attributeName("pk")
                    .attributeType(ScalarAttributeType.S)
                    .build())
            .provisionedThroughput(
                ProvisionedThroughput.builder()
                    .readCapacityUnits(1L)
                    .writeCapacityUnits(1L)
                    .build())
            .build();
    dynamodbClient.createTable(req);

    var factory =
        ImmersionTrackerFactory.builder().dynamoDbEndpoint(dynamoDbContainer.getEndpoint()).build();
    getProgressHandler = new GetProgressHandler(factory);
  }

  @Test
  void test1() {
    getProgressHandler.handleRequest(null, null);
    assertThat(dynamodbClient.listTables().tableNames()).contains("my_table");
  }

  @Test
  void test2() {
    getProgressHandler.handleRequest(null, null);
    assertThat(dynamodbClient.listTables().tableNames()).doesNotContain("my_table_2");
  }
}
