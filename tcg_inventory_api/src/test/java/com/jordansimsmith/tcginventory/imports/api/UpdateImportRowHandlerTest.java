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
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import com.jordansimsmith.tcginventory.imports.client.UpdateImportRowRequest;
import com.jordansimsmith.tcginventory.imports.client.UpdateImportRowResponse;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UpdateImportRowHandlerTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ImportService importService = mock(ImportService.class);
  private UpdateImportRowHandler handler;

  @BeforeEach
  void setUp() throws Exception {
    var factory = mock(TcgInventoryFactory.class);
    when(factory.importService()).thenReturn(importService);
    when(factory.requestContextFactory()).thenReturn(new RequestContextFactory());
    when(factory.httpResponseFactory())
        .thenReturn(
            new HttpResponseFactory.Builder(objectMapper)
                .withAllowedOrigin("https://tcg-inventory.jordansimsmith.com")
                .build());
    when(factory.objectMapper()).thenReturn(objectMapper);
    when(importService.updateImportRow(any(), any())).thenReturn(new UpdateImportRowResponse());
    handler = new UpdateImportRowHandler(factory);
  }

  @Test
  void handleRequestShouldUseAuthenticatedContextAndPathValuesAndReturnNoContent()
      throws Exception {
    var response = handler.handleRequest(event("opaque/id", "3", "{\"condition\":\"LP\"}"), null);

    assertThat(response.getStatusCode()).isEqualTo(204);
    assertThat(response.getBody()).isNull();
    assertThat(response.getHeaders().get("Access-Control-Allow-Origin"))
        .isEqualTo("https://tcg-inventory.jordansimsmith.com");
    verify(importService)
        .updateImportRow(
            new RequestContext("jordan"), new UpdateImportRowRequest("opaque/id", 3, Condition.LP));
  }

  @Test
  void malformedPositionBodyAndConditionShouldReturnBadRequestWithoutDelegation() throws Exception {
    var malformedPosition =
        handler.handleRequest(event("imp-1", "bad", "{\"condition\":\"LP\"}"), null);
    var malformedBody = handler.handleRequest(event("imp-1", "1", "{"), null);
    var nullBody = handler.handleRequest(event("imp-1", "1", null), null);
    var missingCondition = handler.handleRequest(event("imp-1", "1", "{}"), null);
    var invalidCondition =
        handler.handleRequest(event("imp-1", "1", "{\"condition\":\"bad\"}"), null);

    assertThat(malformedPosition.getStatusCode()).isEqualTo(400);
    assertThat(malformedBody.getStatusCode()).isEqualTo(400);
    assertThat(nullBody.getStatusCode()).isEqualTo(400);
    assertThat(missingCondition.getStatusCode()).isEqualTo(400);
    assertThat(invalidCondition.getStatusCode()).isEqualTo(400);
    verify(importService, never()).updateImportRow(any(), any());
  }

  @Test
  void checkedServiceErrorsShouldUseSharedResponseMapping() throws Exception {
    when(importService.updateImportRow(any(), any()))
        .thenThrow(new BadRequestException("invalid condition"));

    var response = handler.handleRequest(event("imp-1", "1", "{\"condition\":\"LP\"}"), null);

    assertThat(response.getStatusCode()).isEqualTo(400);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("invalid condition");
  }

  @Test
  void unexpectedFailuresShouldPropagateWithCause() throws Exception {
    var failure = new IllegalStateException("broken");
    when(importService.updateImportRow(any(), any())).thenThrow(failure);

    assertThatThrownBy(
            () -> handler.handleRequest(event("imp-1", "1", "{\"condition\":\"LP\"}"), null))
        .isInstanceOf(RuntimeException.class)
        .hasCause(failure);
  }

  private static APIGatewayV2HTTPEvent event(String importId, String position, String body) {
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", AUTH_HEADER))
        .withPathParameters(Map.of("import_id", importId, "position", position))
        .withBody(body)
        .build();
  }
}
