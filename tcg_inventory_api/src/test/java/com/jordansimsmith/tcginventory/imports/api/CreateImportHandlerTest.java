package com.jordansimsmith.tcginventory.imports.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
import com.jordansimsmith.tcginventory.imports.client.CreateImportResponse;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import com.jordansimsmith.ulid.UlidGenerator;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreateImportHandlerTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";
  private final ImportService importService = mock(ImportService.class);
  private final UlidGenerator ulidGenerator = mock(UlidGenerator.class);
  private CreateImportHandler handler;

  @BeforeEach
  void setUp() throws Exception {
    var factory = mock(TcgInventoryFactory.class);
    when(factory.importService()).thenReturn(importService);
    when(factory.ulidGenerator()).thenReturn(ulidGenerator);
    when(factory.requestContextFactory()).thenReturn(new RequestContextFactory());
    when(factory.httpResponseFactory())
        .thenReturn(
            new HttpResponseFactory.Builder(new ObjectMapper())
                .withAllowedOrigin("https://tcg-inventory.jordansimsmith.com")
                .build());
    when(ulidGenerator.generate()).thenReturn("operation-id");
    when(importService.createImport(any(), any())).thenReturn(new CreateImportResponse("import-1"));
    handler = new CreateImportHandler(factory);
  }

  @Test
  void passesAuthenticatedContextAndOptionalFilenameAndReturnsOnlyImportId() throws Exception {
    var response =
        handler.handleRequest(
            event("mtg", "bulk.csv", "Name,Quantity\nOpt,1", "text/csv; charset=utf-8"), null);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getBody()).isEqualTo("{\"import_id\":\"import-1\"}");
    assertThat(response.getHeaders().get("Access-Control-Allow-Origin"))
        .isEqualTo("https://tcg-inventory.jordansimsmith.com");
    verify(importService)
        .createImport(
            eq(new RequestContext("jordan")),
            argThat(
                request ->
                    request.operationId().equals("operation-id")
                        && request.game().equals("mtg")
                        && request.csv().equals("Name,Quantity\nOpt,1")
                        && request.filename().equals("bulk.csv")
                        && request.rows() == null));
  }

  @Test
  void allowsMissingFilenameAndRejectsInvalidTransportWithoutDelegation() throws Exception {
    assertThat(handler.handleRequest(event("mtg", null, "csv", "text/csv"), null).getStatusCode())
        .isEqualTo(200);
    verify(importService).createImport(any(), argThat(request -> request.filename() == null));

    assertThat(handler.handleRequest(event("mtg", null, "csv", "text/plain"), null).getStatusCode())
        .isEqualTo(400);
    assertThat(handler.handleRequest(event(null, null, "csv", "text/csv"), null).getStatusCode())
        .isEqualTo(400);
    verify(importService, never())
        .createImport(any(), argThat(request -> request.csv().equals("bad")));
  }

  @Test
  void mapsCheckedServiceErrors() throws Exception {
    when(importService.createImport(any(), any()))
        .thenThrow(new BadRequestException("invalid csv"));

    assertThat(handler.handleRequest(event("mtg", null, "bad", "text/csv"), null).getStatusCode())
        .isEqualTo(400);
  }

  private static APIGatewayV2HTTPEvent event(
      String game, String filename, String body, String contentType) {
    var query = new java.util.HashMap<String, String>();
    if (game != null) {
      query.put("game", game);
    }
    if (filename != null) {
      query.put("filename", filename);
    }
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", AUTH_HEADER, "content-type", contentType))
        .withQueryStringParameters(query)
        .withBody(body)
        .build();
  }
}
