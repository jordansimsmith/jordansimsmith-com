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
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.imports.client.GetImportRequest;
import com.jordansimsmith.tcginventory.imports.client.GetImportResponse;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import com.jordansimsmith.tcginventory.imports.client.ImportStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetImportHandlerTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ImportService importService = mock(ImportService.class);
  private GetImportHandler handler;

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
    handler = new GetImportHandler(factory);
  }

  @Test
  void handleRequestShouldPassPathIdAndReturnServiceResponse() throws Exception {
    var responseBody =
        new GetImportResponse(
            "opaque/id",
            "mtg",
            null,
            ImportStatus.REVIEW,
            3,
            1_700_000_000L,
            "1.25",
            null,
            null,
            List.of());
    when(importService.getImport(any(), any())).thenReturn(responseBody);

    var response = handler.handleRequest(event("opaque/id"), null);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders().get("Content-Type"))
        .isEqualTo("application/json; charset=utf-8");
    assertThat(response.getHeaders().get("Access-Control-Allow-Origin"))
        .isEqualTo("https://tcg-inventory.jordansimsmith.com");
    var json = objectMapper.readTree(response.getBody());
    assertThat(json.get("import_id").asText()).isEqualTo("opaque/id");
    assertThat(json.get("filename").isNull()).isTrue();
    assertThat(json.has("appraisal_error")).isFalse();
    verify(importService)
        .getImport(new RequestContext("jordan"), new GetImportRequest("opaque/id"));
  }

  @Test
  void checkedServiceErrorsShouldUseSharedResponseMapping() throws Exception {
    when(importService.getImport(any(), any())).thenThrow(new NotFoundException("Not Found"));

    var response = handler.handleRequest(event("missing"), null);

    assertThat(response.getStatusCode()).isEqualTo(404);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("Not Found");
  }

  @Test
  void unexpectedFailuresShouldPropagateWithCause() throws Exception {
    var failure = new IllegalStateException("broken");
    when(importService.getImport(any(), any())).thenThrow(failure);

    assertThatThrownBy(() -> handler.handleRequest(event("import-1"), null))
        .isInstanceOf(RuntimeException.class)
        .hasCause(failure);
  }

  private static APIGatewayV2HTTPEvent event(String importId) {
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", AUTH_HEADER))
        .withPathParameters(Map.of("import_id", importId))
        .build();
  }
}
