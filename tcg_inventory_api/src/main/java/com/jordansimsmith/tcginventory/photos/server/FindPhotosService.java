package com.jordansimsmith.tcginventory.photos.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.photos.client.FindPhotosRequest;
import com.jordansimsmith.tcginventory.photos.client.FindPhotosResponse;
import com.jordansimsmith.tcginventory.photos.client.Photo;
import java.util.LinkedHashSet;
import java.util.stream.IntStream;

public class FindPhotosService {
  private static final int MAX_PHOTOS = 100;

  private final S3PhotoStore photoStore;

  public FindPhotosService(S3PhotoStore photoStore) {
    this.photoStore = photoStore;
  }

  public FindPhotosResponse findPhotos(RequestContext ctx, FindPhotosRequest request)
      throws ServiceException {
    var photoIds = request.photoIds();
    if (photoIds == null || photoIds.isEmpty() || photoIds.size() > MAX_PHOTOS) {
      throw new BadRequestException("photo_ids must contain between 1 and 100 values");
    }
    if (photoIds.stream().anyMatch(photoId -> photoId == null || photoId.isBlank())) {
      throw new BadRequestException("photo_ids must not contain blank values");
    }

    var distinctPhotoIds = new LinkedHashSet<>(photoIds).stream().toList();
    var urls = photoStore.findUrls(ctx.user(), distinctPhotoIds);
    var photos =
        IntStream.range(0, distinctPhotoIds.size())
            .mapToObj(index -> new Photo(distinctPhotoIds.get(index), urls.get(index)))
            .toList();
    return new FindPhotosResponse(photos);
  }
}
