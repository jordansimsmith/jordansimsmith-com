package com.jordansimsmith.tcginventory.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class TcgCsvCatalogArtifactCodec {
  private final ObjectMapper objectMapper;

  public TcgCsvCatalogArtifactCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public byte[] encodeJson(TcgCsvCatalogSnapshot snapshot) throws IOException {
    return objectMapper
        .writer()
        .without(SerializationFeature.INDENT_OUTPUT)
        .writeValueAsBytes(snapshot);
  }

  public byte[] encodeGzip(TcgCsvCatalogSnapshot snapshot) throws IOException {
    var output = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(output)) {
      gzip.write(encodeJson(snapshot));
    }
    return output.toByteArray();
  }

  public TcgCsvCatalogSnapshot decodeGzip(byte[] gzipBytes) throws IOException {
    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(gzipBytes))) {
      return objectMapper.readValue(gzip, TcgCsvCatalogSnapshot.class);
    }
  }
}
