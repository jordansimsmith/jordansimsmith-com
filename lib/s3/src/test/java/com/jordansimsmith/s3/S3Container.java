package com.jordansimsmith.s3;

import com.google.common.base.Preconditions;
import com.jordansimsmith.testcontainers.LoadedImage;
import java.io.IOException;
import java.net.URI;
import java.util.Properties;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public class S3Container extends GenericContainer<S3Container> {
  private static final int S3_PORT = 9000;
  static final String ROOT_USER = "minioadmin";
  static final String ROOT_PASSWORD = "minioadmin";

  public S3Container() {
    super(
        LoadedImage.loadImage(
            getProperty("minio.properties", "minio.image.name"),
            getProperty("minio.properties", "minio.image.loader")));

    this.withExposedPorts(S3_PORT);
    this.withEnv("MINIO_ROOT_USER", ROOT_USER);
    this.withEnv("MINIO_ROOT_PASSWORD", ROOT_PASSWORD);
    this.withCommand("server", "/data");
    this.waitingFor(Wait.forHttp("/minio/health/live").forPort(S3_PORT));
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
