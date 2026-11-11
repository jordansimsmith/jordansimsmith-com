package com.jordansimsmith.tcginventory.imports.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.imports.client.FindImportsRequest;
import com.jordansimsmith.tcginventory.imports.client.FindImportsResponse;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import com.jordansimsmith.tcginventory.imports.client.ImportStatus;
import com.jordansimsmith.tcginventory.imports.client.ImportSummary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FindImportsHandlerTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ImportService importService = mock(ImportService.class);
  private FindImportsHandler handler;

  @BeforeEach
  void setUp() {
    var factory = mock(TcgInventoryFactory.class);
    when(factory.importService()).thenReturn(importService);
    when(factory.requestContextFactory()).thenReturn(new RequestContextFactory());
    when(factory.httpResponseFactory())
        .thenReturn(
            new HttpResponseFactory.Builder(objectMapper)
                .withAllowedOrigin("https://tcg-inventory.jordansimsmith.com")
                .build());
    handler = new FindImportsHandler(factory);
  }

  @Test
  void handleRequestShouldMapQueryAndReturnServiceResponse() throws Exception {
    var responseBody =
        new FindImportsResponse(
            List.of(
                new ImportSummary("imp-1", "mtg", null, ImportStatus.REVIEW, 3, 1_700_000_000L)),
            "next-token");
    when(importService.findImports(any(), any())).thenReturn(responseBody);

    var response =
        handler.handleRequest(event(Map.of("limit", "7", "continuation", "opaque")), null);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders().get("Content-Type"))
        .isEqualTo("application/json; charset=utf-8");
    assertThat(response.getHeaders().get("Access-Control-Allow-Origin"))
        .isEqualTo("https://tcg-inventory.jordansimsmith.com");
    var json = objectMapper.readTree(response.getBody());
    assertThat(json.get("imports").get(0).get("import_id").asText()).isEqualTo("imp-1");
    assertThat(json.get("imports").get(0).get("status").asText()).isEqualTo("review");
    assertThat(json.get("imports").get(0).get("filename").isNull()).isTrue();
    assertThat(json.get("imports").get(0).has("appraisal_error")).isFalse();
    assertThat(json.get("next_continuation").asText()).isEqualTo("next-token");
    verify(importService)
        .findImports(new RequestContext("jordan"), new FindImportsRequest(7, "opaque"));
  }

  @Test
  void absentQueryValuesShouldBePassedAsNull() throws Exception {
    when(importService.findImports(any(), any()))
        .thenReturn(new FindImportsResponse(List.of(), null));

    var response = handler.handleRequest(event(null), null);

    assertThat(response.getStatusCode()).isEqualTo(200);
    verify(importService)
        .findImports(new RequestContext("jordan"), new FindImportsRequest(null, null));
  }

  @Test
  void malformedLimitShouldReturnSafeBadRequestWithoutCallingService() throws Exception {
    var response = handler.handleRequest(event(Map.of("limit", "not-a-number")), null);

    assertThat(response.getStatusCode()).isEqualTo(400);
    assertThat(response.getBody()).contains("Invalid limit");
    verify(importService, never()).findImports(any(), any());
  }

  @Test
  void checkedServiceErrorsShouldUseSharedResponseMapping() throws Exception {
    when(importService.findImports(any(), any())).thenThrow(new NotFoundException("Not Found"));

    var response = handler.handleRequest(event(null), null);

    assertThat(response.getStatusCode()).isEqualTo(404);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("Not Found");
  }

  @Test
  void badRequestServiceErrorsShouldUseSharedResponseMapping() throws Exception {
    when(importService.findImports(any(), any()))
        .thenThrow(new BadRequestException("limit must be between 1 and 100"));

    var response = handler.handleRequest(event(null), null);

    assertThat(response.getStatusCode()).isEqualTo(400);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("limit must be between 1 and 100");
  }

  @Test
  void unexpectedFailuresShouldPropagateWithCause() throws Exception {
    var failure = new IllegalStateException("broken");
    when(importService.findImports(any(), any())).thenThrow(failure);

    assertThatThrownBy(() -> handler.handleRequest(event(null), null))
        .isInstanceOf(RuntimeException.class)
        .hasCause(failure);
  }

  private static APIGatewayV2HTTPEvent event(Map<String, String> queryParameters) {
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", AUTH_HEADER))
        .withQueryStringParameters(queryParameters)
        .build();
  }
}
