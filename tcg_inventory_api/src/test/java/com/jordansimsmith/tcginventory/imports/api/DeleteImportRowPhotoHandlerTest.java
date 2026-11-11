package com.jordansimsmith.tcginventory.imports.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
import com.jordansimsmith.tcginventory.imports.client.DeleteImportRowPhotoResponse;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeleteImportRowPhotoHandlerTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";
  private final ImportService importService = mock(ImportService.class);
  private DeleteImportRowPhotoHandler handler;

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
    when(importService.deleteImportRowPhoto(any(), any()))
        .thenReturn(new DeleteImportRowPhotoResponse());
    handler = new DeleteImportRowPhotoHandler(factory);
  }

  @Test
  void requestDelegatesOpaquePathValuesAndReturnsNoContent() throws Exception {
    var response = handler.handleRequest(event("opaque/id", "7", "photo/id"), null);

    assertThat(response.getStatusCode()).isEqualTo(204);
    assertThat(response.getBody()).isNull();
    assertThat(response.getHeaders().get("Access-Control-Allow-Origin"))
        .isEqualTo("https://tcg-inventory.jordansimsmith.com");
    verify(importService)
        .deleteImportRowPhoto(
            new RequestContext("jordan"),
            argThat(
                request ->
                    request.importId().equals("opaque/id")
                        && request.position() == 7
                        && request.photoId().equals("photo/id")));
  }

  @Test
  void malformedPositionReturnsBadRequestWithoutDelegation() {
    assertThat(handler.handleRequest(event("import-1", "bad", "photo-1"), null).getStatusCode())
        .isEqualTo(400);
    assertThat(handler.handleRequest(event("import-1", null, "photo-1"), null).getStatusCode())
        .isEqualTo(400);
    verify(importService, never()).deleteImportRowPhoto(any(), any());
  }

  @Test
  void checkedServiceErrorsUseSharedMapping() throws Exception {
    when(importService.deleteImportRowPhoto(any(), any()))
        .thenThrow(new BadRequestException("photo_id is required"));

    assertThat(
            handler.handleRequest(event("import-1", "1", " "), null).getStatusCode())
        .isEqualTo(400);
  }

  @Test
  void unexpectedFailuresPropagateWithCause() throws Exception {
    var failure = new IllegalStateException("storage failed");
    when(importService.deleteImportRowPhoto(any(), any())).thenThrow(failure);

    assertThatThrownBy(() -> handler.handleRequest(event("import-1", "1", "photo-1"), null))
        .isInstanceOf(RuntimeException.class)
        .hasCause(failure);
  }

  private static APIGatewayV2HTTPEvent event(
      String importId, String position, String photoId) {
    var pathParameters = new java.util.HashMap<String, String>();
    pathParameters.put("import_id", importId);
    pathParameters.put("position", position);
    pathParameters.put("photo_id", photoId);
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", AUTH_HEADER))
        .withPathParameters(pathParameters)
        .build();
  }
}
