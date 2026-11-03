package com.jordansimsmith.tcginventory.listings.server;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.CreateListingImageRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.DeleteListingRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.inventory.client.UpdateSkuRequest;
import com.jordansimsmith.tcginventory.listings.client.UpdateListingRequest;
import com.jordansimsmith.tcginventory.listings.client.UpdateListingResponse;
import com.jordansimsmith.tcginventory.photos.client.GetPhotoBytesRequest;
import com.jordansimsmith.tcginventory.photos.client.PhotosService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateListingService {
  private static final Logger LOGGER = LoggerFactory.getLogger(UpdateListingService.class);

  private final ListingRepository listingRepository;
  private final InventoryService inventoryService;
  private final PhotosService photosService;
  private final FetchTcgService fetchTcgService;

  public UpdateListingService(
      ListingRepository listingRepository,
      InventoryService inventoryService,
      PhotosService photosService,
      FetchTcgService fetchTcgService) {
    this.listingRepository = listingRepository;
    this.inventoryService = inventoryService;
    this.photosService = photosService;
    this.fetchTcgService = fetchTcgService;
  }

  public UpdateListingResponse updateListing(RequestContext ctx, UpdateListingRequest request)
      throws ServiceException {
    var sku = inventoryService.getSku(ctx, new GetSkuRequest(request.skuId())).sku();
    var capturedVersion = sku.version();

    var units = new ArrayList<Unit>();
    String continuation = null;
    do {
      var response =
          inventoryService.findUnits(ctx, new FindUnitsRequest(sku.skuId(), 100, continuation));
      units.addAll(response.units());
      continuation = response.nextContinuation();
    } while (continuation != null);

    var inStock = units.stream().filter(unit -> unit.status() == UnitStatus.IN_STOCK).toList();

    if (inStock.isEmpty()) {
      var current = listingRepository.getCurrentListing(ctx.user(), sku.skuId());
      if (current != null) {
        fetchTcgService.deleteListing(ctx, new DeleteListingRequest(current.getListingId()));
        listingRepository.deleteCurrentListing(ctx.user(), sku.skuId());
      }
      inventoryService.updateSku(
          ctx, new UpdateSkuRequest(sku.skuId(), null, false, capturedVersion));
      return new UpdateListingResponse();
    }

    if (sku.price() == null) {
      throw new IllegalStateException("SKU has no price: " + sku.skuId());
    }
    if (sku.fetchtcgCardId() == null || sku.fetchtcgCardId().isBlank()) {
      throw new IllegalStateException("SKU has no FetchTCG card ID: " + sku.skuId());
    }

    var photos = inStock.getFirst().photos();
    var imageUrls = new ArrayList<String>();
    for (var photoId : photos) {
      var bytes = photosService.getPhotoBytes(ctx, new GetPhotoBytesRequest(photoId));
      var image =
          fetchTcgService.createListingImage(
              ctx, new CreateListingImageRequest(bytes.photoBytes(), photoId + ".jpg"));
      imageUrls.add(image.imageUrl());
    }

    var condition =
        switch (sku.condition()) {
          case NM -> "raw-nm";
          case LP -> "raw-lp";
          case MP -> "raw-mp";
          case HP -> "raw-hp";
          case DMG -> "raw-d";
        };

    if (sku.price().compareTo(new BigDecimal("50")) >= 0 && photos.isEmpty()) {
      LOGGER.warn("upserting photo-less sku {} at price {} (>= NZ$50)", sku.skuId(), sku.price());
    }

    var response =
        fetchTcgService.updateListing(
            ctx,
            new com.jordansimsmith.tcginventory.fetchtcg.client.UpdateListingRequest(
                sku.fetchtcgCardId(),
                condition,
                inStock.size(),
                sku.price(),
                imageUrls.isEmpty() ? null : imageUrls.getFirst(),
                imageUrls.size() <= 1 ? List.of() : imageUrls.subList(1, imageUrls.size())));

    listingRepository.saveListing(ctx.user(), sku.skuId(), response.listingId());

    inventoryService.updateSku(
        ctx, new UpdateSkuRequest(sku.skuId(), null, false, capturedVersion));

    return new UpdateListingResponse();
  }
}
