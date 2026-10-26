package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jordansimsmith.secrets.Secrets;
import java.io.IOException;

public class FetchTcgCredentialStore {
  private static final String SECRET_NAME = "tcg_inventory";

  private final Secrets secrets;
  private final ObjectMapper objectMapper;

  public FetchTcgCredentialStore(Secrets secrets, ObjectMapper objectMapper) {
    this.secrets = secrets;
    this.objectMapper = objectMapper;
  }

  public String getRefreshToken(String user) {
    var secretNode = readSecret();
    var tokenNode = secretNode.get(user);
    if (tokenNode == null) {
      return null;
    }
    if (!tokenNode.isTextual() || tokenNode.asText().isBlank()) {
      throw new IllegalStateException("FetchTCG credential storage is malformed");
    }
    return tokenNode.asText();
  }

  public void updateRefreshToken(String user, String refreshToken) {
    var secretNode = readSecret();
    secretNode.put(user, refreshToken);
    String updatedSecret;
    try {
      updatedSecret = objectMapper.writeValueAsString(secretNode);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to save FetchTCG credentials");
    }
    try {
      secrets.put(SECRET_NAME, updatedSecret);
    } catch (RuntimeException e) {
      throw new IllegalStateException("Failed to save FetchTCG credentials");
    }
  }

  private ObjectNode readSecret() {
    String existingSecret;
    try {
      existingSecret = secrets.get(SECRET_NAME);
    } catch (RuntimeException e) {
      throw new IllegalStateException("Failed to read FetchTCG credentials");
    }
    if (existingSecret == null) {
      throw new IllegalStateException("FetchTCG credential storage is malformed");
    }
    try {
      var parsedSecret = objectMapper.readTree(existingSecret);
      if (parsedSecret == null || !parsedSecret.isObject()) {
        throw new IllegalStateException("FetchTCG credential storage is malformed");
      }
      return (ObjectNode) parsedSecret;
    } catch (IOException e) {
      throw new IllegalStateException("FetchTCG credential storage is malformed");
    }
  }
}
