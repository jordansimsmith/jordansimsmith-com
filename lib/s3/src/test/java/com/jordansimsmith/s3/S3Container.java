package com.jordansimsmith.s3;

import com.google.common.base.Preconditions;
import com.jordansimsmith.testcontainers.LoadedImage;
import java.io.IOException;
import java.net.URI;
import java.util.Properties;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public class S3Container extends GenericContainer<S3Container> {
  private static final int S3_PORT = 4566;
  static final String ACCESS_KEY = "test";
  static final String SECRET_KEY = "test";

  public S3Container() {
    super(
        LoadedImage.loadImage(
            getProperty("s3.properties", "s3.image.name"),
            getProperty("s3.properties", "s3.image.loader")));

    this.withExposedPorts(S3_PORT);
    this.withEnv("SERVICES", "s3");
    this.withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY);
    this.withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY);
    this.withEnv("AWS_DEFAULT_REGION", "us-east-1");
    this.withEnv("AWS_REGION", "us-east-1");
    this.waitingFor(Wait.forHttp("/_localstack/health").forPort(S3_PORT));
  }

  private static String getProperty(String propertyFileName, String key) {
    try (var input = S3Container.class.getClassLoader().getResourceAsStream(propertyFileName)) {
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
    return URI.create("http://%s:%d".formatted(this.getHost(), this.getMappedPort(S3_PORT)));
  }
}
