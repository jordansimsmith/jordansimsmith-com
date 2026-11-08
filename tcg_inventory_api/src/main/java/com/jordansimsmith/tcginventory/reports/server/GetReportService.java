package com.jordansimsmith.tcginventory.reports.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.reports.client.GetReportRequest;
import com.jordansimsmith.tcginventory.reports.client.GetReportResponse;
import com.jordansimsmith.tcginventory.reports.client.ReportGeneration;
import com.jordansimsmith.tcginventory.reports.client.ReportGenerationStatus;
import com.jordansimsmith.tcginventory.reports.client.ReportPayload;
import com.jordansimsmith.time.Clock;
import java.time.Duration;

public class GetReportService {
  private final ReportRepository reportRepository;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public GetReportService(
      ReportRepository reportRepository, ObjectMapper objectMapper, Clock clock) {
    this.reportRepository = reportRepository;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  public GetReportResponse getReport(RequestContext ctx, GetReportRequest request)
      throws ServiceException {
    var report = reportRepository.get(ctx.user());
    if (report == null) {
      throw new NotFoundException("Not Found");
    }

    var generation = readGeneration(report);
    if (report.getReport() == null) {
      throw new NotFoundException("Not Found");
    }
    if (report.getUpdatedAt() == null) {
      throw new IllegalStateException("Report snapshot timestamp is missing");
    }

    var payload = deserialize(report.getReport());
    var stale = clock.now().isAfter(report.getUpdatedAt().plus(Duration.ofHours(24)));
    return new GetReportResponse(
        report.getUpdatedAt().getEpochSecond(), stale, generation, payload);
  }

  private ReportGeneration readGeneration(ReportItem report) {
    var generationId = report.getGenerationId();
    var status = report.getGenerationStatus();
    var startedAt = report.getGenerationStartedAt();
    var finishedAt = report.getGenerationFinishedAt();
    if (generationId == null && status == null && startedAt == null && finishedAt == null) {
      return null;
    }
    if (generationId == null || generationId.isBlank() || status == null || startedAt == null) {
      throw new IllegalStateException("Report generation metadata is incomplete");
    }

    var generationStatus =
        switch (status) {
          case "queued" -> ReportGenerationStatus.QUEUED;
          case "running" -> ReportGenerationStatus.RUNNING;
          case "succeeded" -> ReportGenerationStatus.SUCCEEDED;
          default -> throw new IllegalStateException("Report generation has an unknown status");
        };
    if ((generationStatus == ReportGenerationStatus.SUCCEEDED) != (finishedAt != null)) {
      throw new IllegalStateException("Report generation metadata is incomplete");
    }

    return new ReportGeneration(
        generationStatus,
        null,
        startedAt.getEpochSecond(),
        finishedAt == null ? null : finishedAt.getEpochSecond());
  }

  private ReportPayload deserialize(String reportJson) {
    try {
      return objectMapper.readValue(reportJson, ReportPayload.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not read report snapshot", e);
    }
  }
}
