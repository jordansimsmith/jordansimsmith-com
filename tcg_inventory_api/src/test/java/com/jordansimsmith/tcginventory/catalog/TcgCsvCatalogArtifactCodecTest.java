package com.jordansimsmith.tcginventory.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

public class TcgCsvCatalogArtifactCodecTest {
  private final TcgCsvCatalogArtifactCodec codec =
      new TcgCsvCatalogArtifactCodec(new ObjectMapper());

  @Test
  void decodeGzipShouldRoundTripSnapshot() throws Exception {
    // arrange
    var snapshot = snapshot();

    // act
    var decoded = codec.decodeGzip(codec.encodeGzip(snapshot));

    // assert
    assertThat(decoded).isEqualTo(snapshot);
  }

  @Test
  void decodeGzipShouldPropagateMalformedGzip() {
    // arrange
    var malformedGzip = new byte[] {1, 2, 3};

    // act/assert
    assertThatThrownBy(() -> codec.decodeGzip(malformedGzip)).isInstanceOf(IOException.class);
  }

  @Test
  void decodeGzipShouldPropagateMalformedJson() throws Exception {
    // arrange
    var malformedJson = gzip("{\"schema_version\":");

    // act/assert
    assertThatThrownBy(() -> codec.decodeGzip(malformedJson)).isInstanceOf(IOException.class);
  }

  private static byte[] gzip(String value) throws IOException {
    var output = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(output)) {
      gzip.write(value.getBytes(StandardCharsets.UTF_8));
    }
    return output.toByteArray();
  }

  private static TcgCsvCatalogSnapshot snapshot() {
    return new TcgCsvCatalogSnapshot(
        1,
        "pokemon",
        "tcgplayer",
        "tcgcsv",
        3,
        "2026-10-07T20:06:09Z",
        "2026-10-07T20:06:09+0000",
        Instant.parse("2026-10-07T20:06:09Z").getEpochSecond(),
        Instant.parse("2026-10-07T20:06:40Z").getEpochSecond(),
        List.of(new TcgCsvCatalogSnapshot.Group(1, "BASE", "Base Set")),
        List.of(
            new TcgCsvCatalogSnapshot.Product(
                25, "Pikachu", 1, "58", List.of("normal", "holofoil"))));
  }
}
