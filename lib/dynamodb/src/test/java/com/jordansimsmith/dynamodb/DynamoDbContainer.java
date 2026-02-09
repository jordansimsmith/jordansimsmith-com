package com.jordansimsmith.dynamodb;

import com.google.common.base.Preconditions;
import com.jordansimsmith.testcontainers.LoadedImage;
import java.io.IOException;
import java.net.URI;
import java.util.Properties;
import org.testcontainers.containers.GenericContainer;

public class DynamoDbContainer extends GenericContainer<DynamoDbContainer> {
  private static final int DYNAMODB_PORT = 8000;

  public DynamoDbContainer() {
    super(
        LoadedImage.loadImage(
            getProperty("dynamodb.properties", "dynamodb.image.name"),
            getProperty("dynamodb.properties", "dynamodb.image.loader")));

    this.withExposedPorts(DYNAMODB_PORT);
  }

  private static String getProperty(String propertyFileName, String key) {
    try (var input =
        DynamoDbContainer.class.getClassLoader().getResourceAsStream(propertyFileName)) {
      Preconditions.checkNotNull(input);
      var properties = new Properties();
      properties.load(input);
      return properties.getProperty(key);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("HttpUrlsUsage")
  public URI getEndpoint() {
    return URI.create("http://%s:%d".formatted(this.getHost(), this.getMappedPort(DYNAMODB_PORT)));
  }
}
