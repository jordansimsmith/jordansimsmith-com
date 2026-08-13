package com.jordansimsmith.dynamodb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class Continuations {
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  public static String encode(
      Map<String, AttributeValue> lastEvaluatedKey, ObjectMapper objectMapper) {
    if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
      return null;
    }

    try {
      var serializable = new LinkedHashMap<String, Map<String, String>>();
      for (var entry : lastEvaluatedKey.entrySet()) {
        var attr = entry.getValue();
        var typed = new LinkedHashMap<String, String>();
        if (attr.s() != null) {
          typed.put("S", attr.s());
        } else if (attr.n() != null) {
          typed.put("N", attr.n());
        }
        serializable.put(entry.getKey(), typed);
      }
      var json = objectMapper.writeValueAsBytes(serializable);
      return ENCODER.encodeToString(json);
    } catch (Exception e) {
      throw new RuntimeException("failed to encode continuation token", e);
    }
  }

  public static Map<String, AttributeValue> decode(String continuation, ObjectMapper objectMapper) {
    if (continuation == null || continuation.isEmpty()) {
      return null;
    }

    try {
      var json = DECODER.decode(continuation);
      var typeRef = new TypeReference<Map<String, Map<String, String>>>() {};
      Map<String, Map<String, String>> parsed = objectMapper.readValue(json, typeRef);

      var result = new HashMap<String, AttributeValue>();
      for (var entry : parsed.entrySet()) {
        var typed = entry.getValue();
        if (typed.containsKey("S")) {
          result.put(entry.getKey(), AttributeValue.builder().s(typed.get("S")).build());
        } else if (typed.containsKey("N")) {
          result.put(entry.getKey(), AttributeValue.builder().n(typed.get("N")).build());
        }
      }
      return result;
    } catch (Exception e) {
      throw new RuntimeException("failed to decode continuation token", e);
    }
  }
}
