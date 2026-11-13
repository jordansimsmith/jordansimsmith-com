package com.jordansimsmith.tcginventory.scans.api;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.jordansimsmith.tcginventory.scans.client.FindScansRequest;
import com.jordansimsmith.tcginventory.scans.client.FindScansResponse;
import com.jordansimsmith.tcginventory.scans.client.GetScanRequest;
import com.jordansimsmith.tcginventory.scans.client.GetScanResponse;
import com.jordansimsmith.tcginventory.scans.client.ScanRow;
import com.jordansimsmith.tcginventory.scans.client.ScanRowStatus;
import com.jordansimsmith.tcginventory.scans.client.ScanService;
import com.jordansimsmith.tcginventory.scans.client.ScanStatus;
import com.jordansimsmith.tcginventory.scans.client.ScanSuggestion;
import com.jordansimsmith.tcginventory.scans.client.ScanSummary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScanHandlersTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ScanService scanService = mock(ScanService.class);
  private FindScansHandler findScansHandler;
  private GetScanHandler getScanHandler;

  @BeforeEach
  void setUp() {
    var factory = mock(TcgInventoryFactory.class);
    when(factory.scanService()).thenReturn(scanService);
    when(factory.requestContextFactory()).thenReturn(new RequestContextFactory());
    when(factory.httpResponseFactory())
        .thenReturn(
            new HttpResponseFactory.Builder(objectMapper)
                .withAllowedOrigin("https://tcg-inventory.jordansimsmith.com")
                .build());
    findScansHandler = new FindScansHandler(factory);
    getScanHandler = new GetScanHandler(factory);
  }

  @Test
  void findScansShouldReturnServiceResponseAndMapRequest() throws Exception {
    // arrange
    var responseBody =
        new FindScansResponse(
            List.of(
                new ScanSummary(
                    "scan-1", "mtg", ScanStatus.REVIEWING, "NM", "normal", 2, null, 1_700_000_000)),
            "next-token");
    when(scanService.findScans(any(), any())).thenReturn(responseBody);

    // act
    var response =
        findScansHandler.handleRequest(
            event(Map.of("limit", "7", "continuation", "opaque"), Map.of()), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getBody()).isEqualTo(objectMapper.writeValueAsString(responseBody));
    var json = objectMapper.readTree(response.getBody());
    assertThat(json.get("scans").get(0).get("scan_id").asText()).isEqualTo("scan-1");
    assertThat(json.get("scans").get(0).get("status").asText()).isEqualTo("reviewing");
    assertThat(json.get("next_continuation").asText()).isEqualTo("next-token");
    verify(scanService).findScans(new RequestContext("jordan"), new FindScansRequest(7, "opaque"));
  }

  @Test
  void findScansShouldMapLimitErrorsFromService() throws Exception {
    // arrange
    when(scanService.findScans(any(), any()))
        .thenThrow(new BadRequestException("limit must not exceed 100"));

    // act
    var response = findScansHandler.handleRequest(event(Map.of("limit", "101"), Map.of()), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("limit must not exceed 100");
    verify(scanService).findScans(new RequestContext("jordan"), new FindScansRequest(101, null));
  }

  @Test
  void getScanShouldReturnServiceResponseAndMapRequest() throws Exception {
    // arrange
    var responseBody =
        new GetScanResponse(
            "scan-1",
            "mtg",
            ScanStatus.REVIEWING,
            "NM",
            "normal",
            1,
            null,
            1_700_000_000,
            List.of(
                new ScanRow(
                    1,
                    "card.jpg",
                    5,
                    true,
                    ScanRowStatus.SUGGESTED,
                    false,
                    List.of(new ScanSuggestion("printing-1", "Card name", 0.9)),
                    "https://example.com/card.jpg")));
    when(scanService.getScan(any(), any())).thenReturn(responseBody);

    // act
    var response = getScanHandler.handleRequest(event(Map.of(), Map.of("scan_id", "scan-1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getBody()).isEqualTo(objectMapper.writeValueAsString(responseBody));
    var json = objectMapper.readTree(response.getBody());
    assertThat(json.get("scan_id").asText()).isEqualTo("scan-1");
    assertThat(json.get("rows").get(0).get("scan_position").asInt()).isEqualTo(1);
    assertThat(json.get("rows").get(0).get("status").asText()).isEqualTo("suggested");
    assertThat(json.get("rows").get(0).get("suggestions").get(0).get("external_id").asText())
        .isEqualTo("printing-1");
    verify(scanService).getScan(new RequestContext("jordan"), new GetScanRequest("scan-1"));
  }

  @Test
  void getScanShouldMapNotFoundErrorsFromService() throws Exception {
    // arrange
    when(scanService.getScan(any(), any())).thenThrow(new NotFoundException("Not Found"));

    // act
    var response =
        getScanHandler.handleRequest(event(Map.of(), Map.of("scan_id", "missing")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("Not Found");
  }

  private static APIGatewayV2HTTPEvent event(
      Map<String, String> queryParameters, Map<String, String> pathParameters) {
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", AUTH_HEADER))
        .withQueryStringParameters(queryParameters)
        .withPathParameters(pathParameters)
        .build();
  }
}
