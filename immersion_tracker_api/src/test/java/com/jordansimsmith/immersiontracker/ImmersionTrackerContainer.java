package com.jordansimsmith.immersiontracker;

import com.google.common.base.Preconditions;
import com.jordansimsmith.testcontainers.LoadedImage;
import java.io.IOException;
import java.util.Properties;
import org.testcontainers.containers.GenericContainer;

public class ImmersionTrackerContainer extends GenericContainer<ImmersionTrackerContainer> {
  private static final int LOCALSTACK_PORT = 4566;

  public ImmersionTrackerContainer() {
    super(
        LoadedImage.loadImage(
            getProperty("immersiontracker.image.name"),
            getProperty("immersiontracker.image.loader")));

    this.withExposedPorts(LOCALSTACK_PORT);
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
