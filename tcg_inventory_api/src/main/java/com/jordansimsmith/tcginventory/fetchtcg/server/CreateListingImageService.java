package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.fetchtcg.client.CreateListingImageRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.CreateListingImageResponse;

public class CreateListingImageService {
  private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
  private static final String RECONNECT_MESSAGE =
      "Save or re-save your FetchTCG refresh token in settings to verify your seller account.";

  private final FetchTcgTokenProvider tokenProvider;
  private final HttpFetchTcgClient fetchTcgClient;

  public CreateListingImageService(
      FetchTcgTokenProvider tokenProvider, HttpFetchTcgClient fetchTcgClient) {
    this.tokenProvider = tokenProvider;
    this.fetchTcgClient = fetchTcgClient;
  }

  public CreateListingImageResponse createListingImage(
      RequestContext ctx, CreateListingImageRequest request) throws ServiceException {
    if (request == null || request.imageBytes() == null || request.imageBytes().length == 0) {
      throw new BadRequestException("image_bytes is required");
    }
    if (request.imageBytes().length > MAX_IMAGE_BYTES) {
      throw new BadRequestException("image_bytes must not exceed 4 MiB");
    }
    if (request.filename() == null || request.filename().isBlank()) {
      throw new BadRequestException("filename is required");
    }
    if (request.filename().contains("\r")
        || request.filename().contains("\n")
        || request.filename().contains("\"")
        || request.filename().contains("\\")) {
      throw new BadRequestException("filename is invalid");
    }

    var bearerToken = tokenProvider.getBearer(ctx.user());
    try {
      return new CreateListingImageResponse(
          fetchTcgClient.createListingImage(bearerToken, request.imageBytes(), request.filename()));
    } catch (FetchTcgAuthenticationException e) {
      throw new ConflictException(RECONNECT_MESSAGE);
    } catch (NotFoundException e) {
      throw new ServiceUnavailableException("FetchTCG image upload is temporarily unavailable");
    }
  }
}
