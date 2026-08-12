package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class FetchTcgSetMapping {
  public record FetchTcgSetEntry(int setId, String setName) {}

  private static final String RESOURCE_PATH = "/fetchtcg_set_mapping.json";
  private static final Map<String, List<FetchTcgSetEntry>> MAPPING = loadMapping();

  public static List<FetchTcgSetEntry> get(String scryfallSetCode) {
    return MAPPING.getOrDefault(scryfallSetCode, List.of());
  }

  public static boolean contains(String scryfallSetCode) {
    return MAPPING.containsKey(scryfallSetCode);
  }

  private static Map<String, List<FetchTcgSetEntry>> loadMapping() {
    try (InputStream input = FetchTcgSetMapping.class.getResourceAsStream(RESOURCE_PATH)) {
      if (input == null) {
        throw new IllegalStateException("resource not found: " + RESOURCE_PATH);
      }
      var objectMapper = new ObjectMapper();
      Map<String, List<Map<String, Object>>> raw =
          objectMapper.readValue(input, new TypeReference<>() {});
      return raw.entrySet().stream()
          .collect(
              Collectors.toUnmodifiableMap(
                  Map.Entry::getKey,
                  entry ->
                      entry.getValue().stream()
                          .map(
                              m ->
                                  new FetchTcgSetEntry(
                                      ((Number) m.get("set_id")).intValue(),
                                      (String) m.get("set_name")))
                          .toList()));
    } catch (IOException e) {
      throw new RuntimeException("failed to load FetchTCG set mapping", e);
    }
  }
}
