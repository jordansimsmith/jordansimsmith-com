package com.jordansimsmith.tcginventory.imports.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import com.jordansimsmith.tcginventory.imports.client.ConfirmImportRequest;
import com.jordansimsmith.tcginventory.imports.client.ConfirmImportResponse;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfirmImportHandlerTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";

  private final ImportService importService = mock(ImportService.class);
  private ConfirmImportHandler handler;

  @BeforeEach
  void setUp() throws Exception {
    var factory = mock(TcgInventoryFactory.class);
    when(factory.importService()).thenReturn(importService);
    when(factory.requestContextFactory()).thenReturn(new RequestContextFactory());
    when(factory.httpResponseFactory())
        .thenReturn(
            new HttpResponseFactory.Builder(new ObjectMapper())
                .withAllowedOrigin("https://tcg-inventory.jordansimsmith.com")
                .build());
    when(importService.confirmImport(any(), any())).thenReturn(new ConfirmImportResponse());
    handler = new ConfirmImportHandler(factory);
  }

  @Test
  void handleRequestShouldDelegateAuthenticatedRequestAndReturnAccepted() throws Exception {
    var response = handler.handleRequest(event("opaque/id"), null);

    assertThat(response.getStatusCode()).isEqualTo(202);
    assertThat(response.getBody()).isNull();
    assertThat(response.getHeaders().get("Access-Control-Allow-Origin"))
        .isEqualTo("https://tcg-inventory.jordansimsmith.com");
    verify(importService).confirmImport(new RequestContext("jordan"), new ConfirmImportRequest("opaque/id"));
  }

  @Test
  void missingPathParameterShouldBeValidatedByService() throws Exception {
    when(importService.confirmImport(any(), any())).thenThrow(new BadRequestException("import_id is required"));

    var response = handler.handleRequest(event(null), null);

    assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void checkedServiceErrorsShouldUseSharedResponseMapping() throws Exception {
    when(importService.confirmImport(any(), any())).thenThrow(new NotFoundException("Not Found"));

    var response = handler.handleRequest(event("import-1"), null);

    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void unexpectedFailuresShouldPropagateWithCause() throws Exception {
    var failure = new IllegalStateException("broken");
    when(importService.confirmImport(any(), any())).thenThrow(failure);

    assertThatThrownBy(() -> handler.handleRequest(event("import-1"), null))
        .isInstanceOf(RuntimeException.class)
        .hasCause(failure);
  }

  private static APIGatewayV2HTTPEvent event(String importId) {
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", AUTH_HEADER))
        .withPathParameters(importId == null ? null : Map.of("import_id", importId))
        .build();
  }
}
