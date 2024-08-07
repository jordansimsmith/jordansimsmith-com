package com.jordansimsmith.testcontainers;

import com.google.common.base.Preconditions;
import java.io.IOException;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.TestcontainersConfiguration;

public class LoadedImage {
  public static DockerImageName loadImage(String imageKey, String loaderKey) {
    var image =
        TestcontainersConfiguration.getInstance().getClasspathProperties().getProperty(imageKey);
    var loader =
        TestcontainersConfiguration.getInstance().getClasspathProperties().getProperty(loaderKey);

    Preconditions.checkNotNull(image);
    Preconditions.checkNotNull(loader);

    try {
      var builder = new ProcessBuilder(loader);
      builder.redirectErrorStream(true);
      var process = builder.start();
      var code = process.waitFor();
      if (code != 0) {
        throw new RuntimeException("failed to load image:" + image);
      }
    } catch (IOException | InterruptedException e) {
      throw new RuntimeException(e);
    }

    return DockerImageName.parse(image);
  }
}