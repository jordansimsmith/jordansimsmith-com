package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.jordansimsmith.service.RequestContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FakeFetchTcgService implements FetchTcgService {
  private final Map<String, String> sellerUsernames = new HashMap<>();
  private final Map<String, GetCardResponse> cards = new HashMap<>();
  private final Map<String, List<CardListing>> listings = new HashMap<>();
  private final Map<String, List<Offer>> offers = new HashMap<>();
  private final Map<String, Integer> listingIds = new HashMap<>();
  private int nextListingId = 900000;

  @Override
  public GetAccountResponse getAccount(RequestContext ctx, GetAccountRequest request) {
    return new GetAccountResponse(sellerUsernames.getOrDefault(ctx.user(), ctx.user()), 0L);
  }

  @Override
  public UpdateCredentialsResponse updateCredentials(
      RequestContext ctx, UpdateCredentialsRequest request) {
    var username = sellerUsernames.getOrDefault(ctx.user(), ctx.user());
    return new UpdateCredentialsResponse(username, 0L);
  }

  @Override
  public GetCardResponse getCard(RequestContext ctx, GetCardRequest request) {
    return cards.getOrDefault(
        request.cardId(), new GetCardResponse(request.cardId(), "Unknown", null, Map.of()));
  }

  @Override
  public FindCardListingsResponse findCardListings(
      RequestContext ctx, FindCardListingsRequest request) {
    var page = request.continuation() == null ? 0 : Integer.parseInt(request.continuation());
    var limit = request.limit() == null ? 100 : request.limit();
    var allListings = listings.getOrDefault(request.cardId(), List.of());
    var start = page * limit;
    var end = Math.min(start + limit, allListings.size());
    var pageListings =
        start >= allListings.size() ? List.<CardListing>of() : allListings.subList(start, end);
    var nextContinuation = end < allListings.size() ? Integer.toString(page + 1) : null;
    return new FindCardListingsResponse(pageListings, nextContinuation);
  }

  @Override
  public FindOffersResponse findOffers(RequestContext ctx, FindOffersRequest request) {
    var page = request.continuation() == null ? 0 : Integer.parseInt(request.continuation());
    var limit = request.limit() == null ? 100 : request.limit();
    var allOffers = offers.getOrDefault(ctx.user(), List.of());
    var start = page * limit;
    var end = Math.min(start + limit, allOffers.size());
    var pageOffers = start >= allOffers.size() ? List.<Offer>of() : allOffers.subList(start, end);
    var nextContinuation = end < allOffers.size() ? Integer.toString(page + 1) : null;
    return new FindOffersResponse(pageOffers, nextContinuation);
  }

  @Override
  public UpdateListingResponse updateListing(RequestContext ctx, UpdateListingRequest request) {
    var listingKey = ctx.user() + "#" + request.cardId() + "#" + request.condition();
    var listingId = listingIds.computeIfAbsent(listingKey, key -> nextListingId++);
    return new UpdateListingResponse(listingId, request.quantity());
  }

  @Override
  public DeleteListingResponse deleteListing(RequestContext ctx, DeleteListingRequest request) {
    listingIds.entrySet().removeIf(
        entry ->
            entry.getKey().startsWith(ctx.user() + "#")
                && entry.getValue() == request.listingId());
    return new DeleteListingResponse();
  }

  public void seedSellerUsername(String user, String username) {
    sellerUsernames.put(user, username);
  }

  public void seedCard(GetCardResponse response) {
    cards.put(response.id(), response);
  }

  public void seedListings(String cardId, List<CardListing> response) {
    listings.put(cardId, response);
  }

  public void seedOffers(String user, List<Offer> response) {
    offers.put(user, response);
  }

  public void reset() {
    sellerUsernames.clear();
    cards.clear();
    listings.clear();
    offers.clear();
    listingIds.clear();
    nextListingId = 900000;
  }
}
