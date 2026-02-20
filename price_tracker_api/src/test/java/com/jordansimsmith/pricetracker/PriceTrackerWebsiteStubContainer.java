package com.jordansimsmith.pricetracker;

import com.google.common.base.Preconditions;
import com.jordansimsmith.testcontainers.LoadedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.Properties;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public class PriceTrackerWebsiteStubContainer
    extends GenericContainer<PriceTrackerWebsiteStubContainer> {
  private static final int PRICE_TRACKER_WEBSITE_STUB_PORT = 8080;

  public PriceTrackerWebsiteStubContainer() {
    super(
        LoadedImage.loadImage(
            getProperty("test.properties", "pricetrackerwebsitestub.image.name"),
            getProperty("test.properties", "pricetrackerwebsitestub.image.loader")));

    this.withExposedPorts(PRICE_TRACKER_WEBSITE_STUB_PORT);
    this.withCommand(
        "java",
        "--add-modules",
        "jdk.httpserver",
        "-cp",
        "/opt/code/price-tracker-website-stub/price-tracker-website-stub-server_deploy.jar",
        "com.jordansimsmith.pricetracker.PriceTrackerWebsiteStubServer");
    this.waitingFor(Wait.forHttp("/health").forPort(PRICE_TRACKER_WEBSITE_STUB_PORT));
    this.withStartupTimeout(Duration.ofMinutes(1));
  }

  private static String getProperty(String propertyFileName, String key) {
    try (var input =
        PriceTrackerWebsiteStubContainer.class
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
