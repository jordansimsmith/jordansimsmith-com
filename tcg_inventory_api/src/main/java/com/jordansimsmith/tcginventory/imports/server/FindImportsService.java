package com.jordansimsmith.tcginventory.imports.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.Continuations;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.imports.client.FindImportsRequest;
import com.jordansimsmith.tcginventory.imports.client.FindImportsResponse;
import com.jordansimsmith.tcginventory.imports.client.ImportStatus;
import com.jordansimsmith.tcginventory.imports.client.ImportSummary;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class FindImportsService {
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;

  private final ImportReadRepository importReadRepository;
  private final ObjectMapper objectMapper;

  public FindImportsService(ImportReadRepository importReadRepository, ObjectMapper objectMapper) {
    this.importReadRepository = importReadRepository;
    this.objectMapper = objectMapper;
  }

  public FindImportsResponse findImports(RequestContext ctx, FindImportsRequest request)
      throws ServiceException {
    var limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new BadRequestException("limit must be between 1 and 100");
    }

    var exclusiveStartKey = decodeContinuation(request.continuation());
    var page = importReadRepository.findImports(ctx.user(), limit, exclusiveStartKey);
    var summaries = new ArrayList<ImportSummary>();
    for (var item : page.items()) {
      summaries.add(toSummary(item));
    }
    return new FindImportsResponse(
        summaries, Continuations.encode(page.lastEvaluatedKey(), objectMapper));
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

  private static ImportSummary toSummary(ImportItem item) {
    return new ImportSummary(
        item.getImportId(),
        item.getGame(),
        item.getFilename(),
        ImportStatus.valueOf(item.getStatus().toUpperCase(Locale.ROOT)),
        item.getRowCount(),
        item.getCreatedAt().getEpochSecond());
  }
}
