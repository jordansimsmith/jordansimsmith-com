package com.jordansimsmith.tcginventory.imports;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindCardsRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AppraisalCardResolver {
  private static final int PAGE_SIZE = 100;

  public static Optional<AppraisalCatalogs.AppraisalCatalog.ResolvedCard> resolve(
      String fetchTcgGameId,
      List<Integer> setIds,
      String searchName,
      String finish,
      String externalReferenceField,
      String externalId,
      RequestContext ctx,
      FetchTcgService fetchTcgService,
      Map<String, GetCardResponse> cardCache)
      throws ServiceException {
    Map<String, Integer> matchingCardSetIds = new LinkedHashMap<>();
    for (var setId : setIds) {
      String continuation = null;
      do {
        var response =
            fetchTcgService.findCards(
                ctx,
                new FindCardsRequest(
                    fetchTcgGameId, setId, searchName, finish, PAGE_SIZE, continuation));
        for (var result : response.cards()) {
          var cardDetails = cardCache.get(result.id());
          if (cardDetails == null) {
            cardDetails = fetchTcgService.getCard(ctx, new GetCardRequest(result.id()));
            cardCache.put(result.id(), cardDetails);
          }
          var externalReferences = cardDetails.externalReferences();
          if (externalReferences != null
              && externalId.equals(externalReferences.get(externalReferenceField))) {
            matchingCardSetIds.putIfAbsent(result.id(), setId);
          }
        }
        continuation = response.nextContinuation();
      } while (continuation != null);
    }
    if (matchingCardSetIds.size() != 1) {
      return Optional.empty();
    }
    var match = matchingCardSetIds.entrySet().iterator().next();
    return Optional.of(
        new AppraisalCatalogs.AppraisalCatalog.ResolvedCard(match.getKey(), match.getValue()));
  }
}
