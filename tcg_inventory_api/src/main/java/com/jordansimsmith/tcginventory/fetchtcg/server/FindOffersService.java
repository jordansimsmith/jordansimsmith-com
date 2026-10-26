package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.fetchtcg.client.BuyerAddress;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindOffersRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindOffersResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.Offer;
import com.jordansimsmith.tcginventory.fetchtcg.client.OfferItem;
import com.jordansimsmith.tcginventory.fetchtcg.client.OfferListing;
import com.jordansimsmith.tcginventory.fetchtcg.client.ShippingOption;
import java.util.List;

public class FindOffersService {
  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 100;
  private static final String RECONNECT_MESSAGE =
      "Save or re-save your FetchTCG refresh token in settings to verify your seller account.";

  private final FetchTcgTokenProvider tokenProvider;
  private final HttpFetchTcgClient fetchTcgClient;

  public FindOffersService(FetchTcgTokenProvider tokenProvider, HttpFetchTcgClient fetchTcgClient) {
    this.tokenProvider = tokenProvider;
    this.fetchTcgClient = fetchTcgClient;
  }

  public FindOffersResponse findOffers(RequestContext ctx, FindOffersRequest request)
      throws ServiceException {
    if (request == null) {
      throw new BadRequestException("request is required");
    }
    var limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new BadRequestException("limit must be between 1 and 100");
    }
    var page = request.continuation() == null ? 0 : decodeContinuation(request.continuation());

    var bearerToken = tokenProvider.getBearer(ctx.user());
    HttpFetchTcgClient.OffersPage providerPage;
    try {
      providerPage = fetchTcgClient.findOffers(bearerToken, limit, page);
    } catch (FetchTcgAuthenticationException e) {
      throw new ConflictException(RECONNECT_MESSAGE);
    } catch (NotFoundException e) {
      throw new ServiceUnavailableException("FetchTCG seller offers are temporarily unavailable");
    }

    if (providerPage.number() == null
        || providerPage.number() != page
        || providerPage.size() == null
        || providerPage.size() != limit
        || providerPage.last() == null
        || providerPage.content() == null
        || providerPage.content().size() > limit) {
      throw new IllegalStateException(
          "FetchTCG seller offers response contained invalid pagination");
    }
    if (!providerPage.last() && page == Integer.MAX_VALUE) {
      throw new IllegalStateException("FetchTCG seller offers response exceeded the page range");
    }

    var offers = providerPage.content().stream().map(FindOffersService::toOffer).toList();
    var nextContinuation = providerPage.last() ? null : encodeContinuation(page + 1);
    return new FindOffersResponse(offers, nextContinuation);
  }

  private int decodeContinuation(String continuation) throws BadRequestException {
    try {
      var page = Integer.parseInt(continuation);
      if (page < 1) {
        throw new BadRequestException("continuation is invalid");
      }
      return page;
    } catch (NumberFormatException e) {
      throw new BadRequestException("continuation is invalid");
    }
  }

  private String encodeContinuation(int page) {
    return Integer.toString(page);
  }

  private static Offer toOffer(HttpFetchTcgClient.ProviderOffer providerOffer) {
    if (providerOffer == null) {
      throw new IllegalStateException("FetchTCG seller offers response contained an invalid offer");
    }
    if (providerOffer.id() == null) {
      throw new IllegalStateException(
          "FetchTCG seller offers response contained an offer without an id");
    }
    var buyerAddress =
        providerOffer.buyerRegionAddress() == null
            ? null
            : new BuyerAddress(
                providerOffer.buyerRegionAddress().line1(),
                providerOffer.buyerRegionAddress().line2(),
                providerOffer.buyerRegionAddress().suburb(),
                providerOffer.buyerRegionAddress().city(),
                providerOffer.buyerRegionAddress().postCode(),
                providerOffer.buyerRegionAddress().country());
    var shippingOption =
        providerOffer.shippingOption() == null
            ? null
            : new ShippingOption(providerOffer.shippingOption().title());
    List<OfferItem> items =
        providerOffer.items() == null
            ? null
            : providerOffer.items().stream().map(FindOffersService::toOfferItem).toList();
    return new Offer(
        providerOffer.id(),
        providerOffer.status(),
        providerOffer.currentAction(),
        providerOffer.acceptedAt(),
        providerOffer.deliveryMode(),
        providerOffer.buyerName(),
        buyerAddress,
        shippingOption,
        providerOffer.totalOfferPrice(),
        items);
  }

  private static OfferItem toOfferItem(HttpFetchTcgClient.ProviderOfferItem providerItem) {
    if (providerItem == null) {
      throw new IllegalStateException("FetchTCG seller offers response contained an invalid item");
    }
    var listing = providerItem.listing() == null ? null : toOfferListing(providerItem.listing());
    return new OfferItem(listing, providerItem.quantity(), providerItem.price());
  }

  private static OfferListing toOfferListing(
      HttpFetchTcgClient.ProviderOfferListing providerListing) {
    if (providerListing.id() == null || providerListing.condition() == null) {
      throw new IllegalStateException(
          "FetchTCG seller offers response contained an invalid listing");
    }
    return new OfferListing(
        providerListing.id(), providerListing.condition(), providerListing.listedPrice());
  }
}
