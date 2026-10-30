package com.jordansimsmith.tcginventory.photos.server;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.photos.client.CreatePhotoRequest;
import com.jordansimsmith.tcginventory.photos.client.CreatePhotoResponse;
import com.jordansimsmith.tcginventory.photos.client.DeletePhotoRequest;
import com.jordansimsmith.tcginventory.photos.client.DeletePhotoResponse;
import com.jordansimsmith.tcginventory.photos.client.FindPhotosRequest;
import com.jordansimsmith.tcginventory.photos.client.FindPhotosResponse;
import com.jordansimsmith.tcginventory.photos.client.GetPhotoBytesRequest;
import com.jordansimsmith.tcginventory.photos.client.GetPhotoBytesResponse;
import com.jordansimsmith.tcginventory.photos.client.PhotosService;
import com.jordansimsmith.ulid.UlidGenerator;
import dagger.Module;
import dagger.Provides;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Module
public class PhotosModule {
  @Provides
  @Singleton
  PhotosService photosService(
      S3Client s3Client,
      S3Presigner s3Presigner,
      UlidGenerator ulidGenerator,
      @Named("photosBucket") String bucket) {
    var photoStore = new S3PhotoStore(s3Client, s3Presigner, bucket);
    var createPhotoService = new CreatePhotoService(photoStore, ulidGenerator);
    var findPhotosService = new FindPhotosService(photoStore);
    var getPhotoBytesService = new GetPhotoBytesService(photoStore);
    var deletePhotoService = new DeletePhotoService(photoStore);
    return new PhotosService() {
      @Override
      public CreatePhotoResponse createPhoto(RequestContext ctx, CreatePhotoRequest request)
          throws ServiceException {
        return createPhotoService.createPhoto(ctx, request);
      }

      @Override
      public FindPhotosResponse findPhotos(RequestContext ctx, FindPhotosRequest request)
          throws ServiceException {
        return findPhotosService.findPhotos(ctx, request);
      }

      @Override
      public GetPhotoBytesResponse getPhotoBytes(RequestContext ctx, GetPhotoBytesRequest request)
          throws ServiceException {
        return getPhotoBytesService.getPhotoBytes(ctx, request);
      }

      @Override
      public DeletePhotoResponse deletePhoto(RequestContext ctx, DeletePhotoRequest request)
          throws ServiceException {
        return deletePhotoService.deletePhoto(ctx, request);
      }
    };
  }
}
