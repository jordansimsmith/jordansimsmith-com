package com.jordansimsmith.tcginventory.photos.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.photos.client.CreatePhotoRequest;
import com.jordansimsmith.tcginventory.photos.client.CreatePhotoResponse;
import com.jordansimsmith.ulid.UlidGenerator;

public class CreatePhotoService {
  private static final int MAX_BYTES = 4 * 1024 * 1024;

  private final S3PhotoStore photoStore;
  private final UlidGenerator ulidGenerator;

  public CreatePhotoService(S3PhotoStore photoStore, UlidGenerator ulidGenerator) {
    this.photoStore = photoStore;
    this.ulidGenerator = ulidGenerator;
  }

  public CreatePhotoResponse createPhoto(RequestContext ctx, CreatePhotoRequest request)
      throws ServiceException {
    var photoBytes = request.photoBytes();
    if (photoBytes == null || photoBytes.length == 0) {
      throw new BadRequestException("photo_bytes is required");
    }
    if (photoBytes.length > MAX_BYTES) {
      throw new BadRequestException("photo_bytes must not exceed 4 MiB");
    }

    var photoId = ulidGenerator.generate();
    photoStore.create(ctx.user(), photoId, photoBytes);
    return new CreatePhotoResponse(photoId);
  }
}
