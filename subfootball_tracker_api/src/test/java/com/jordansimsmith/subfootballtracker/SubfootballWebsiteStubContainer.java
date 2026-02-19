package com.jordansimsmith.subfootballtracker;

import com.google.common.base.Preconditions;
import com.jordansimsmith.testcontainers.LoadedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.Properties;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public class SubfootballWebsiteStubContainer
    extends GenericContainer<SubfootballWebsiteStubContainer> {
  private static final int SUBFOOTBALL_WEBSITE_PORT = 8080;

  public SubfootballWebsiteStubContainer() {
    super(
        LoadedImage.loadImage(
            getProperty("test.properties", "subfootballwebsitestub.image.name"),
            getProperty("test.properties", "subfootballwebsitestub.image.loader")));

    this.withExposedPorts(SUBFOOTBALL_WEBSITE_PORT);
    this.withCommand(
        "java",
        "--add-modules",
        "jdk.httpserver",
        "-cp",
        "/opt/code/subfootball-website-stub/subfootball-website-stub-server_deploy.jar",
        "com.jordansimsmith.subfootballtracker.SubfootballWebsiteStubServer");
    this.waitingFor(Wait.forHttp("/register").forPort(SUBFOOTBALL_WEBSITE_PORT));
    this.withStartupTimeout(Duration.ofMinutes(1));
  }

  private static String getProperty(String propertyFileName, String key) {
    try (var input =
        SubfootballWebsiteStubContainer.class
            .getClassLoader()
            .getResourceAsStream(propertyFileName)) {
      Preconditions.checkNotNull(input);
      var properties = new Properties();
      properties.load(input);
      return properties.getProperty(key);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
