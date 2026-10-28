package com.jordansimsmith.tcginventory.imports;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.CardIdentity;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardResponse;
import java.util.Map;

public class AppraisalCatalogs {
  public interface AppraisalCatalog {
    Result resolve(
        CardIdentity identity,
        ImportRowItem rowItem,
        RequestContext ctx,
        FetchTcgService fetchTcgService,
        Map<String, GetCardResponse> cardCache)
        throws ServiceException;

    record ResolvedCard(String cardId, int setId) {}

    record Result(ResolvedCard card, String reviewReason) {
      public static Result resolved(ResolvedCard card) {
        return new Result(card, null);
      }

      public static Result review(String reason) {
        return new Result(null, reason);
      }
    }
  }

  private static final Map<String, AppraisalCatalog> CATALOGS =
      Map.of("mtg", new MagicTheGatheringAppraisalCatalog());

  public static AppraisalCatalog get(String gameId) {
    var catalog = CATALOGS.get(gameId);
    if (catalog == null) {
      throw new IllegalArgumentException("no appraisal catalog configured for game: " + gameId);
    }
    return catalog;
  }
}
