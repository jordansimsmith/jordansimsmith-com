package com.jordansimsmith.tcginventory.photos.client;

import com.jordansimsmith.service.RequestContext;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class FakePhotosService implements PhotosService {
  private final AtomicInteger nextPhotoId = new AtomicInteger();
  private final Map<String, Map<String, byte[]>> photosByUser = new LinkedHashMap<>();

  @Override
  public CreatePhotoResponse createPhoto(RequestContext ctx, CreatePhotoRequest request) {
    var photoId = "FAKE_PHOTO_%010d".formatted(nextPhotoId.incrementAndGet());
    photosByUser
        .computeIfAbsent(ctx.user(), user -> new LinkedHashMap<>())
        .put(photoId, request.photoBytes().clone());
    return new CreatePhotoResponse(photoId);
  }

  @Override
  public FindPhotosResponse findPhotos(RequestContext ctx, FindPhotosRequest request) {
    var photos =
        new LinkedHashSet<>(request.photoIds())
            .stream()
                .map(
                    photoId ->
                        new Photo(
                            photoId,
                            "https://photos.test/users/%s/photos/%s.jpg"
                                .formatted(ctx.user(), photoId)))
                .toList();
    return new FindPhotosResponse(photos);
  }

  @Override
  public GetPhotoBytesResponse getPhotoBytes(RequestContext ctx, GetPhotoBytesRequest request) {
    return new GetPhotoBytesResponse(photosByUser.get(ctx.user()).get(request.photoId()).clone());
  }

  @Override
  public DeletePhotoResponse deletePhoto(RequestContext ctx, DeletePhotoRequest request) {
    var photos = photosByUser.get(ctx.user());
    if (photos != null) {
      photos.remove(request.photoId());
    }
    return new DeletePhotoResponse();
  }
}
