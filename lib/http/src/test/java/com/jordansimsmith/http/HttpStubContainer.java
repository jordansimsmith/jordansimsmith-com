package com.jordansimsmith.http;

import com.google.common.base.Preconditions;
import com.jordansimsmith.testcontainers.LoadedImage;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Properties;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

public abstract class HttpStubContainer<T extends HttpStubContainer<T>>
    extends GenericContainer<T> {
  private static final int HTTP_PORT = 8080;

  private final String networkAlias;
  private boolean hasNetwork;

  protected HttpStubContainer(
      String propertyFileName,
      String imageNameProperty,
      String imageLoaderProperty,
      String deployJarPath,
      String mainClassName,
      String waitPath,
      String networkAlias) {
    super(
        LoadedImage.loadImage(
            getProperty(propertyFileName, imageNameProperty),
            getProperty(propertyFileName, imageLoaderProperty)));

    this.networkAlias = networkAlias;

    this.withExposedPorts(HTTP_PORT);
    this.withCommand(
        "java", "--add-modules", "jdk.httpserver", "-cp", deployJarPath, mainClassName);
    this.waitingFor(Wait.forHttp(waitPath).forPort(HTTP_PORT));
    this.withStartupTimeout(Duration.ofMinutes(1));
  }

  @Override
  public T withNetwork(Network network) {
    hasNetwork = true;
    return super.withNetwork(network).withNetworkAliases(networkAlias);
  }

  @SuppressWarnings("HttpUrlsUsage")
  public URI getEndpoint() {
    if (hasNetwork) {
      return URI.create("http://" + networkAlias + ":" + HTTP_PORT);
    }
    return URI.create("http://" + getHost() + ":" + getMappedPort(HTTP_PORT));
  }

  protected static String getProperty(String propertyFileName, String key) {
    try (var input =
        HttpStubContainer.class.getClassLoader().getResourceAsStream(propertyFileName)) {
      Preconditions.checkNotNull(input);
      var properties = new Properties();
      properties.load(input);
      return properties.getProperty(key);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
