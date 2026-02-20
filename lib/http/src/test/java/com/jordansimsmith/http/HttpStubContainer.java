package com.jordansimsmith.http;

import com.google.common.base.Preconditions;
import com.jordansimsmith.testcontainers.LoadedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.Properties;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public abstract class HttpStubContainer<T extends HttpStubContainer<T>>
    extends GenericContainer<T> {
  private static final int HTTP_PORT = 8080;

  protected HttpStubContainer(
      String propertyFileName,
      String imageNameProperty,
      String imageLoaderProperty,
      String deployJarPath,
      String mainClassName,
      String waitPath) {
    super(
        LoadedImage.loadImage(
            getProperty(propertyFileName, imageNameProperty),
            getProperty(propertyFileName, imageLoaderProperty)));

    this.withExposedPorts(HTTP_PORT);
    this.withCommand(
        "java", "--add-modules", "jdk.httpserver", "-cp", deployJarPath, mainClassName);
    this.waitingFor(Wait.forHttp(waitPath).forPort(HTTP_PORT));
    this.withStartupTimeout(Duration.ofMinutes(1));
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
