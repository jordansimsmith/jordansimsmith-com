package com.jordansimsmith.tcginventory.imports;

import com.jordansimsmith.tcginventory.CardIdentity;
import com.jordansimsmith.tcginventory.Games;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgCardResolver;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgClient;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgSetMapping;
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
      FetchTcgClient fetchTcgClient,
      Map<String, FetchTcgClient.GetCardResponse> cardCache) {
    if (!Games.MAGIC_THE_GATHERING.id().equals(identity.game())) {
      throw new IllegalArgumentException(
          "unsupported game for Magic appraisal: " + identity.game());
    }
    if (!Games.MAGIC_THE_GATHERING.externalSource().equals(identity.externalSource())) {
      throw new IllegalArgumentException(
          "unsupported external source for game "
              + identity.game()
              + ": "
              + identity.externalSource());
    }
    if (!Games.MAGIC_THE_GATHERING.finishes().contains(rowItem.getFinish())) {
      throw new IllegalArgumentException("unsupported finish for game: " + rowItem.getFinish());
    }
    if (!"en".equals(rowItem.getLanguage())) {
      return Result.review("non-english");
    }

    var setCode = rowItem.getSetCode();
    if (!FetchTcgSetMapping.contains(setCode)) {
      return Result.review("unmapped set");
    }

    var searchName =
        rowItem.getName().contains("//")
            ? rowItem.getName().split("//")[0].trim()
            : rowItem.getName();
    // fetchtcg catalog names are ascii, so fold accents before matching.
    searchName = Normalizer.normalize(searchName, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    var resolvedCard =
        FetchTcgCardResolver.resolve(
            FETCHTCG_GAME_ID,
            FetchTcgSetMapping.get(setCode).stream()
                .map(FetchTcgSetMapping.FetchTcgSetEntry::setId)
                .toList(),
            searchName,
            rowItem.getFinish(),
            FETCHTCG_EXTERNAL_REFERENCE_FIELD,
            identity.externalId(),
            fetchTcgClient,
            cardCache);
    return resolvedCard
        .map(
            card ->
                Result.resolved(new ResolvedCard(card.cardId(), card.setId(), card.marketPrice())))
        .orElseGet(() -> Result.review("unresolvable"));
  }
}
