package com.jordansimsmith.tcginventory.imports.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.imports.client.CreateImportRowPhotoRequest;
import com.jordansimsmith.tcginventory.imports.client.CreateImportRowPhotoResponse;
import com.jordansimsmith.tcginventory.photos.client.CreatePhotoRequest;
import com.jordansimsmith.tcginventory.photos.client.PhotosService;
import java.util.ArrayList;

public class CreateImportRowPhotoService {
  private static final int MAX_PHOTOS = 5;

  private final ImportReadRepository importReadRepository;
  private final ImportMutationRepository importMutationRepository;
  private final PhotosService photosService;

  public CreateImportRowPhotoService(
      ImportReadRepository importReadRepository,
      ImportMutationRepository importMutationRepository,
      PhotosService photosService) {
    this.importReadRepository = importReadRepository;
    this.importMutationRepository = importMutationRepository;
    this.photosService = photosService;
  }

  public CreateImportRowPhotoResponse createImportRowPhoto(
      RequestContext ctx, CreateImportRowPhotoRequest request) throws ServiceException {
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
    if (!"keep".equals(row.getDecision())) {
      throw new BadRequestException("photos can only be created on keep rows");
    }
    addPhoto(ctx, request, row);
    return new CreateImportRowPhotoResponse();
  }

  private void addPhoto(RequestContext ctx, CreateImportRowPhotoRequest request, ImportRowItem row)
      throws ServiceException {
    var photos =
        row.getPhotos() == null
            ? new ArrayList<ImportRowItem.Photo>()
            : new ArrayList<>(row.getPhotos());
    if (photos.size() >= MAX_PHOTOS) {
      throw new BadRequestException("a row may have at most 5 photos");
    }

    var photoId =
        photosService.createPhoto(ctx, new CreatePhotoRequest(request.photoBytes())).photoId();
    photos.add(ImportRowItem.Photo.create(photoId));
    importMutationRepository.updateRowPhotos(
        ctx.user(), request.importId(), request.position(), photos);
  }

  private static void checkRequest(CreateImportRowPhotoRequest request) throws BadRequestException {
    if (request.importId() == null || request.importId().isBlank()) {
      throw new BadRequestException("import_id is required");
    }
    if (request.position() <= 0) {
      throw new BadRequestException("position must be positive");
    }
  }
}
