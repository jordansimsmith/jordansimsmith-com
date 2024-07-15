package com.jordansimsmith.testcontainers;

import java.net.URI;
import java.net.URISyntaxException;

public class DynamoDbContainer extends LoadedContainer<DynamoDbContainer> {
  private static final int DYNAMODB_PORT = 8000;

  public DynamoDbContainer() {
    super("dynamodb.image.name", "dynamodb.image.loader");

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
