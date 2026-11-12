package com.jordansimsmith.tcginventory.scans;

import com.google.common.base.Strings;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.catalog.CatalogCard;
import com.jordansimsmith.tcginventory.catalog.CatalogException;
import com.jordansimsmith.tcginventory.catalog.Catalogs;
import com.jordansimsmith.tcginventory.games.client.Game;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.games.client.GetGameRequest;
import com.jordansimsmith.tcginventory.imports.client.CreateImportRequest;
import com.jordansimsmith.tcginventory.imports.client.CreateImportRow;
import com.jordansimsmith.tcginventory.imports.client.ImportService;
import com.jordansimsmith.tcginventory.imports.client.Language;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

public class ScanConfirmationJobProcessor implements JobProcessor {
  private final ScanRepository scanRepository;
  private final Catalogs catalogs;
  private final GamesService gamesService;
  private final ImportService importService;

  public ScanConfirmationJobProcessor(
      ScanRepository scanRepository,
      Catalogs catalogs,
      GamesService gamesService,
      ImportService importService) {
    this.scanRepository = scanRepository;
    this.catalogs = catalogs;
    this.gamesService = gamesService;
    this.importService = importService;
  }

  @Override
  public JobProcessor.JobResult processBatch(String user, JobItem jobItem) throws ServiceException {
    var scanId = jobItem.getScanId();
    if (scanId == null) {
      throw new IllegalStateException("scan confirmation job is missing required identity");
    }

    var scanItem = scanRepository.getScan(user, scanId);
    if (scanItem == null) {
      throw new IllegalStateException("scan not found for confirmation job");
    }
    if ("confirmed".equals(scanItem.getStatus())) {
      return new JobProcessor.SuccessJobResult(0, null);
    }
    if (!"confirming".equals(scanItem.getStatus())) {
      throw new IllegalStateException("scan is not in confirming status");
    }

    var scanRows = scanRepository.findScanRows(user, scanId);
    var game =
        gamesService
            .getGame(new RequestContext(user), new GetGameRequest(scanItem.getGame()))
            .game();
    List<CreateImportRow> confirmedRows;
    try {
      confirmedRows =
          validateCatalogRows(
              game, scanItem.getFinish(), Condition.valueOf(scanItem.getCondition()), scanRows);
    } catch (IllegalArgumentException e) {
      scanRepository.failScanConfirmation(user, scanId, e.getMessage());
      return new JobProcessor.FailureJobResult(e.getMessage());
    }
    Collections.reverse(confirmedRows);
    var response =
        importService.createImport(
            new RequestContext(user),
            new CreateImportRequest(
                scanId, scanItem.getGame(), null, scanId + ".scan", confirmedRows));

    if (!scanRepository.transitionScanToConfirmed(user, scanId, response.importId())) {
      var currentScan = scanRepository.getScan(user, scanId);
      if (currentScan == null
          || !"confirmed".equals(currentScan.getStatus())
          || !response.importId().equals(currentScan.getImportId())) {
        throw new IllegalStateException("scan changed before confirmation completed");
      }
    }
    return new JobProcessor.SuccessJobResult(0, null);
  }

  private ArrayList<CreateImportRow> validateCatalogRows(
      Game game, String finish, Condition condition, List<ScanRowItem> scanRows) {
    if (scanRows.isEmpty()) {
      throw new IllegalArgumentException("scan has no retained rows");
    }
    var requestedIds = new LinkedHashSet<String>();
    for (var row : scanRows) {
      if (Strings.isNullOrEmpty(row.getSelectedExternalId())) {
        throw new IllegalArgumentException(
            "scan position %d: selected card identity is required"
                .formatted(row.getScanPosition()));
      }
      requestedIds.add(row.getSelectedExternalId());
    }

    var resolvedCards =
        catalogs.forGame(game).findCards(game, List.copyOf(requestedIds)).values().stream()
            .collect(Collectors.toMap(CatalogCard::externalId, card -> card));
    var confirmedRows = new ArrayList<CreateImportRow>();
    for (var row : scanRows) {
      var card = resolvedCards.get(row.getSelectedExternalId());
      if (card == null) {
        throw new IllegalArgumentException(
            "scan position %d: selected card was not found".formatted(row.getScanPosition()));
      }
      if (!game.id().equals(card.game())) {
        throw new CatalogException.Unavailable("catalog returned an incompatible card identity");
      }
      if (!card.availableFinishes().contains(finish)) {
        throw new IllegalArgumentException(
            "scan position %d: selected card does not support finish %s"
                .formatted(row.getScanPosition(), finish));
      }
      confirmedRows.add(
          new CreateImportRow(
              card.externalId(),
              card.name(),
              card.setCode(),
              card.setName(),
              card.collectorNumber(),
              finish,
              condition,
              Language.ENGLISH));
    }
    return confirmedRows;
  }
}
