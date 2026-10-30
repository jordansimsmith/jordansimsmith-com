package com.jordansimsmith.tcginventory.photos.client;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;

public interface PhotosService {
  CreatePhotoResponse createPhoto(RequestContext ctx, CreatePhotoRequest request)
      throws ServiceException;

  FindPhotosResponse findPhotos(RequestContext ctx, FindPhotosRequest request)
      throws ServiceException;

  GetPhotoBytesResponse getPhotoBytes(RequestContext ctx, GetPhotoBytesRequest request)
      throws ServiceException;

  DeletePhotoResponse deletePhoto(RequestContext ctx, DeletePhotoRequest request)
      throws ServiceException;
}
