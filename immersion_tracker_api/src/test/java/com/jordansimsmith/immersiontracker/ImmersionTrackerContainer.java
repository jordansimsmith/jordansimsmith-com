package com.jordansimsmith.immersiontracker;

import com.google.common.base.Preconditions;
import com.jordansimsmith.testcontainers.LoadedImage;
import java.io.IOException;
import java.net.URI;
import java.util.Properties;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public class ImmersionTrackerContainer extends GenericContainer<ImmersionTrackerContainer> {
  private static final int LOCALSTACK_PORT = 4566;

  public ImmersionTrackerContainer() {
    super(
        LoadedImage.loadImage(
            getProperty("immersiontracker.image.name"),
            getProperty("immersiontracker.image.loader")));

    this.withExposedPorts(LOCALSTACK_PORT);
    this.withFileSystemBind("/var/run/docker.sock", "/var/run/docker.sock", BindMode.READ_WRITE);
    this.waitingFor(
        Wait.forHttp("/_localstack/init/ready")
            .forResponsePredicate(res -> res.contains("\"completed\": true")));
    this.withEnv("TVDB_API_KEY", System.getenv("TVDB_API_KEY"));
  }

  @SuppressWarnings("HttpUrlsUsage")
  public URI getApiUrl() {
    return URI.create(
        "http://%s:%d/restapis/immersion_tracker/local/_user_request_"
            .formatted(this.getHost(), this.getMappedPort(LOCALSTACK_PORT)));
  }

  private static String getProperty(String key) {
    try (var input =
        ImmersionTrackerContainer.class.getClassLoader().getResourceAsStream("test.properties")) {
      Preconditions.checkNotNull(input);
      var properties = new Properties();
      properties.load(input);
      return properties.getProperty(key);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
