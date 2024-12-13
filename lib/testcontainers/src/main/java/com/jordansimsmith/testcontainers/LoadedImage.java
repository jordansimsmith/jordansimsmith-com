package com.jordansimsmith.testcontainers;

import com.google.common.base.Preconditions;
import java.io.IOException;
import org.testcontainers.utility.DockerImageName;

public class LoadedImage {
  public static DockerImageName loadImage(String image, String loader) {
    Preconditions.checkNotNull(image);
    Preconditions.checkNotNull(loader);

    try {
      var builder = new ProcessBuilder(loader);
      builder.redirectErrorStream(true);
      var process = builder.start();
      var code = process.waitFor();
      if (code != 0) {
        var error = new String(process.getInputStream().readAllBytes());
        throw new RuntimeException("failed to load image: " + image + " with error: " + error);
      }
    } catch (IOException | InterruptedException e) {
      throw new RuntimeException(e);
    }

    return DockerImageName.parse(image);
  }
}
