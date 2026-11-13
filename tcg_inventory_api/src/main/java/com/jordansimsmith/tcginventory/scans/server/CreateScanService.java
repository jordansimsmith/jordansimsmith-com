package com.jordansimsmith.tcginventory.scans.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.games.client.Game;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.games.client.GetGameRequest;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.scans.client.CreateScanRequest;
import com.jordansimsmith.tcginventory.scans.client.CreateScanResponse;
import com.jordansimsmith.tcginventory.scans.client.ScanFile;
import com.jordansimsmith.tcginventory.scans.client.ScanRowUpload;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

public class CreateScanService {
  private static final int MAX_SCAN_FILES = 200;
  private static final long MAX_SCAN_FILE_BYTES = 10L * 1024 * 1024;
  private static final Map<String, String> UPLOAD_HEADERS =
      Map.of("Content-Type", ScanImages.CONTENT_TYPE, "If-None-Match", "*");

  private final ScanMutationRepository scanMutationRepository;
  private final Clock clock;
  private final UlidGenerator ulidGenerator;
  private final S3Presigner s3Presigner;
  private final GamesService gamesService;
  private final String bucket;

  public CreateScanService(
      ScanMutationRepository scanMutationRepository,
      Clock clock,
      UlidGenerator ulidGenerator,
      S3Presigner s3Presigner,
      GamesService gamesService,
      String bucket) {
    this.scanMutationRepository = scanMutationRepository;
    this.clock = clock;
    this.ulidGenerator = ulidGenerator;
    this.s3Presigner = s3Presigner;
    this.gamesService = gamesService;
    this.bucket = bucket;
  }

  public CreateScanResponse createScan(RequestContext ctx, CreateScanRequest request)
      throws ServiceException {
    var game = gamesService.getGame(ctx, new GetGameRequest(request.game())).game();
    var files = validate(request, game);
    files.sort(Comparator.comparing(ScanFile::filename));

    var user = ctx.user();
    var scanId = ulidGenerator.generate();
    var now = clock.now();
    var scanItem =
        ScanItem.create(
            user, game.id(), scanId, request.condition(), request.finish(), files.size(), now);
    var rowItems = new ArrayList<ScanRowItem>();
    for (int index = 0; index < files.size(); index++) {
      var file = files.get(index);
      var position = index + 1;
      var key = "users/%s/scans/%s/%06d.jpg".formatted(user, scanId, position);
      var rowItem =
          ScanRowItem.create(user, scanId, position, file.filename(), file.sizeBytes(), key);
      rowItems.add(rowItem);
      scanMutationRepository.createScanRow(rowItem);
    }

    scanMutationRepository.createScan(scanItem);
    var slots = new ArrayList<ScanRowUpload>();
    for (var row : rowItems) {
      var uploadUrl =
          s3Presigner
              .presignPutObject(
                  PutObjectPresignRequest.builder()
                      .signatureDuration(ScanImages.PRESIGN_TTL)
                      .putObjectRequest(
                          PutObjectRequest.builder()
                              .bucket(bucket)
                              .key(row.getS3Key())
                              .contentType(ScanImages.CONTENT_TYPE)
                              .ifNoneMatch("*")
                              .build())
                      .build())
              .url()
              .toString();
      slots.add(
          new ScanRowUpload(
              row.getScanPosition(),
              row.getFilename(),
              row.getSizeBytes(),
              false,
              uploadUrl,
              UPLOAD_HEADERS));
    }
    return new CreateScanResponse(scanId, List.copyOf(slots));
  }

  private static List<ScanFile> validate(CreateScanRequest request, Game game)
      throws BadRequestException {
    if (!game.scanningEnabled()) {
      throw new BadRequestException("scanning is unavailable for game");
    }
    if (request.condition() == null || !isValidCondition(request.condition())) {
      throw new BadRequestException("invalid scan condition");
    }
    if (request.finish() == null
        || game.finishes().stream().noneMatch(finish -> finish.id().equals(request.finish()))) {
      throw new BadRequestException("invalid scan finish");
    }
    if (request.files() == null
        || request.files().isEmpty()
        || request.files().size() > MAX_SCAN_FILES) {
      throw new BadRequestException("a scan must contain between 1 and 200 files");
    }

    var filenames = new HashSet<String>();
    for (var file : request.files()) {
      if (file == null
          || file.filename() == null
          || file.filename().isBlank()
          || !file.filename().matches("(?i).*\\.jpe?g$")) {
        throw new BadRequestException("scan files must be JPEG images");
      }
      if (!file.filename().chars().allMatch(character -> character <= 0x7f)) {
        throw new BadRequestException("scan filenames must use ASCII characters");
      }
      if (!filenames.add(file.filename())) {
        throw new BadRequestException("scan filenames must be unique");
      }
      if (file.sizeBytes() == null
          || file.sizeBytes() <= 0
          || file.sizeBytes() > MAX_SCAN_FILE_BYTES) {
        throw new BadRequestException("scan files must be between 1 byte and 10 MiB");
      }
    }
    return new ArrayList<>(request.files());
  }

  private static boolean isValidCondition(String condition) {
    try {
      Condition.valueOf(condition);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
