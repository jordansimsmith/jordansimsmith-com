package com.jordansimsmith.http;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;

public class HttpResponseFactory {
  private final ObjectMapper objectMapper;
  private final Map<String, String> headers;

  private HttpResponseFactory(ObjectMapper objectMapper, Map<String, String> headers) {
    this.objectMapper = objectMapper;
    this.headers = headers;
  }

  public APIGatewayV2HTTPResponse ok(Object body) {
    return buildResponse(200, body);
  }

  public APIGatewayV2HTTPResponse created(Object body) {
    return buildResponse(201, body);
  }

  public APIGatewayV2HTTPResponse badRequest(Object body) {
    return buildResponse(400, body);
  }

  public APIGatewayV2HTTPResponse notFound(Object body) {
    return buildResponse(404, body);
  }

  private APIGatewayV2HTTPResponse buildResponse(int statusCode, Object body) {
    try {
      return APIGatewayV2HTTPResponse.builder()
          .withStatusCode(statusCode)
          .withHeaders(headers)
          .withBody(objectMapper.writeValueAsString(body))
          .build();
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize response body", e);
    }
  }

  public static class Builder {
    private final ObjectMapper objectMapper;
    private String allowedOrigin;

    public Builder(ObjectMapper objectMapper) {
      this.objectMapper = objectMapper;
    }

    public Builder withAllowedOrigin(String url) {
      this.allowedOrigin = url;
      return this;
    }

    public HttpResponseFactory build() {
      var headers = new HashMap<String, String>();
      headers.put("Content-Type", "application/json; charset=utf-8");
      if (allowedOrigin != null) {
        headers.put("Access-Control-Allow-Origin", allowedOrigin);
      }
      return new HttpResponseFactory(objectMapper, headers);
    }
  }
}
