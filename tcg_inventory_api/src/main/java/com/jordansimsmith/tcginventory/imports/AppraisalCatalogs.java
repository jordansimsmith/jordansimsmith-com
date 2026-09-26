package com.jordansimsmith.tcginventory.imports;

import com.jordansimsmith.tcginventory.CardIdentity;
import com.jordansimsmith.tcginventory.Games;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgClient;
import java.math.BigDecimal;
import java.util.Map;

public class AppraisalCatalogs {
  public interface AppraisalCatalog {
    Result resolve(
        CardIdentity identity,
        ImportRowItem rowItem,
        FetchTcgClient fetchTcgClient,
        Map<String, FetchTcgClient.GetCardResponse> cardCache);

    record ResolvedCard(String cardId, int setId, BigDecimal marketPrice) {}

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
      Map.of(Games.MAGIC_THE_GATHERING.id(), new MagicTheGatheringAppraisalCatalog());

  public static AppraisalCatalog get(String gameId) {
    Games.get(gameId);
    var catalog = CATALOGS.get(gameId);
    if (catalog == null) {
      throw new IllegalArgumentException("no appraisal catalog configured for game: " + gameId);
    }
    return catalog;
  }
}
