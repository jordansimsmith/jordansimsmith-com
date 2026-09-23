package com.jordansimsmith.localstack;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.google.common.base.Preconditions;
import com.jordansimsmith.testcontainers.LoadedImage;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Properties;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

public abstract class LocalStackContainer<T extends LocalStackContainer<T>>
    extends GenericContainer<T> {
  private static final Logger LOGGER = LoggerFactory.getLogger(LocalStackContainer.class);

  private static final String CONTAINER_DOCKER_SOCKET = "/var/run/docker.sock";
  private static final int LOCALSTACK_PORT = 4566;
  private static final String STARTER_SCRIPT = "/testcontainers_start.sh";

  protected LocalStackContainer(
      String propertyFileName, String imageNameProperty, String imageLoaderProperty) {
    super(
        LoadedImage.loadImage(
            getProperty(propertyFileName, imageNameProperty),
            getProperty(propertyFileName, imageLoaderProperty)));

    this.withExposedPorts(LOCALSTACK_PORT);
    this.withFileSystemBind(CONTAINER_DOCKER_SOCKET, CONTAINER_DOCKER_SOCKET, BindMode.READ_WRITE);
    this.withPrivilegedMode(true);
    this.withEnv("DOCKER_HOST", "unix://" + CONTAINER_DOCKER_SOCKET);
    this.withEnv("DOCKER_SOCK", CONTAINER_DOCKER_SOCKET);
    this.withEnv("AWS_ACCESS_KEY_ID", "fake");
    this.withEnv("AWS_SECRET_ACCESS_KEY", "fake");
    this.withEnv("AWS_DEFAULT_REGION", "ap-southeast-2");
    this.withEnv("AWS_REGION", "ap-southeast-2");
    this.withCreateContainerCmdModifier(
        cmd ->
            cmd.withEntrypoint(
                "sh",
                "-c",
                "while [ ! -f " + STARTER_SCRIPT + " ]; do sleep 0.1; done; " + STARTER_SCRIPT));
    this.waitingFor(
        Wait.forHttp("/_localstack/init/ready")
            .forResponsePredicate(
                res ->
                    res.contains("\"completed\": true") && !res.contains("\"state\": \"ERROR\"")));
    this.withStartupTimeout(Duration.ofMinutes(2));
    this.withLogConsumer(new Slf4jLogConsumer(LOGGER).withSeparateOutputStreams());
  }

  @Override
  protected void containerIsStarting(InspectContainerResponse containerInfo) {
    var command = "#!/bin/bash\n";
    command +=
        "export LAMBDA_DOCKER_FLAGS="
            + configureServiceContainerLabels("LAMBDA_DOCKER_FLAGS")
            + "\n";
    command +=
        "export ECS_DOCKER_FLAGS=" + configureServiceContainerLabels("ECS_DOCKER_FLAGS") + "\n";
    command +=
        "export EC2_DOCKER_FLAGS=" + configureServiceContainerLabels("EC2_DOCKER_FLAGS") + "\n";
    command +=
        "export BATCH_DOCKER_FLAGS=" + configureServiceContainerLabels("BATCH_DOCKER_FLAGS") + "\n";
    command += "/usr/local/bin/docker-entrypoint.sh\n";
    copyFileToContainer(Transferable.of(command, 0777), STARTER_SCRIPT);
  }

  private String configureServiceContainerLabels(String existingEnvFlagKey) {
    var internalMarkerFlags = internalMarkerLabels();
    var existingFlags = getEnvMap().get(existingEnvFlagKey);
    if (existingFlags != null) {
      internalMarkerFlags = existingFlags + " " + internalMarkerFlags;
    }
    return "\"" + internalMarkerFlags + "\"";
  }

  private String internalMarkerLabels() {
    return getContainerInfo().getConfig().getLabels().entrySet().stream()
        .filter(entry -> entry.getKey().startsWith(DockerClientFactory.TESTCONTAINERS_LABEL))
        .filter(
            entry ->
                !entry.getKey().equals("org.testcontainers.hash")
                    && !entry.getKey().equals("org.testcontainers.copied_files.hash"))
        .map(entry -> String.format("-l %s=%s", entry.getKey(), entry.getValue()))
        .collect(Collectors.joining(" "));
  }

  @SuppressWarnings("HttpUrlsUsage")
  public URI getLocalstackUrl() {
    return URI.create("http://" + getHost() + ":" + getMappedPort(LOCALSTACK_PORT));
  }

  protected int getLocalstackPort() {
    return getMappedPort(LOCALSTACK_PORT);
  }

  protected static String getProperty(String propertyFileName, String key) {
    try (var input =
        LocalStackContainer.class.getClassLoader().getResourceAsStream(propertyFileName)) {
      Preconditions.checkNotNull(input);
      var properties = new Properties();
      properties.load(input);
      return properties.getProperty(key);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
