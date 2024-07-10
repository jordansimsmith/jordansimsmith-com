package com.jordansimsmith.immersiontracker;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
public class MainIntegrationTest {

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
    public GenericContainer<?> dynamodb = getDynamoDb();

    @Test
    void test() {
        assertThat(dynamodb.isRunning()).isTrue();
        assertThat(dynamodb.getHost()).isEqualTo("localhost");
    }
}