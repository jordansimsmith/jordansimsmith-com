package com.jordansimsmith.tcginventory.imports;

import com.jordansimsmith.tcginventory.BatchResult;
import com.jordansimsmith.tcginventory.CardIdentity;
import com.jordansimsmith.tcginventory.Condition;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.SkuIds;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.time.Clock;

public class ConfirmImportJobProcessor {
  private final ImportRepository importRepository;
  private final Clock clock;

  public ConfirmImportJobProcessor(ImportRepository importRepository, Clock clock) {
    this.importRepository = importRepository;
    this.clock = clock;
  }

  public BatchResult processBatch(String user, JobItem jobItem) {
    var importId = jobItem.getImportId();
    if (importId == null) {
      throw new IllegalStateException("import confirmation job is missing its import id");
    }
    var importItem = importRepository.getImport(user, importId);
    if (importItem == null) {
      throw new IllegalStateException("import not found during confirmation: " + importId);
    }
    if ("confirmed".equals(importItem.getStatus())) {
      return new BatchResult(1, true);
    }
    if (!"confirming".equals(importItem.getStatus())) {
      throw new IllegalStateException("import is not confirming");
    }

    var keepRows = importRepository.findKeepRows(user, importId);
    var firstSequenceNumber = importRepository.freezeSequenceRange(user, importItem, keepRows);
    for (int index = 0; index < keepRows.size(); index++) {
      var row = keepRows.get(index);
      var sequenceNumber = firstSequenceNumber + index;
      if (Boolean.TRUE.equals(row.getConfirmed())) {
        if (!Integer.valueOf(sequenceNumber).equals(row.getSequenceNumber())) {
          throw new IllegalStateException("confirmed import row has an unexpected sequence number");
        }
        continue;
      }
      var sku = toSku(user, importItem.getGame(), row);
      importRepository.confirmRow(
          user,
          importItem,
          row,
          sku,
          toUnit(user, importItem, row, sku.getSkuId(), sequenceNumber),
          sequenceNumber);
    }

    var verifiedRows = importRepository.findKeepRows(user, importId);
    if (verifiedRows.size() != keepRows.size()) {
      throw new IllegalStateException("import rows changed during confirmation");
    }
    for (int index = 0; index < verifiedRows.size(); index++) {
      var row = verifiedRows.get(index);
      if (!Boolean.TRUE.equals(row.getConfirmed())
          || !Integer.valueOf(firstSequenceNumber + index).equals(row.getSequenceNumber())) {
        throw new IllegalStateException("import confirmation is missing a completed row");
      }
    }
    importRepository.finishConfirmation(user, importId, keepRows.size());
    return new BatchResult(1, true);
  }

  private SkuItem toSku(String user, String game, ImportRowItem row) {
    var identity = new CardIdentity(game, row.getExternalSource(), row.getExternalId());
    var skuId = SkuIds.format(identity, row.getFinish(), Condition.valueOf(row.getCondition()));
    var sku =
        SkuItem.create(
            user,
            skuId,
            game,
            row.getExternalSource(),
            row.getExternalId(),
            row.getFinish(),
            row.getCondition(),
            row.getName(),
            row.getSetCode(),
            row.getSetName(),
            row.getCollectorNumber(),
            row.getFetchtcgCardId(),
            row.getSuggestedPrice());
    sku.setFetchtcgSetId(row.getFetchtcgSetId());
    return sku;
  }

  private UnitItem toUnit(
      String user, ImportItem importItem, ImportRowItem row, String skuId, int sequenceNumber) {
    var unit =
        UnitItem.create(
            user,
            importItem.getGame(),
            skuId,
            sequenceNumber,
            "in_stock",
            importItem.getImportId(),
            clock.now());
    if (row.getPhotos() != null && !row.getPhotos().isEmpty()) {
      unit.setPhotos(
          row.getPhotos().stream()
              .map(photo -> UnitItem.Photo.create(photo.getPhotoId(), photo.getFetchtcgUrl()))
              .toList());
    }
    return unit;
  }
}
