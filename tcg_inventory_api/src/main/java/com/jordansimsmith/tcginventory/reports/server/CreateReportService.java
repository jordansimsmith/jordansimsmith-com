package com.jordansimsmith.tcginventory.reports.server;

import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.reports.client.CreateReportRequest;
import com.jordansimsmith.tcginventory.reports.client.CreateReportResponse;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;

public class CreateReportService {
  private final ReportRepository reportRepository;
  private final QueueClient<ReportMessage> reportsQueue;
  private final Clock clock;
  private final UlidGenerator ulidGenerator;

  public CreateReportService(
      ReportRepository reportRepository,
      QueueClient<ReportMessage> reportsQueue,
      Clock clock,
      UlidGenerator ulidGenerator) {
    this.reportRepository = reportRepository;
    this.reportsQueue = reportsQueue;
    this.clock = clock;
    this.ulidGenerator = ulidGenerator;
  }

  public CreateReportResponse createReport(RequestContext ctx, CreateReportRequest request)
      throws ServiceException {
    var report = reportRepository.get(ctx.user());
    if (hasActiveGeneration(report)) {
      return new CreateReportResponse();
    }

    var generationId = ulidGenerator.generate();
    reportRepository.createGeneration(ctx.user(), generationId, clock.now());
    reportsQueue.send(new ReportMessage(ctx.user(), generationId), generationId, generationId);
    return new CreateReportResponse();
  }

  private boolean hasActiveGeneration(ReportItem report) {
    if (report == null) {
      return false;
    }

    var generationId = report.getGenerationId();
    var status = report.getGenerationStatus();
    var startedAt = report.getGenerationStartedAt();
    var finishedAt = report.getGenerationFinishedAt();
    if (generationId == null && status == null && startedAt == null && finishedAt == null) {
      return false;
    }
    if (generationId == null || generationId.isBlank() || status == null || startedAt == null) {
      throw new IllegalStateException("Report generation metadata is incomplete");
    }

    return switch (status) {
      case "queued", "running" -> {
        if (finishedAt != null) {
          throw new IllegalStateException("Active report generation metadata is incomplete");
        }
        yield true;
      }
      case "succeeded" -> {
        if (finishedAt == null) {
          throw new IllegalStateException("Completed report generation metadata is incomplete");
        }
        yield false;
      }
      default -> throw new IllegalStateException("Report generation has an unknown status");
    };
  }
}
