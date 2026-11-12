package com.jordansimsmith.tcginventory.imports.server;

import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.games.client.Game;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.games.client.GetGameRequest;
import com.jordansimsmith.tcginventory.imports.client.CreateImportRequest;
import com.jordansimsmith.tcginventory.imports.client.CreateImportResponse;
import com.jordansimsmith.tcginventory.imports.client.CreateImportRow;
import com.jordansimsmith.tcginventory.imports.client.Language;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CreateImportService {
  private final ImportReadRepository importReadRepository;
  private final ImportMutationRepository importMutationRepository;
  private final GamesService gamesService;
  private final ImportCsvParserFactory importCsvParserFactory;
  private final QueueClient<ImportMessage> importsQueue;
  private final Clock clock;
  private final UlidGenerator ulidGenerator;

  public CreateImportService(
      ImportReadRepository importReadRepository,
      ImportMutationRepository importMutationRepository,
      GamesService gamesService,
      ImportCsvParserFactory importCsvParserFactory,
      QueueClient<ImportMessage> importsQueue,
      Clock clock,
      UlidGenerator ulidGenerator) {
    this.importReadRepository = importReadRepository;
    this.importMutationRepository = importMutationRepository;
    this.gamesService = gamesService;
    this.importCsvParserFactory = importCsvParserFactory;
    this.importsQueue = importsQueue;
    this.clock = clock;
    this.ulidGenerator = ulidGenerator;
  }

  public CreateImportResponse createImport(RequestContext ctx, CreateImportRequest request)
      throws ServiceException {
    checkOperationId(request);
    var previous = importReadRepository.getCreateImportOperation(ctx.user(), request.operationId());
    if (previous != null) {
      return new CreateImportResponse(previous.getImportId());
    }

    var hasCsv = request.csv() != null;
    var hasRows = request.rows() != null;
    if (hasCsv == hasRows) {
      throw new BadRequestException("exactly one of csv or rows is required");
    }
    if (request.game() == null || request.game().isBlank()) {
      throw new BadRequestException("game is required");
    }

    var game = gamesService.getGame(ctx, new GetGameRequest(request.game())).game();
    List<CreateImportRow> inputRows;
    if (hasCsv) {
      if (request.csv().isBlank()) {
        throw new BadRequestException("CSV is empty");
      }
      if (!game.csvImportEnabled()) {
        throw new BadRequestException("CSV import is unavailable for game: " + game.id());
      }
      try {
        inputRows = importCsvParserFactory.forGame(game.id()).parse(request.csv(), game);
      } catch (IllegalArgumentException e) {
        throw new BadRequestException(e.getMessage(), e);
      }
    } else {
      inputRows = request.rows();
    }
    var orderedRows = new ArrayList<>(inputRows);
    Collections.reverse(orderedRows);
    for (var row : orderedRows) {
      validateRow(row, game);
    }

    var now = clock.now();
    var importId = ulidGenerator.generate();
    var rows = new ArrayList<ImportRowItem>();
    for (int index = 0; index < orderedRows.size(); index++) {
      var row = orderedRows.get(index);
      rows.add(
          ImportRowItem.create(
              ctx.user(),
              importId,
              index + 1,
              row.name(),
              row.setCode(),
              row.setName(),
              row.collectorNumber(),
              row.finish(),
              row.condition().name(),
              row.externalId(),
              languageCode(row.language())));
    }

    importMutationRepository.createRows(rows);
    importMutationRepository.createImport(
        ImportItem.create(ctx.user(), game.id(), importId, request.filename(), rows.size(), now),
        CreateImportOperationItem.create(ctx.user(), request.operationId(), importId));
    var message = new ImportMessage(ctx.user(), importId, ImportMessageType.APPRAISE);
    importsQueue.send(message, importId, importId + "#appraise#0");
    return new CreateImportResponse(importId);
  }

  private static void validateRow(CreateImportRow row, Game game) throws BadRequestException {
    if (row == null) {
      throw new BadRequestException("rows must not contain null values");
    }
    var fields =
        new String[] {
          row.externalId(),
          row.name(),
          row.setCode(),
          row.setName(),
          row.collectorNumber(),
          row.finish()
        };
    for (var field : fields) {
      if (field == null || field.isBlank()) {
        throw new BadRequestException("row identity and metadata are required");
      }
    }
    if (row.condition() == null || row.language() == null) {
      throw new BadRequestException("row condition and language are required");
    }
    if (game.finishes().stream().noneMatch(finish -> finish.id().equals(row.finish()))) {
      throw new BadRequestException("unsupported finish for game " + game.id());
    }
  }

  private static String languageCode(Language language) {
    return switch (language) {
      case ENGLISH -> "en";
      case JAPANESE -> "ja";
      case OTHER -> "other";
    };
  }

  private static void checkOperationId(CreateImportRequest request) throws BadRequestException {
    if (request == null || request.operationId() == null || request.operationId().isBlank()) {
      throw new BadRequestException("operation_id is required");
    }
  }
}
