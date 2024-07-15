package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.testcontainers.DynamoDbContainer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

@Testcontainers
public class MainIntegrationTest {

  @Container DynamoDbContainer dynamodb = new DynamoDbContainer();

  @Test
  void test() throws Exception {
    assertThat(dynamodb.isRunning()).isTrue();
    assertThat(dynamodb.getHost()).isEqualTo("localhost");

    var endpoint = dynamodb.getEndpoint();

    var client = DynamoDbClient.builder().endpointOverride(endpoint).build();
    var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();

    assertThat(client.listTables().tableNames()).isEmpty();

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
    var res = client.createTable(req);

    assertThat(client.listTables().tableNames()).contains("my_table");
  }
}
