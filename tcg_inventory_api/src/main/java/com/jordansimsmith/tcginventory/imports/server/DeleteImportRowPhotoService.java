package com.jordansimsmith.tcginventory.imports.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.imports.client.DeleteImportRowPhotoRequest;
import com.jordansimsmith.tcginventory.imports.client.DeleteImportRowPhotoResponse;
import com.jordansimsmith.tcginventory.photos.client.DeletePhotoRequest;
import com.jordansimsmith.tcginventory.photos.client.PhotosService;
import java.util.ArrayList;

public class DeleteImportRowPhotoService {
  private final ImportReadRepository importReadRepository;
  private final ImportMutationRepository importMutationRepository;
  private final PhotosService photosService;

  public DeleteImportRowPhotoService(
      ImportReadRepository importReadRepository,
      ImportMutationRepository importMutationRepository,
      PhotosService photosService) {
    this.importReadRepository = importReadRepository;
    this.importMutationRepository = importMutationRepository;
    this.photosService = photosService;
  }

  public DeleteImportRowPhotoResponse deleteImportRowPhoto(
      RequestContext ctx, DeleteImportRowPhotoRequest request) throws ServiceException {
    checkRequest(request);
    var user = ctx.user();
    var importItem = importReadRepository.getImport(user, request.importId());
    if (importItem == null) {
      throw new NotFoundException("Not Found");
    }
    if (!"review".equals(importItem.getStatus())) {
      throw new ConflictException("import is not in review status");
    }
    var row = importReadRepository.getRow(user, request.importId(), request.position());
    if (row == null) {
      throw new NotFoundException("Not Found");
    }

    var remaining = removePhoto(row, request.photoId());
    importMutationRepository.updateRowPhotos(
        user, request.importId(), request.position(), remaining);
    photosService.deletePhoto(ctx, new DeletePhotoRequest(request.photoId()));
    return new DeleteImportRowPhotoResponse();
  }

  private static void checkRequest(DeleteImportRowPhotoRequest request)
      throws BadRequestException {
    if (request.importId() == null || request.importId().isBlank()) {
      throw new BadRequestException("import_id is required");
    }
    if (request.photoId() == null || request.photoId().isBlank()) {
      throw new BadRequestException("photo_id is required");
    }
    if (request.position() <= 0) {
      throw new BadRequestException("position must be positive");
    }
  }

  private static ArrayList<ImportRowItem.Photo> removePhoto(
      ImportRowItem row, String photoId) throws NotFoundException {
    var remaining = new ArrayList<ImportRowItem.Photo>();
    var found = false;
    if (row.getPhotos() != null) {
      for (var photo : row.getPhotos()) {
        if (photoId.equals(photo.getPhotoId())) {
          found = true;
        } else {
          remaining.add(photo);
        }
      }
    }
    if (!found) {
      throw new NotFoundException("Not Found");
    }
    return remaining;
  }
}
