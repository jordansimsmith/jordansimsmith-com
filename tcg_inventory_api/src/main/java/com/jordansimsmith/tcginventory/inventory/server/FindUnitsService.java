package com.jordansimsmith.tcginventory.inventory.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.Continuations;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsResponse;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitPhoto;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Named;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

public class FindUnitsService {
  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 100;
  private static final Duration PHOTO_URL_TTL = Duration.ofMinutes(15);

  private final InventoryReadRepository inventoryRepository;
  private final ObjectMapper objectMapper;
  private final S3Presigner s3Presigner;
  private final String photoBucket;

  public FindUnitsService(
      InventoryReadRepository inventoryRepository,
      ObjectMapper objectMapper,
      S3Presigner s3Presigner,
      @Named("inventoryPhotoBucket") String photoBucket) {
    this.inventoryRepository = inventoryRepository;
    this.objectMapper = objectMapper;
    this.s3Presigner = s3Presigner;
    this.photoBucket = photoBucket;
  }

  public FindUnitsResponse findUnits(RequestContext ctx, FindUnitsRequest request)
      throws BadRequestException {
    if (request.skuId() == null || request.skuId().isBlank()) {
      throw new BadRequestException("sku_id is required");
    }
    var limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new BadRequestException("limit must be between 1 and 100");
    }

    var continuation = decodeContinuation(request.continuation());
    var page = inventoryRepository.findUnits(ctx.user(), request.skuId(), limit, continuation);
    if (page == null) {
      return new FindUnitsResponse(List.of(), null);
    }

    var units =
        page.items().stream()
            .filter(item -> !"removed".equals(item.getStatus()))
            .map(
                item ->
                    new Unit(
                        item.getSequenceNumber(),
                        formatLocation(item.getSequenceNumber()),
                        item.getStatus(),
                        toPhotoResponses(ctx.user(), item.getPhotos())))
            .toList();
    var nextContinuation = Continuations.encode(page.lastEvaluatedKey(), objectMapper);
    return new FindUnitsResponse(units, nextContinuation);
  }

  @Nullable
  private Map<String, AttributeValue> decodeContinuation(@Nullable String continuation)
      throws BadRequestException {
    if (continuation == null || continuation.isEmpty()) {
      return null;
    }
    try {
      return Continuations.decode(continuation, objectMapper);
    } catch (RuntimeException e) {
      throw new BadRequestException("Invalid continuation");
    }
  }

  private List<UnitPhoto> toPhotoResponses(
      String user, @Nullable List<InventoryUnitItem.PhotoItem> photos) {
    if (photos == null) {
      return List.of();
    }
    return photos.stream()
        .map(photo -> new UnitPhoto(photo.getPhotoId(), presignedGetUrl(user, photo.getPhotoId())))
        .toList();
  }

  private String presignedGetUrl(String user, String photoId) {
    return s3Presigner
        .presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(PHOTO_URL_TTL)
                .getObjectRequest(
                    GetObjectRequest.builder()
                        .bucket(photoBucket)
                        .key("users/" + user + "/photos/" + photoId + ".jpg")
                        .build())
                .build())
        .url()
        .toString();
  }

  private static String formatLocation(int sequenceNumber) {
    var blockNumber = sequenceNumber / 100;
    var offset = sequenceNumber % 100;
    var letter = (char) ('A' + blockNumber / 100);
    return "" + letter + blockNumber % 100 + "-" + offset;
  }
}
