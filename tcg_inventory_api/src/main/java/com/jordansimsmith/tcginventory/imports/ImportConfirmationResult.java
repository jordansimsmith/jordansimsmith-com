package com.jordansimsmith.tcginventory.imports;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jordansimsmith.tcginventory.inventory.InventoryLocation;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

public record ImportConfirmationResult(
    @JsonProperty("unit_count") int unitCount,
    @JsonProperty("total_suggested_price") String totalSuggestedPrice,
    @JsonProperty("first_sequence_number") @Nullable Integer firstSequenceNumber,
    @JsonProperty("last_sequence_number") @Nullable Integer lastSequenceNumber,
    @JsonProperty("placement_instructions") List<PlacementInstruction> placementInstructions) {

  public record PlacementInstruction(
      @JsonProperty("block") String block,
      @JsonProperty("from_location") String fromLocation,
      @JsonProperty("to_location") String toLocation,
      @JsonProperty("from_name") String fromName,
      @JsonProperty("to_name") String toName,
      @JsonProperty("unit_count") int unitCount) {}

  public static ImportConfirmationResult from(ImportItem importItem, List<ImportRowItem> keepRows) {
    var totalSuggestedPrice = ImportRows.totalSuggestedPrice(keepRows);
    var unitCount = keepRows.size();
    Integer firstSequenceNumber = null;
    if (unitCount > 0) {
      firstSequenceNumber =
          importItem.getFirstSequenceNumber() != null
              ? importItem.getFirstSequenceNumber()
              : keepRows.stream()
                  .map(ImportRowItem::getSequenceNumber)
                  .filter(value -> value != null)
                  .mapToInt(Integer::intValue)
                  .min()
                  .orElseThrow();
      for (int index = 0; index < keepRows.size(); index++) {
        if (!Integer.valueOf(firstSequenceNumber + index)
            .equals(keepRows.get(index).getSequenceNumber())) {
          throw new IllegalStateException("confirmed import sequence range is ambiguous");
        }
      }
    }
    var lastSequenceNumber =
        firstSequenceNumber == null ? null : firstSequenceNumber + unitCount - 1;
    return new ImportConfirmationResult(
        unitCount,
        totalSuggestedPrice,
        firstSequenceNumber,
        lastSequenceNumber,
        buildPlacementInstructions(keepRows));
  }

  private static List<PlacementInstruction> buildPlacementInstructions(
      List<ImportRowItem> keepRows) {
    if (keepRows.isEmpty()) {
      return List.of();
    }
    var instructions = new ArrayList<PlacementInstruction>();
    int currentBlockNumber = keepRows.get(0).getSequenceNumber() / 100;
    int blockStartIndex = 0;
    for (int index = 0; index < keepRows.size(); index++) {
      var blockNumber = keepRows.get(index).getSequenceNumber() / 100;
      if (blockNumber != currentBlockNumber) {
        instructions.add(
            buildInstruction(keepRows, blockStartIndex, index - 1, currentBlockNumber));
        currentBlockNumber = blockNumber;
        blockStartIndex = index;
      }
    }
    instructions.add(
        buildInstruction(keepRows, blockStartIndex, keepRows.size() - 1, currentBlockNumber));
    return instructions;
  }

  private static PlacementInstruction buildInstruction(
      List<ImportRowItem> rows, int startIndex, int endIndex, int blockNumber) {
    var firstRow = rows.get(startIndex);
    var lastRow = rows.get(endIndex);
    return new PlacementInstruction(
        InventoryLocation.formatBlock(blockNumber),
        InventoryLocation.formatLocation(firstRow.getSequenceNumber()),
        InventoryLocation.formatLocation(lastRow.getSequenceNumber()),
        firstRow.getName(),
        lastRow.getName(),
        endIndex - startIndex + 1);
  }
}
