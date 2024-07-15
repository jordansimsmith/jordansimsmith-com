package com.jordansimsmith.testcontainers;

import com.google.common.base.Preconditions;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.ImageNameSubstitutor;
import org.testcontainers.utility.TestcontainersConfiguration;

public class LoadedImageNameSubstitutor extends ImageNameSubstitutor {

  @Override
  public DockerImageName apply(DockerImageName original) {
    if (original.toString().equals("testcontainers/ryuk:0.7.0")) {
      return LoadedImage.loadImage("ryuk.image.name", "ryuk.image.loader");
    }

    var loadedPrefix =
        TestcontainersConfiguration.getInstance()
            .getClasspathProperties()
            .getProperty("loaded.image.prefix");
    Preconditions.checkNotNull(loadedPrefix);
    if (original.toString().startsWith(loadedPrefix)) {
      return original;
    }

    throw new IllegalArgumentException(
        "All images must be loaded, refusing to resolve image:" + original);
  }

  @Override
  protected String getDescription() {
    return this.getClass().toString();
  }
}
