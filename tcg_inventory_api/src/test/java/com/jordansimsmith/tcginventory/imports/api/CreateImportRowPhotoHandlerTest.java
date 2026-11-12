package com.jordansimsmith.tcginventory.imports.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.jordansimsmith.tcginventory.imports.client.CreateImportRowPhotoResponse;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreateImportRowPhotoHandlerTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";
  private final ImportService importService = mock(ImportService.class);
  private CreateImportRowPhotoHandler handler;

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
    when(importService.createImportRowPhoto(any(), any()))
        .thenReturn(new CreateImportRowPhotoResponse());
    handler = new CreateImportRowPhotoHandler(factory);
  }

  @Test
  void requestPassesAuthenticatedContextPathAndDecodedBytesAndReturnsNoContent() throws Exception {
    var bytes = new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2};
    var response = handler.handleRequest(event("opaque/id", "7", "image/jpeg", bytes), null);

    assertThat(response.getStatusCode()).isEqualTo(204);
    assertThat(response.getBody()).isNull();
    assertThat(response.getHeaders().get("Access-Control-Allow-Origin"))
        .isEqualTo("https://tcg-inventory.jordansimsmith.com");
    verify(importService)
        .createImportRowPhoto(
            eq(new RequestContext("jordan")),
            argThat(
                request ->
                    request.importId().equals("opaque/id")
                        && request.position() == 7
                        && Arrays.equals(request.photoBytes(), bytes)));
  }

  @Test
  void invalidTransportValuesReturnBadRequestWithoutDelegation() throws Exception {
    assertThat(
            handler
                .handleRequest(event("import-1", "1", "image/png", new byte[] {1}), null)
                .getStatusCode())
        .isEqualTo(400);
    assertThat(
            handler.handleRequest(event("import-1", "1", "image/jpeg", null), null).getStatusCode())
        .isEqualTo(400);
    assertThat(
            handler
                .handleRequest(event("import-1", "bad", "image/jpeg", new byte[] {1}), null)
                .getStatusCode())
        .isEqualTo(400);
    var invalidBase64 =
        APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", AUTH_HEADER, "content-type", "image/jpeg"))
            .withPathParameters(Map.of("import_id", "import-1", "position", "1"))
            .withBody("***")
            .build();
    assertThat(handler.handleRequest(invalidBase64, null).getStatusCode()).isEqualTo(400);
    verify(importService, never()).createImportRowPhoto(any(), any());
  }

  @Test
  void checkedServiceErrorsUseSharedMapping() throws Exception {
    when(importService.createImportRowPhoto(any(), any()))
        .thenThrow(new BadRequestException("invalid photo"));

    assertThat(
            handler
                .handleRequest(event("import-1", "1", "image/jpeg", new byte[] {1}), null)
                .getStatusCode())
        .isEqualTo(400);
  }

  @Test
  void unexpectedFailuresPropagateWithCause() throws Exception {
    var failure = new IllegalStateException("storage failed");
    when(importService.createImportRowPhoto(any(), any())).thenThrow(failure);

    assertThatThrownBy(
            () -> handler.handleRequest(event("import-1", "1", "image/jpeg", new byte[] {1}), null))
        .isInstanceOf(RuntimeException.class)
        .hasCause(failure);
  }

  private static APIGatewayV2HTTPEvent event(
      String importId, String position, String contentType, byte[] bytes) {
    var pathParameters = new HashMap<String, String>();
    pathParameters.put("import_id", importId);
    pathParameters.put("position", position);
    var builder =
        APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", AUTH_HEADER, "content-type", contentType))
            .withPathParameters(pathParameters);
    if (bytes != null) {
      builder.withBody(Base64.getEncoder().encodeToString(bytes));
    }
    return builder.build();
  }
}
