package com.jordansimsmith.http;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

public class RequestContextFactory {

  public RequestContext createCtx(APIGatewayV2HTTPEvent event) {
    var headers = event.getHeaders();
    var token = getHeaderCaseInsensitive(headers, "Authorization");

    if (token == null || token.isBlank()) {
      throw new IllegalStateException("Missing Authorization header");
    }

    var base64 = token.substring("Basic".length()).trim();
    var bytes = Base64.getDecoder().decode(base64);
    var credentials = new String(bytes, StandardCharsets.UTF_8).split(":", 2);
    var user = credentials[0];

    return new RequestContext(user);
  }

  private String getHeaderCaseInsensitive(Map<String, String> headers, String headerName) {
    if (headers == null) {
      return null;
    }

    for (var entry : headers.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(headerName)) {
        return entry.getValue();
      }
    }

    return null;
  }
}
