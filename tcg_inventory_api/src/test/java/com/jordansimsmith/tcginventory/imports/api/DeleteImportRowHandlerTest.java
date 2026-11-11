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
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.imports.client.DeleteImportRowRequest;
import com.jordansimsmith.tcginventory.imports.client.DeleteImportRowResponse;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeleteImportRowHandlerTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";

  private final ImportService importService = mock(ImportService.class);
  private DeleteImportRowHandler handler;

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
    when(importService.deleteImportRow(any(), any())).thenReturn(new DeleteImportRowResponse());
    handler = new DeleteImportRowHandler(factory);
  }

  @Test
  void handleRequestShouldDelegateAuthenticatedRequestAndReturnNoContent() throws Exception {
    var response = handler.handleRequest(event("opaque/id", "7"), null);

    assertThat(response.getStatusCode()).isEqualTo(204);
    assertThat(response.getBody()).isNull();
    assertThat(response.getHeaders().get("Access-Control-Allow-Origin"))
        .isEqualTo("https://tcg-inventory.jordansimsmith.com");
    verify(importService)
        .deleteImportRow(new RequestContext("jordan"), new DeleteImportRowRequest("opaque/id", 7));
  }

  @Test
  void malformedPositionShouldReturnBadRequestWithoutDelegation() throws Exception {
    assertThat(handler.handleRequest(event("import-1", "bad"), null).getStatusCode())
        .isEqualTo(400);
    assertThat(handler.handleRequest(event("import-1", null), null).getStatusCode()).isEqualTo(400);
    verify(importService, never()).deleteImportRow(any(), any());
  }

  @Test
  void checkedServiceErrorsShouldUseSharedResponseMapping() throws Exception {
    when(importService.deleteImportRow(any(), any())).thenThrow(new NotFoundException("Not Found"));

    var response = handler.handleRequest(event("import-1", "1"), null);

    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void unexpectedFailuresShouldPropagateWithCause() throws Exception {
    var failure = new IllegalStateException("broken");
    when(importService.deleteImportRow(any(), any())).thenThrow(failure);

    assertThatThrownBy(() -> handler.handleRequest(event("import-1", "1"), null))
        .isInstanceOf(RuntimeException.class)
        .hasCause(failure);
  }

  private static APIGatewayV2HTTPEvent event(String importId, String position) {
    var pathParameters = new HashMap<String, String>();
    pathParameters.put("import_id", importId);
    if (position != null) {
      pathParameters.put("position", position);
    }
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", AUTH_HEADER))
        .withPathParameters(pathParameters)
        .build();
  }
}
