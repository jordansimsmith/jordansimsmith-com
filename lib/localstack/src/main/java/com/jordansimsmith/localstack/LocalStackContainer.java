package com.jordansimsmith.localstack;

import com.google.common.base.Preconditions;
import com.jordansimsmith.testcontainers.LoadedImage;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

public abstract class LocalStackContainer<T extends LocalStackContainer<T>>
    extends GenericContainer<T> {
  private static final Logger LOGGER = LoggerFactory.getLogger(LocalStackContainer.class);

  private static final int LOCALSTACK_PORT = 4566;

  protected LocalStackContainer(
      String propertyFileName, String imageNameProperty, String imageLoaderProperty) {
    super(
        LoadedImage.loadImage(
            getProperty(propertyFileName, imageNameProperty),
            getProperty(propertyFileName, imageLoaderProperty)));

    this.withExposedPorts(LOCALSTACK_PORT);
    this.withFileSystemBind("/var/run/docker.sock", "/var/run/docker.sock", BindMode.READ_WRITE);
    this.waitingFor(
        Wait.forHttp("/_localstack/init/ready")
            .forResponsePredicate(
                res ->
                    res.contains("\"completed\": true") && !res.contains("\"state\": \"ERROR\"")));
    this.withStartupTimeout(Duration.ofMinutes(2));
    this.withLogConsumer(new Slf4jLogConsumer(LOGGER).withSeparateOutputStreams());
  }

  @SuppressWarnings("HttpUrlsUsage")
  public URI getLocalstackUrl() {
    return URI.create("http://" + getHost() + ":" + getMappedPort(LOCALSTACK_PORT));
  }

  protected int getLocalstackPort() {
    return getMappedPort(LOCALSTACK_PORT);
  }

  protected static String getProperty(String propertyFileName, String key) {
    try (var input =
        LocalStackContainer.class.getClassLoader().getResourceAsStream(propertyFileName)) {
      Preconditions.checkNotNull(input);
      var properties = new Properties();
      properties.load(input);
      return properties.getProperty(key);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
