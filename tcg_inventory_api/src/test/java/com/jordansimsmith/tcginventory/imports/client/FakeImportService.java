package com.jordansimsmith.tcginventory.imports.client;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.photos.client.CreatePhotoRequest;
import com.jordansimsmith.tcginventory.photos.client.DeletePhotoRequest;
import com.jordansimsmith.tcginventory.photos.client.PhotosService;
import com.jordansimsmith.tcginventory.imports.client.ConfirmImportRequest;
import com.jordansimsmith.tcginventory.imports.client.ConfirmImportResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeImportService implements ImportService {
  private final Map<UserImport, GetImportResponse> imports = new HashMap<>();
  private final Map<UserOperation, String> createOperations = new HashMap<>();
  private final PhotosService photosService;
  private int createOperationCounter;

  public FakeImportService(PhotosService photosService) {
    this.photosService = photosService;
  }

  @Override
  public CreateImportResponse createImport(RequestContext ctx, CreateImportRequest request)
      throws ServiceException {
    var operation = new UserOperation(ctx.user(), request.operationId());
    var previous = createOperations.get(operation);
    if (previous != null) {
      return new CreateImportResponse(previous);
    }
    var rows = new ArrayList<ImportRow>();
    if (request.rows() != null) {
      var orderedRows = new ArrayList<>(request.rows());
      Collections.reverse(orderedRows);
      for (int index = 0; index < orderedRows.size(); index++) {
        var row = orderedRows.get(index);
        rows.add(
            new ImportRow(
                index + 1,
                row.name(),
                row.setCode(),
                row.setName(),
                row.collectorNumber(),
                row.finish(),
                row.condition(),
                row.externalId(),
                null,
                null,
                null,
                null,
                List.of(),
                false));
      }
    }
    var importId = "fake-import-create-%d".formatted(++createOperationCounter);
    imports.put(
        new UserImport(ctx.user(), importId),
        new GetImportResponse(
            importId,
            request.game(),
            request.filename(),
            ImportStatus.APPRAISING,
            rows.size(),
            System.currentTimeMillis() / 1000,
            "0.00",
            null,
            null,
            rows));
    createOperations.put(operation, importId);
    return new CreateImportResponse(importId);
  }

  @Override
  public CreateImportRowPhotoResponse createImportRowPhoto(
      RequestContext ctx, CreateImportRowPhotoRequest request) throws ServiceException {
    if (request.importId() == null || request.importId().isBlank()) {
      throw new BadRequestException("import_id is required");
    }
    if (request.position() <= 0) {
      throw new BadRequestException("position must be positive");
    }
    var key = new UserImport(ctx.user(), request.importId());
    var importDetail = imports.get(key);
    if (importDetail == null) {
      throw new NotFoundException("Not Found");
    }
    if (importDetail.status() != ImportStatus.REVIEW) {
      throw new ConflictException("import is not in review status");
    }
    var rows = new ArrayList<>(importDetail.rows());
    var rowIndex = -1;
    for (int index = 0; index < rows.size(); index++) {
      if (rows.get(index).position() == request.position()) {
        rowIndex = index;
        break;
      }
    }
    if (rowIndex == -1) {
      throw new NotFoundException("Not Found");
    }
    var row = rows.get(rowIndex);
    if (row.decision() != ImportDecision.KEEP) {
      throw new BadRequestException("photos can only be created on keep rows");
    }
    if (row.photos().size() >= 5) {
      throw new BadRequestException("a row may have at most 5 photos");
    }
    var photoId =
        photosService.createPhoto(ctx, new CreatePhotoRequest(request.photoBytes())).photoId();
    var photoIds = new ArrayList<>(row.photos());
    photoIds.add(photoId);
    rows.set(
        rowIndex,
        new ImportRow(
            row.position(),
            row.name(),
            row.setCode(),
            row.setName(),
            row.collectorNumber(),
            row.finish(),
            row.condition(),
            row.externalId(),
            row.decision(),
            row.decisionReason(),
            row.marketPrice(),
            row.suggestedPrice(),
            photoIds,
            false));
    imports.put(
        key,
        new GetImportResponse(
            importDetail.importId(),
            importDetail.game(),
            importDetail.filename(),
            importDetail.status(),
            importDetail.rowCount(),
            importDetail.createdAt(),
            importDetail.totalSuggestedPrice(),
            importDetail.unitCount(),
            importDetail.placementInstructions(),
            rows));
    return new CreateImportRowPhotoResponse();
  }

  @Override
  public DeleteImportRowPhotoResponse deleteImportRowPhoto(
      RequestContext ctx, DeleteImportRowPhotoRequest request) throws ServiceException {
    if (request.importId() == null || request.importId().isBlank()) {
      throw new BadRequestException("import_id is required");
    }
    if (request.photoId() == null || request.photoId().isBlank()) {
      throw new BadRequestException("photo_id is required");
    }
    if (request.position() <= 0) {
      throw new BadRequestException("position must be positive");
    }
    var key = new UserImport(ctx.user(), request.importId());
    var importDetail = imports.get(key);
    if (importDetail == null) {
      throw new NotFoundException("Not Found");
    }
    if (importDetail.status() != ImportStatus.REVIEW) {
      throw new ConflictException("import is not in review status");
    }
    var rows = new ArrayList<>(importDetail.rows());
    var rowIndex = -1;
    for (int index = 0; index < rows.size(); index++) {
      if (rows.get(index).position() == request.position()) {
        rowIndex = index;
        break;
      }
    }
    if (rowIndex == -1) {
      throw new NotFoundException("Not Found");
    }
    var row = rows.get(rowIndex);
    var remainingPhotos =
        row.photos().stream().filter(photoId -> !photoId.equals(request.photoId())).toList();
    if (remainingPhotos.size() == row.photos().size()) {
      throw new NotFoundException("Not Found");
    }
    var needsPhotos =
        row.decision() == ImportDecision.KEEP
            && row.suggestedPrice() != null
            && new BigDecimal(row.suggestedPrice()).compareTo(new BigDecimal("20.00")) >= 0
            && remainingPhotos.isEmpty();
    rows.set(
        rowIndex,
        new ImportRow(
            row.position(),
            row.name(),
            row.setCode(),
            row.setName(),
            row.collectorNumber(),
            row.finish(),
            row.condition(),
            row.externalId(),
            row.decision(),
            row.decisionReason(),
            row.marketPrice(),
            row.suggestedPrice(),
            remainingPhotos,
            needsPhotos));
    imports.put(
        key,
        new GetImportResponse(
            importDetail.importId(),
            importDetail.game(),
            importDetail.filename(),
            importDetail.status(),
            importDetail.rowCount(),
            importDetail.createdAt(),
            importDetail.totalSuggestedPrice(),
            importDetail.unitCount(),
            importDetail.placementInstructions(),
            rows));
    photosService.deletePhoto(ctx, new DeletePhotoRequest(request.photoId()));
    return new DeleteImportRowPhotoResponse();
  }

  @Override
  public DeleteImportResponse deleteImport(RequestContext ctx, DeleteImportRequest request)
      throws ServiceException {
    if (request.importId() == null || request.importId().isBlank()) {
      throw new BadRequestException("import_id is required");
    }
    var key = new UserImport(ctx.user(), request.importId());
    var importDetail = imports.get(key);
    if (importDetail == null) {
      throw new NotFoundException("Not Found");
    }
    if (importDetail.status() != ImportStatus.REVIEW) {
      throw new ConflictException("import is not in a deletable status");
    }
    imports.remove(key);
    return new DeleteImportResponse();
  }

  @Override
  public ConfirmImportResponse confirmImport(RequestContext ctx, ConfirmImportRequest request)
      throws ServiceException {
    if (request.importId() == null || request.importId().isBlank()) {
      throw new BadRequestException("import_id is required");
    }
    var key = new UserImport(ctx.user(), request.importId());
    var importDetail = imports.get(key);
    if (importDetail == null) {
      throw new NotFoundException("Not Found");
    }
    if (importDetail.status() == ImportStatus.CONFIRMING
        || importDetail.status() == ImportStatus.CONFIRMED) {
      return new ConfirmImportResponse();
    }
    if (importDetail.status() != ImportStatus.REVIEW) {
      throw new ConflictException("import is not in review status");
    }
    var rowsNeedingPhotos =
        importDetail.rows().stream().filter(ImportRow::needsPhotos).count();
    if (rowsNeedingPhotos > 0) {
      throw new ConflictException(rowsNeedingPhotos + " rows need photos before confirm");
    }
    imports.put(
        key,
        new GetImportResponse(
            importDetail.importId(),
            importDetail.game(),
            importDetail.filename(),
            ImportStatus.CONFIRMING,
            importDetail.rowCount(),
            importDetail.createdAt(),
            importDetail.totalSuggestedPrice(),
            importDetail.unitCount(),
            importDetail.placementInstructions(),
            importDetail.rows()));
    return new ConfirmImportResponse();
  }

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

  @Override
  public UpdateImportRowResponse updateImportRow(RequestContext ctx, UpdateImportRowRequest request)
      throws ServiceException {
    if (request.importId() == null || request.importId().isBlank()) {
      throw new BadRequestException("import_id is required");
    }
    if (request.position() <= 0) {
      throw new BadRequestException("position must be positive");
    }
    if (request.condition() == null) {
      throw new BadRequestException("invalid condition");
    }
    var key = new UserImport(ctx.user(), request.importId());
    var importDetail = imports.get(key);
    if (importDetail == null) {
      throw new NotFoundException("Not Found");
    }
    if (importDetail.status() != ImportStatus.REVIEW) {
      throw new ConflictException("import is not in review status");
    }
    var rows = new ArrayList<>(importDetail.rows());
    var rowIndex = -1;
    for (int index = 0; index < rows.size(); index++) {
      if (rows.get(index).position() == request.position()) {
        rowIndex = index;
        break;
      }
    }
    if (rowIndex == -1) {
      throw new NotFoundException("Not Found");
    }
    var row = rows.get(rowIndex);
    rows.set(
        rowIndex,
        new ImportRow(
            row.position(),
            row.name(),
            row.setCode(),
            row.setName(),
            row.collectorNumber(),
            row.finish(),
            request.condition(),
            row.externalId(),
            row.decision(),
            row.decisionReason(),
            row.marketPrice(),
            row.suggestedPrice(),
            row.photos(),
            row.needsPhotos()));
    imports.put(
        key,
        new GetImportResponse(
            importDetail.importId(),
            importDetail.game(),
            importDetail.filename(),
            importDetail.status(),
            importDetail.rowCount(),
            importDetail.createdAt(),
            importDetail.totalSuggestedPrice(),
            importDetail.unitCount(),
            importDetail.placementInstructions(),
            rows));
    return new UpdateImportRowResponse();
  }

  @Override
  public DeleteImportRowResponse deleteImportRow(RequestContext ctx, DeleteImportRowRequest request)
      throws ServiceException {
    if (request.importId() == null || request.importId().isBlank()) {
      throw new BadRequestException("import_id is required");
    }
    if (request.position() <= 0) {
      throw new BadRequestException("position must be positive");
    }
    var key = new UserImport(ctx.user(), request.importId());
    var importDetail = imports.get(key);
    if (importDetail == null) {
      throw new NotFoundException("Not Found");
    }
    if (importDetail.status() != ImportStatus.REVIEW) {
      throw new ConflictException("import is not in review status");
    }
    var rows = new ArrayList<>(importDetail.rows());
    var rowRemoved = rows.removeIf(row -> row.position() == request.position());
    if (!rowRemoved) {
      throw new NotFoundException("Not Found");
    }
    var totalSuggestedPrice =
        rows.stream()
            .filter(row -> row.decision() == ImportDecision.KEEP)
            .map(row -> new BigDecimal(row.suggestedPrice()))
            .reduce(new BigDecimal("0.00"), BigDecimal::add)
            .toPlainString();
    imports.put(
        key,
        new GetImportResponse(
            importDetail.importId(),
            importDetail.game(),
            importDetail.filename(),
            importDetail.status(),
            importDetail.rowCount(),
            importDetail.createdAt(),
            totalSuggestedPrice,
            importDetail.unitCount(),
            importDetail.placementInstructions(),
            rows));
    return new DeleteImportRowResponse();
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

  private record UserOperation(String user, String operationId) {}
}
