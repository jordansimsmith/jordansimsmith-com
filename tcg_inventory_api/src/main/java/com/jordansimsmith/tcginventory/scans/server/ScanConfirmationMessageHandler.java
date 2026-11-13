package com.jordansimsmith.tcginventory.scans.server;

import com.google.common.collect.Lists;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.catalog.client.CatalogPrinting;
import com.jordansimsmith.tcginventory.catalog.client.CatalogService;
import com.jordansimsmith.tcginventory.catalog.client.FindCatalogCardsRequest;
import com.jordansimsmith.tcginventory.imports.client.CreateImportRequest;
import com.jordansimsmith.tcginventory.imports.client.CreateImportRow;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import com.jordansimsmith.tcginventory.imports.client.Language;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ScanConfirmationMessageHandler {
  private final ScanReadRepository readRepository;
  private final ScanMutationRepository mutationRepository;
  private final CatalogService catalogService;
  private final ImportService importService;

  public ScanConfirmationMessageHandler(
      ScanReadRepository readRepository,
      ScanMutationRepository mutationRepository,
      CatalogService catalogService,
      ImportService importService) {
    this.readRepository = readRepository;
    this.mutationRepository = mutationRepository;
    this.catalogService = catalogService;
    this.importService = importService;
  }

  public void handleMessage(RequestContext ctx, ScanConfirmationMessage message)
      throws ServiceException {
    var scanId = message.scanId();
    var scan = readRepository.getScan(ctx.user(), scanId);
    if (scan == null) {
      throw new IllegalStateException("scan %s: scan not found".formatted(scanId));
    }
    var status = scan.getStatus();
    if ("confirmed".equals(status)) {
      return;
    }
    if (!"confirming".equals(status)) {
      throw new IllegalStateException(
          "scan %s: expected confirming status, got %s".formatted(scanId, status));
    }

    var rows = readRepository.findScanRows(ctx.user(), scanId);
    if (rows.isEmpty()) {
      throw new IllegalStateException("confirming scan has no retained rows");
    }
    for (var row : rows) {
      var externalId = row.getSelectedExternalId();
      if (externalId == null || externalId.isBlank()) {
        throw new IllegalStateException(
            "scan position %d: missing selected printing ID".formatted(row.getScanPosition()));
      }
    }

    var printings = findPrintings(ctx, scan.getGame(), rows);
    var condition = Condition.valueOf(scan.getCondition());
    var importRows = new ArrayList<CreateImportRow>();
    for (var row : rows) {
      var printing = resolvePrinting(row, scan.getFinish(), printings);
      importRows.add(
          new CreateImportRow(
              row.getSelectedExternalId(),
              printing.name(),
              printing.setCode(),
              printing.setName(),
              printing.collectorNumber(),
              scan.getFinish(),
              condition,
              Language.ENGLISH));
    }
    var response =
        importService.createImport(
            ctx,
            new CreateImportRequest(
                scanId, scan.getGame(), null, scanId + ".scan", importRows.reversed()));
    mutationRepository.completeScanConfirmation(ctx.user(), scanId, response.importId());
  }

  private Map<String, CatalogPrinting> findPrintings(
      RequestContext ctx, String game, List<ScanRowItem> rows) throws ServiceException {
    var ids = rows.stream().map(ScanRowItem::getSelectedExternalId).distinct().toList();
    var printings = new HashMap<String, CatalogPrinting>();
    for (var batch : Lists.partition(ids, 100)) {
      var response = catalogService.findCards(ctx, new FindCatalogCardsRequest(game, batch));
      for (var printing : response.cards()) {
        printings.put(printing.externalId(), printing);
      }
    }

    return printings;
  }

  private CatalogPrinting resolvePrinting(
      ScanRowItem row, String finish, Map<String, CatalogPrinting> printings) {
    var position = row.getScanPosition();
    var printing = printings.get(row.getSelectedExternalId());
    if (printing == null) {
      throw new IllegalStateException(
          "scan position %d: selected printing was not found".formatted(position));
    }
    if (!printing.availableFinishes().contains(finish)) {
      throw new IllegalStateException(
          "scan position %d: selected printing does not support finish %s"
              .formatted(position, finish));
    }
    return printing;
  }
}
