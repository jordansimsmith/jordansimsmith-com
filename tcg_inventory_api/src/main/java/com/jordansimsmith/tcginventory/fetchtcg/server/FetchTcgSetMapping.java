package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class FetchTcgSetMapping {
  public record FetchTcgSetEntry(int setId, String setName) {}

  private static final String RESOURCE_DIRECTORY =
      "/com/jordansimsmith/tcginventory/fetchtcg/server/";
  private static final Map<String, Map<String, List<FetchTcgSetEntry>>> MAPPINGS =
      Map.of(
          "mtg", loadMapping("fetchtcg_magic_the_gathering_set_mapping.json"),
          "poke", loadMapping("fetchtcg_pokemon_set_mapping.json"));

  public static List<FetchTcgSetEntry> get(String gameId, String externalSetId) {
    var code = "poke".equals(gameId) ? externalSetId.toLowerCase(Locale.ROOT) : externalSetId;
    return MAPPINGS.get(gameId).getOrDefault(code, List.of());
  }

  private static Map<String, List<FetchTcgSetEntry>> loadMapping(String filename) {
    var path = RESOURCE_DIRECTORY + filename;
    try (InputStream input = FetchTcgSetMapping.class.getResourceAsStream(path)) {
      if (input == null) {
        throw new IllegalStateException("resource not found: " + path);
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
      throw new RuntimeException("failed to load FetchTCG set mapping: " + path, e);
    }
  }
}
