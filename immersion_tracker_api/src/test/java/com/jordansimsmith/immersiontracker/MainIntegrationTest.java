package com.jordansimsmith.immersiontracker;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
public class MainIntegrationTest {

    private static final int DYNAMODB_PORT = 8000;

    // TODO: move to DynamoDbContainer in separate bazel target
    static GenericContainer<?> getDynamoDb() {
        try {
            var builder = new ProcessBuilder("immersion_tracker_api/dynamodb-load.sh");
            builder.redirectErrorStream(true);
            var process = builder.start();
            var code = process.waitFor();
            if (code != 0) {
                throw new RuntimeException("unsuccessful load");
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }

        return new GenericContainer(DockerImageName.parse("bazel/dynamodb:latest")).withExposedPorts(8000).withImagePullPolicy(image -> false);
    }

    @Container
    GenericContainer<?> dynamodb = getDynamoDb();

    @Test
    void test() throws Exception {
        assertThat(dynamodb.isRunning()).isTrue();
        assertThat(dynamodb.getHost()).isEqualTo("localhost");

        var endpoint = new URI("http://%s:%d".formatted(dynamodb.getHost(), dynamodb.getMappedPort(DYNAMODB_PORT)));

        var client = DynamoDbClient.builder().endpointOverride(endpoint).build();
        var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();

        assertThat(client.listTables().tableNames()).isEmpty();

        var req = CreateTableRequest.builder()
                .tableName("my_table")
                .keySchema(KeySchemaElement.builder().keyType(KeyType.HASH).attributeName("pk").build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build())
                .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(1L).writeCapacityUnits(1L).build())
                .build();
        var res = client.createTable(req);

        assertThat(client.listTables().tableNames()).contains("my_table");
    }
}