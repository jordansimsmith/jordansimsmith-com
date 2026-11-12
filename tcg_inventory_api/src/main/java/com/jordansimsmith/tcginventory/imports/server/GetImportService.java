package com.jordansimsmith.tcginventory.imports.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.imports.client.GetImportRequest;
import com.jordansimsmith.tcginventory.imports.client.GetImportResponse;
import com.jordansimsmith.tcginventory.imports.client.ImportDecision;
import com.jordansimsmith.tcginventory.imports.client.ImportRow;
import com.jordansimsmith.tcginventory.imports.client.ImportStatus;
import com.jordansimsmith.tcginventory.imports.client.PlacementInstruction;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GetImportService {
  private final ImportReadRepository importReadRepository;

  public GetImportService(ImportReadRepository importReadRepository) {
    this.importReadRepository = importReadRepository;
  }

  public GetImportResponse getImport(RequestContext ctx, GetImportRequest request)
      throws ServiceException {
    checkRequest(request);
    var item = importReadRepository.getImport(ctx.user(), request.importId());
    if (item == null) {
      throw new NotFoundException("Not Found");
    }

    var rows = importReadRepository.findRows(ctx.user(), request.importId());
    var status = ImportStatus.valueOf(item.getStatus().toUpperCase(Locale.ROOT));
    var importRows = rows.stream().map(GetImportService::toImportRow).toList();
    var confirmed = status == ImportStatus.CONFIRMED;
    var keepRows = rows.stream().filter(row -> "keep".equals(row.getDecision())).toList();
    return new GetImportResponse(
        item.getImportId(),
        item.getGame(),
        item.getFilename(),
        status,
        item.getRowCount(),
        item.getCreatedAt().getEpochSecond(),
        totalSuggestedPrice(keepRows),
        confirmed ? keepRows.size() : null,
        confirmed ? buildPlacementInstructions(keepRows) : null,
        importRows);
  }

  private static void checkRequest(GetImportRequest request) throws BadRequestException {
    if (request.importId() == null || request.importId().isBlank()) {
      throw new BadRequestException("import_id is required");
    }
  }

  private static ImportRow toImportRow(ImportRowItem item) {
    var photos = item.getPhotos() == null ? List.<ImportRowItem.Photo>of() : item.getPhotos();
    var photoIds = photos.stream().map(ImportRowItem.Photo::getPhotoId).toList();
    var decision =
        item.getDecision() == null
            ? null
            : ImportDecision.valueOf(item.getDecision().toUpperCase(Locale.ROOT));
    return new ImportRow(
        item.getPosition(),
        item.getName(),
        item.getSetCode(),
        item.getSetName(),
        item.getCollectorNumber(),
        item.getFinish(),
        Condition.valueOf(item.getCondition()),
        item.getExternalId(),
        decision,
        item.getDecisionReason(),
        item.getMarketPrice(),
        item.getSuggestedPrice(),
        photoIds,
        ImportPhotoPolicy.needsPhotos(
            item.getDecision(), item.getSuggestedPrice(), photoIds.size()));
  }

  private static String totalSuggestedPrice(List<ImportRowItem> keepRows) {
    return keepRows.stream()
        .map(row -> new BigDecimal(row.getSuggestedPrice()))
        .reduce(new BigDecimal("0.00"), BigDecimal::add)
        .toPlainString();
  }

  private static List<PlacementInstruction> buildPlacementInstructions(
      List<ImportRowItem> keepRows) {
    var instructions = new ArrayList<PlacementInstruction>();
    if (keepRows.isEmpty()) {
      return instructions;
    }

    int groupStart = 0;
    int previousSequence = keepRows.get(0).getSequenceNumber();
    var block = blockOf(keepRows.get(0).getLocation());
    for (int index = 1; index < keepRows.size(); index++) {
      var row = keepRows.get(index);
      var sequence = row.getSequenceNumber();
      var rowBlock = blockOf(row.getLocation());
      if (sequence != previousSequence + 1 || !rowBlock.equals(block)) {
        instructions.add(placementInstruction(keepRows, groupStart, index - 1, block));
        groupStart = index;
        block = rowBlock;
      }
      previousSequence = sequence;
    }
    instructions.add(placementInstruction(keepRows, groupStart, keepRows.size() - 1, block));
    return List.copyOf(instructions);
  }

  private static PlacementInstruction placementInstruction(
      List<ImportRowItem> rows, int start, int end, String block) {
    var first = rows.get(start);
    var last = rows.get(end);
    return new PlacementInstruction(
        block,
        first.getLocation(),
        last.getLocation(),
        first.getName(),
        last.getName(),
        end - start + 1);
  }

  private static String blockOf(String location) {
    return location.substring(0, location.lastIndexOf('-'));
  }
}
