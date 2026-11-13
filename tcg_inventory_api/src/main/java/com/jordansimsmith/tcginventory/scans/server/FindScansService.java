package com.jordansimsmith.tcginventory.scans.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.Continuations;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.scans.client.FindScansRequest;
import com.jordansimsmith.tcginventory.scans.client.FindScansResponse;
import com.jordansimsmith.tcginventory.scans.client.ScanStatus;
import com.jordansimsmith.tcginventory.scans.client.ScanSummary;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class FindScansService {
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;

  private final ScanReadRepository scanRepository;
  private final ObjectMapper objectMapper;

  public FindScansService(ScanReadRepository scanRepository, ObjectMapper objectMapper) {
    this.scanRepository = scanRepository;
    this.objectMapper = objectMapper;
  }

  public FindScansResponse findScans(RequestContext ctx, FindScansRequest request)
      throws ServiceException {
    var limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
    if (limit <= 0) {
      throw new BadRequestException("limit must be positive");
    }
    if (limit > MAX_LIMIT) {
      throw new BadRequestException("limit must not exceed 100");
    }
    var startKey = decodeContinuation(request.continuation());
    var page = scanRepository.findScans(ctx.user(), limit, startKey);
    var scans = page.items().stream().map(FindScansService::toSummary).toList();
    return new FindScansResponse(
        scans, Continuations.encode(page.lastEvaluatedKey(), objectMapper));
  }

  @Nullable
  private Map<String, AttributeValue> decodeContinuation(@Nullable String continuation)
      throws BadRequestException {
    if (continuation == null || continuation.isEmpty()) {
      return null;
    }
    try {
      return Continuations.decode(continuation, objectMapper);
    } catch (RuntimeException e) {
      throw new BadRequestException("Invalid continuation", e);
    }
  }

  public static ScanSummary toSummary(ScanItem item) {
    return new ScanSummary(
        item.getScanId(),
        item.getGame(),
        ScanStatus.valueOf(item.getStatus().toUpperCase(Locale.ROOT)),
        item.getCondition(),
        item.getFinish(),
        item.getRowCount() == null ? 0 : item.getRowCount(),
        item.getImportId(),
        item.getCreatedAt() == null ? 0 : item.getCreatedAt().getEpochSecond());
  }
}
