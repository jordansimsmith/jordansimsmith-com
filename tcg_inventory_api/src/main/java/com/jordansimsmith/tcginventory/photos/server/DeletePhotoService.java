package com.jordansimsmith.tcginventory.photos.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.photos.client.DeletePhotoRequest;
import com.jordansimsmith.tcginventory.photos.client.DeletePhotoResponse;

public class DeletePhotoService {
  private final S3PhotoStore photoStore;

  public DeletePhotoService(S3PhotoStore photoStore) {
    this.photoStore = photoStore;
  }

  public DeletePhotoResponse deletePhoto(RequestContext ctx, DeletePhotoRequest request)
      throws ServiceException {
    if (request.photoId() == null || request.photoId().isBlank()) {
      throw new BadRequestException("photo_id is required");
    }
    photoStore.delete(ctx.user(), request.photoId());
    return new DeletePhotoResponse();
  }
}
