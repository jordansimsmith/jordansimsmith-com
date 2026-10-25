package com.jordansimsmith.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.ForbiddenException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.service.UnauthorizedException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class HttpResponseFactoryTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpResponseFactory httpResponseFactory =
      new HttpResponseFactory.Builder(objectMapper)
          .withAllowedOrigin("https://example.com")
          .build();

  @Test
  void serviceExceptionShouldBeCheckedAndRetainCause() {
    // arrange
    var cause = new IllegalStateException("sensitive provider response");

    // act
    var exception = new ServiceUnavailableException("service unavailable", cause);

    // assert
    assertThat(exception).isInstanceOf(Exception.class).isNotInstanceOf(RuntimeException.class);
    assertThat(exception.getMessage()).isEqualTo("service unavailable");
    assertThat(exception.getCause()).isSameAs(cause);
  }

  @Test
  void serviceErrorShouldMapExceptionsAndSubclassesToTheirStatuses() {
    // arrange
    var errorCases =
        List.of(
            new ErrorCase(new BadRequestException("bad request"), 400),
            new ErrorCase(new UnauthorizedException("unauthorized"), 401),
            new ErrorCase(new ForbiddenException("forbidden"), 403),
            new ErrorCase(new NotFoundException("not found"), 404),
            new ErrorCase(new ConflictException("conflict"), 409),
            new ErrorCase(new ServiceUnavailableException("unavailable"), 503));

    // act and assert
    for (var errorCase : errorCases) {
      var response = httpResponseFactory.serviceError(errorCase.exception());
      assertThat(response.getStatusCode()).isEqualTo(errorCase.expectedStatus());
      assertThat(response.getHeaders().get("Content-Type"))
          .isEqualTo("application/json; charset=utf-8");
      assertThat(response.getHeaders().get("Access-Control-Allow-Origin"))
          .isEqualTo("https://example.com");
      assertThat(readMessage(response.getBody())).isEqualTo(errorCase.exception().getMessage());
    }
  }

  @Test
  void serviceErrorShouldEscapeMessageAndExcludeCauseFromResponse() {
    // arrange
    var exception =
        new BadRequestException(
            "invalid \"token\"\nrequest", new IllegalStateException("sensitive provider body"));

    // act
    var response = httpResponseFactory.serviceError(exception);

    // assert
    assertThat(readMessage(response.getBody())).isEqualTo("invalid \"token\"\nrequest");
    assertThat(response.getBody()).doesNotContain("sensitive provider body");
  }

  @Test
  void existingResponseMethodsShouldRetainTheirStatuses() {
    // arrange, act, assert
    assertThat(httpResponseFactory.ok(Map.of("value", "ok")).getStatusCode()).isEqualTo(200);
    assertThat(httpResponseFactory.created(Map.of("value", "created")).getStatusCode())
        .isEqualTo(201);
    assertThat(httpResponseFactory.accepted().getStatusCode()).isEqualTo(202);
    assertThat(httpResponseFactory.accepted(Map.of("value", "accepted")).getStatusCode())
        .isEqualTo(202);
    assertThat(httpResponseFactory.noContent().getStatusCode()).isEqualTo(204);
    assertThat(httpResponseFactory.badRequest(Map.of("message", "bad request")).getStatusCode())
        .isEqualTo(400);
    assertThat(httpResponseFactory.notFound(Map.of("message", "not found")).getStatusCode())
        .isEqualTo(404);
    assertThat(httpResponseFactory.conflict(Map.of("message", "conflict")).getStatusCode())
        .isEqualTo(409);
    assertThat(
            httpResponseFactory
                .serviceUnavailable(Map.of("message", "unavailable"))
                .getStatusCode())
        .isEqualTo(503);
  }

  private String readMessage(String body) {
    try {
      return objectMapper.readTree(body).path("message").asText();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private record ErrorCase(ServiceException exception, int expectedStatus) {}
}
