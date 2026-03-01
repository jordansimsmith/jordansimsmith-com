package com.jordansimsmith.ankibackup;

import com.jordansimsmith.localstack.LocalStackContainer;
import java.net.URI;

public class AnkiBackupContainer extends LocalStackContainer<AnkiBackupContainer> {
  public AnkiBackupContainer() {
    super("test.properties", "ankibackup.image.name", "ankibackup.image.loader");
  }

  @SuppressWarnings("HttpUrlsUsage")
  public URI getApiUrl() {
    return URI.create(
        "http://%s:%d/restapis/anki_backup/local/_user_request_"
            .formatted(this.getHost(), this.getLocalstackPort()));
  }
}
