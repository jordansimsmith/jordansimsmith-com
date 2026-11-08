package com.jordansimsmith.tcginventory.reports.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.reports.client.CreateReportRequest;
import com.jordansimsmith.tcginventory.reports.client.GetReportRequest;
import com.jordansimsmith.tcginventory.reports.client.GetReportResponse;
import com.jordansimsmith.tcginventory.reports.client.ReportGeneration;
import com.jordansimsmith.tcginventory.reports.client.ReportGenerationStatus;
import com.jordansimsmith.tcginventory.reports.client.ReportPayload;
import com.jordansimsmith.tcginventory.reports.client.ReportService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ReportsHandlerTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ReportService reportService = mock(ReportService.class);
  private final TcgInventoryFactory factory = mock(TcgInventoryFactory.class);
  private CreateReportHandler createHandler;
  private GetReportsHandler getHandler;

  @BeforeEach
  void setUp() {
    when(factory.reportService()).thenReturn(reportService);
    when(factory.requestContextFactory()).thenReturn(new RequestContextFactory());
    when(factory.httpResponseFactory())
        .thenReturn(
            new HttpResponseFactory.Builder(objectMapper)
                .withAllowedOrigin("https://tcg-inventory.example")
                .build());
    createHandler = new CreateReportHandler(factory);
    getHandler = new GetReportsHandler(factory);
  }

  @Test
  void createShouldAcceptAndDelegateOnceForAuthenticatedUser() throws Exception {
    // arrange
    var event = request("alice");

    // act
    var response = createHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);
    assertThat(response.getBody()).isNull();
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8")
        .containsEntry("Access-Control-Allow-Origin", "https://tcg-inventory.example");
    verify(reportService)
        .createReport(eq(new RequestContext("alice")), eq(new CreateReportRequest()));
  }

  @Test
  void getShouldReturnServiceResponseAndMapMissingSnapshot() throws Exception {
    // arrange
    var payload = new ReportPayload(null, List.of(), List.of(), List.of());
    var generation = new ReportGeneration(ReportGenerationStatus.RUNNING, null, 1700000000L, null);
    when(reportService.getReport(any(), any()))
        .thenReturn(new GetReportResponse(1700000001L, false, generation, payload))
        .thenThrow(new NotFoundException("Not Found"));

    // act
    var response = getHandler.handleRequest(request("bob"), null);
    var body = objectMapper.readTree(response.getBody());

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(body.get("generated_at").asLong()).isEqualTo(1700000001L);
    assertThat(body.get("generation").get("status").asText()).isEqualTo("running");
    assertThat(body.get("generation").get("started_at").asLong()).isEqualTo(1700000000L);
    assertThat(body.get("generation").get("finished_at").isNull()).isTrue();
    assertThat(body.get("generation").get("error").isNull()).isTrue();
    assertThat(body.get("report").get("games").isArray()).isTrue();
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8")
        .containsEntry("Access-Control-Allow-Origin", "https://tcg-inventory.example");
    verify(reportService).getReport(eq(new RequestContext("bob")), eq(new GetReportRequest()));

    var missing = getHandler.handleRequest(request("bob"), null);
    assertThat(missing.getStatusCode()).isEqualTo(404);
    assertThat(objectMapper.readTree(missing.getBody()).get("message").asText())
        .isEqualTo("Not Found");
  }

  @Test
  void getShouldPreserveLegacyResponseWithoutGenerationMetadata() throws Exception {
    // arrange
    var payload = new ReportPayload(null, List.of(), List.of(), List.of());
    when(reportService.getReport(any(), any()))
        .thenReturn(new GetReportResponse(1700000001L, true, null, payload));

    // act
    var response = getHandler.handleRequest(request("bob"), null);
    var body = objectMapper.readTree(response.getBody());

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(body.get("generation").isNull()).isTrue();
    assertThat(body.get("generated_at").asLong()).isEqualTo(1700000001L);
  }

  @Test
  void createShouldMapCheckedServiceErrors() throws Exception {
    // arrange
    when(reportService.createReport(any(), any()))
        .thenThrow(new ServiceUnavailableException("Reports are unavailable"));

    // act
    var response = createHandler.handleRequest(request("alice"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(503);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("Reports are unavailable");
  }

  @Test
  void unexpectedFailureShouldPropagate() throws Exception {
    // arrange
    var failure = new IllegalStateException("unexpected");
    when(reportService.getReport(any(), any())).thenThrow(failure);

    // act/assert
    assertThatThrownBy(() -> getHandler.handleRequest(request("alice"), null))
        .isInstanceOf(RuntimeException.class)
        .hasCause(failure);
  }

  private APIGatewayV2HTTPEvent request(String user) {
    var credentials =
        Base64.getEncoder().encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", "Basic " + credentials))
        .build();
  }
}
