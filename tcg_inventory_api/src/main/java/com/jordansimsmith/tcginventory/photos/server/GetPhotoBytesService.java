package com.jordansimsmith.tcginventory.photos.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.photos.client.GetPhotoBytesRequest;
import com.jordansimsmith.tcginventory.photos.client.GetPhotoBytesResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

public class GetPhotoBytesService {
  private final S3PhotoStore photoStore;

  public GetPhotoBytesService(S3PhotoStore photoStore) {
    this.photoStore = photoStore;
  }

  public GetPhotoBytesResponse getPhotoBytes(RequestContext ctx, GetPhotoBytesRequest request)
      throws ServiceException {
    if (request.photoId() == null || request.photoId().isBlank()) {
      throw new BadRequestException("photo_id is required");
    }
    try {
      return new GetPhotoBytesResponse(photoStore.getBytes(ctx.user(), request.photoId()));
    } catch (NoSuchKeyException e) {
      throw new NotFoundException("Photo was not found");
    }
  }
}
