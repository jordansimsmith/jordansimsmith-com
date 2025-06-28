package com.jordansimsmith.auctiontracker;

import com.google.common.base.Preconditions;
import com.jordansimsmith.testcontainers.LoadedImage;
import java.io.IOException;
import java.net.URI;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

public class AuctionTrackerContainer extends GenericContainer<AuctionTrackerContainer> {
  private static final Logger LOGGER = LoggerFactory.getLogger(AuctionTrackerContainer.class);

  private static final int LOCALSTACK_PORT = 4566;

  public AuctionTrackerContainer() {
    super(
        LoadedImage.loadImage(
            getProperty("auctiontracker.image.name"), getProperty("auctiontracker.image.loader")));

    this.withExposedPorts(LOCALSTACK_PORT);
    this.withFileSystemBind("/var/run/docker.sock", "/var/run/docker.sock", BindMode.READ_WRITE);
    // TODO: add the error check to all other service containers
    this.waitingFor(
        Wait.forHttp("/_localstack/init/ready")
            .forResponsePredicate(
                res ->
                    res.contains("\"completed\": true") && !res.contains("\"state\": \"ERROR\"")));
    this.withLogConsumer(new Slf4jLogConsumer(LOGGER).withSeparateOutputStreams());
  }

  @SuppressWarnings("HttpUrlsUsage")
  public URI getLocalstackUrl() {
    return URI.create("http://" + getHost() + ":" + getMappedPort(LOCALSTACK_PORT));
  }

  private static String getProperty(String key) {
    try (var input =
        AuctionTrackerContainer.class.getClassLoader().getResourceAsStream("test.properties")) {
      Preconditions.checkNotNull(input);
      var properties = new Properties();
      properties.load(input);
      return properties.getProperty(key);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
