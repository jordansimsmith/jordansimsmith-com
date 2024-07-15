package com.jordansimsmith.testcontainers;

import java.net.URI;
import java.net.URISyntaxException;
import org.testcontainers.containers.GenericContainer;

public class DynamoDbContainer extends GenericContainer<DynamoDbContainer> {
  private static final int DYNAMODB_PORT = 8000;

  public DynamoDbContainer() {
    super(LoadedImage.loadImage("dynamodb.image.name", "dynamodb.image.loader"));

    this.withExposedPorts(DYNAMODB_PORT);
  }

  public URI getEndpoint() {
    try {
      //noinspection HttpUrlsUsage
      return new URI("http://%s:%d".formatted(this.getHost(), this.getMappedPort(DYNAMODB_PORT)));
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }
}
