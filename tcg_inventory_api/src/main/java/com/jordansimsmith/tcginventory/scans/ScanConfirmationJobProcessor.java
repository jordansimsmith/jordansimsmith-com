package com.jordansimsmith.tcginventory.scans;

import com.google.common.base.Strings;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobMessage;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.catalog.CatalogCard;
import com.jordansimsmith.tcginventory.catalog.CatalogException;
import com.jordansimsmith.tcginventory.catalog.Catalogs;
import com.jordansimsmith.tcginventory.games.Games;
import com.jordansimsmith.tcginventory.games.Games.Game;
import com.jordansimsmith.tcginventory.imports.ImportItem;
import com.jordansimsmith.tcginventory.imports.ImportRowItem;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

public class ScanConfirmationJobProcessor implements JobProcessor {
  private record ConfirmedScanRow(
      String externalId, String name, String setCode, String setName, String collectorNumber) {}

  private final Clock clock;
  private final ScanRepository scanRepository;
  private final Catalogs catalogs;
  private final DynamoDbTable<ImportItem> importTable;
  private final DynamoDbTable<ImportRowItem> importRowTable;
  private final DynamoDbTable<JobItem> jobTable;
  private final QueueClient<JobMessage> jobsQueue;
  private final UlidGenerator ulidGenerator;

  public ScanConfirmationJobProcessor(TcgInventoryFactory factory) {
    this.clock = factory.clock();
    this.scanRepository =
        new ScanRepository(
            TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ScanItem.class),
            TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ScanRowItem.class),
            factory.dynamoDbClient(),
            factory.clock());
    this.catalogs = factory.catalogs();
    this.importTable = TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ImportItem.class);
    this.importRowTable =
        TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ImportRowItem.class);
    this.jobTable = factory.jobTable();
    this.jobsQueue = factory.jobsQueue();
    this.ulidGenerator = factory.ulidGenerator();
  }

  @Override
  public JobProcessor.JobResult processBatch(String user, JobItem jobItem) {
    var scanId = jobItem.getScanId();
    if (scanId == null) {
      throw new IllegalStateException("scan confirmation job is missing required identity");
    }

    var scanItem = scanRepository.getScan(user, scanId);
    if (scanItem == null) {
      throw new IllegalStateException("scan not found for confirmation job");
    }
    if ("confirmed".equals(scanItem.getStatus())) {
      return new JobProcessor.SuccessJobResult(0, true);
    }
    if (!"confirming".equals(scanItem.getStatus())) {
      throw new IllegalStateException("scan is not in confirming status");
    }

    var scanRows = scanRepository.findScanRows(user, scanId);
    var game = Games.get(scanItem.getGame());
    List<ConfirmedScanRow> confirmedRows;
    try {
      confirmedRows = validateCatalogRows(game, scanItem.getFinish(), scanRows);
    } catch (IllegalArgumentException e) {
      scanRepository.failScanConfirmation(user, scanId, e.getMessage());
      return new JobProcessor.FailureJobResult(e.getMessage());
    }
    var importId = ulidGenerator.generate();
    var appraisalJobId = ulidGenerator.generate();
    var now = clock.now();
    importTable.putItem(
        ImportItem.create(
            user,
            scanItem.getGame(),
            importId,
            scanId + ".scan",
            confirmedRows.size(),
            appraisalJobId,
            now));

    for (int index = 0; index < confirmedRows.size(); index++) {
      var selected = confirmedRows.get(index);
      importRowTable.putItem(
          ImportRowItem.create(
              user,
              importId,
              index + 1,
              selected.name(),
              selected.setCode(),
              selected.setName(),
              selected.collectorNumber(),
              scanItem.getFinish(),
              scanItem.getCondition(),
              selected.externalId(),
              "en"));
    }

    jobTable.putItem(JobItem.create(user, appraisalJobId, "appraise", importId, null, now));
    var appraisalMessage = new JobMessage(user, appraisalJobId, "appraise");
    jobsQueue.send(appraisalMessage, user, appraisalMessage.deduplicationId(0));

    if (!scanRepository.transitionScanToConfirmed(user, scanId, importId)) {
      var currentScan = scanRepository.getScan(user, scanId);
      if (currentScan == null
          || !"confirmed".equals(currentScan.getStatus())
          || !importId.equals(currentScan.getImportId())) {
        throw new IllegalStateException("scan changed before confirmation completed");
      }
    }
    return new JobProcessor.SuccessJobResult(0, true);
  }

  private List<ConfirmedScanRow> validateCatalogRows(
      Game game, String finish, List<ScanRowItem> scanRows) {
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
        catalogs.forGame(game).findCards(List.copyOf(requestedIds)).values().stream()
            .collect(Collectors.toMap(CatalogCard::externalId, card -> card));
    var confirmedRows = new ArrayList<ConfirmedScanRow>();
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
          new ConfirmedScanRow(
              card.externalId(),
              card.name(),
              card.setCode(),
              card.setName(),
              card.collectorNumber()));
    }
    return List.copyOf(confirmedRows);
  }
}
