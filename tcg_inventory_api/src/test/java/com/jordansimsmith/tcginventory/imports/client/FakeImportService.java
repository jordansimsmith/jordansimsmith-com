package com.jordansimsmith.tcginventory.imports.client;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

public class FakeImportService implements ImportService {
  private final Map<UserImport, GetImportResponse> imports = new HashMap<>();

  public void seedImport(String user, GetImportResponse importDetail) {
    imports.put(new UserImport(user, importDetail.importId()), importDetail);
  }

  @Override
  public FindImportsResponse findImports(RequestContext ctx, FindImportsRequest request)
      throws ServiceException {
    var limit = request.limit() == null ? 20 : request.limit();
    if (limit < 1 || limit > 100) {
      throw new BadRequestException("limit must be between 1 and 100");
    }
    var userImports =
        imports.entrySet().stream()
            .filter(entry -> entry.getKey().user().equals(ctx.user()))
            .map(Map.Entry::getValue)
            .sorted(
                Comparator.comparingLong(GetImportResponse::createdAt)
                    .thenComparing(GetImportResponse::importId)
                    .reversed())
            .toList();
    var offset = decodeContinuation(request.continuation());
    var page = userImports.stream().skip(offset).limit(limit).toList();
    var nextOffset = offset + page.size();
    return new FindImportsResponse(
        page.stream().map(FakeImportService::toSummary).toList(),
        nextOffset < userImports.size() ? Integer.toString(nextOffset) : null);
  }

  @Override
  public GetImportResponse getImport(RequestContext ctx, GetImportRequest request)
      throws ServiceException {
    var result = imports.get(new UserImport(ctx.user(), request.importId()));
    if (result == null) {
      throw new NotFoundException("Not Found");
    }
    return result;
  }

  private static int decodeContinuation(String continuation) throws BadRequestException {
    if (continuation == null || continuation.isEmpty()) {
      return 0;
    }
    try {
      return Integer.parseInt(continuation);
    } catch (NumberFormatException e) {
      throw new BadRequestException("Invalid continuation", e);
    }
  }

  private static ImportSummary toSummary(GetImportResponse response) {
    return new ImportSummary(
        response.importId(),
        response.game(),
        response.filename(),
        response.status(),
        response.rowCount(),
        response.createdAt());
  }

  private record UserImport(String user, String importId) {}
}
