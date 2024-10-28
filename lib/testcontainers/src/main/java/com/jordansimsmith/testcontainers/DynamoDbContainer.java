package com.jordansimsmith.testcontainers;

import java.net.URI;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.TestcontainersConfiguration;

public class DynamoDbContainer extends GenericContainer<DynamoDbContainer> {
  private static final int DYNAMODB_PORT = 8000;

  public DynamoDbContainer() {
    super(
        LoadedImage.loadImage(
            TestcontainersConfiguration.getInstance()
                .getClasspathProperties()
                .getProperty("dynamodb.image.name"),
            TestcontainersConfiguration.getInstance()
                .getClasspathProperties()
                .getProperty("dynamodb.image.loader")));

    this.withExposedPorts(DYNAMODB_PORT);
  }

  @SuppressWarnings("HttpUrlsUsage")
  public URI getEndpoint() {
    return URI.create("http://%s:%d".formatted(this.getHost(), this.getMappedPort(DYNAMODB_PORT)));
  }
}
