package com.jordansimsmith.tcginventory.imports;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.CardIdentity;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindSetsRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardResponse;
import com.jordansimsmith.tcginventory.games.Games;
import com.jordansimsmith.tcginventory.imports.AppraisalCatalogs.AppraisalCatalog;
import java.text.Normalizer;
import java.util.Map;

public class MagicTheGatheringAppraisalCatalog implements AppraisalCatalog {
  private static final String FETCHTCG_GAME_ID = "mtg";
  private static final String FETCHTCG_EXTERNAL_REFERENCE_FIELD = "scryfallId";

  @Override
  public Result resolve(
      CardIdentity identity,
      ImportRowItem rowItem,
      RequestContext ctx,
      FetchTcgService fetchTcgService,
      Map<String, GetCardResponse> cardCache)
      throws ServiceException {
    if (!Games.MAGIC_THE_GATHERING.id().equals(identity.game())) {
      throw new IllegalArgumentException(
          "unsupported game for Magic appraisal: " + identity.game());
    }
    var fetchTcgFinish =
        switch (rowItem.getFinish()) {
          case "normal" -> "normal";
          case "foil" -> "foil";
          case "etched" -> "foil";
          default ->
              throw new IllegalArgumentException(
                  "unsupported finish for game: " + rowItem.getFinish());
        };
    if (!"en".equals(rowItem.getLanguage())) {
      return Result.review("non-english");
    }

    var setCode = rowItem.getSetCode();
    var sets = fetchTcgService.findSets(ctx, new FindSetsRequest("mtg", setCode)).sets();
    if (sets.isEmpty()) {
      return Result.review("unmapped set");
    }

    var searchName =
        rowItem.getName().contains("//")
            ? rowItem.getName().split("//")[0].trim()
            : rowItem.getName();
    // fetchtcg catalog names are ascii, so fold accents before matching.
    searchName = Normalizer.normalize(searchName, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    var resolvedCard =
        AppraisalCardResolver.resolve(
            FETCHTCG_GAME_ID,
            sets.stream().map(set -> set.id()).toList(),
            searchName,
            fetchTcgFinish,
            FETCHTCG_EXTERNAL_REFERENCE_FIELD,
            identity.externalId(),
            ctx,
            fetchTcgService,
            cardCache);
    return resolvedCard
        .map(card -> Result.resolved(new ResolvedCard(card.cardId(), card.setId())))
        .orElseGet(() -> Result.review("unresolvable"));
  }
}
