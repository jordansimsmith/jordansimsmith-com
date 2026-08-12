package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.dynamodb.DynamoDbUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Testcontainers
public class TcgInventoryE2ETest {

  @Container
  private static final TcgInventoryContainer tcgInventoryContainer = new TcgInventoryContainer();

  private DynamoDbClient dynamoDbClient;

  @BeforeEach
  void setup() {
    dynamoDbClient =
        DynamoDbClient.builder().endpointOverride(tcgInventoryContainer.getLocalstackUrl()).build();

    DynamoDbUtils.reset(dynamoDbClient);
  }

  @Test
  void shouldStartContainerWithDynamoDbTable() {
    // act
    var tables = dynamoDbClient.listTables().tableNames();

    // assert
    assertThat(tables).contains("tcg_inventory");

    var tableDesc = dynamoDbClient.describeTable(r -> r.tableName("tcg_inventory")).table();
    var gsiNames = tableDesc.globalSecondaryIndexes().stream().map(gsi -> gsi.indexName()).toList();
    assertThat(gsiNames).containsExactlyInAnyOrder("gsi1", "gsi2");
  }
}
