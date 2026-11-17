package com.jordansimsmith.tcginventory.imports.server;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.CardSet;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindSetsRequest;

public class PokemonJpAppraisalResolver implements AppraisalResolver {
  private static final String GAME = "pkjp";

  private final FetchTcgService fetchTcgService;
  private final FetchTcgCardMatcher cardMatcher;

  public PokemonJpAppraisalResolver(
      FetchTcgService fetchTcgService, FetchTcgCardMatcher cardMatcher) {
    this.fetchTcgService = fetchTcgService;
    this.cardMatcher = cardMatcher;
  }

  @Override
  public AppraisalResult resolve(RequestContext ctx, ImportRowItem row) throws ServiceException {
    var finish =
        switch (row.getFinish()) {
          case "normal" -> "normal";
          case "holofoil" -> "foil";
          default ->
              throw new IllegalArgumentException("unsupported finish for game: " + row.getFinish());
        };
    var setCode = row.getSetCode();
    if (setCode == null || setCode.isBlank()) {
      return review("unmapped set");
    }
    var sets = fetchTcgService.findSets(ctx, new FindSetsRequest(GAME, setCode)).sets();
    if (sets.isEmpty()) {
      return review("unmapped set");
    }
    var match =
        cardMatcher.findMatch(
            ctx,
            GAME,
            sets.stream().map(CardSet::id).toList(),
            row.getName(),
            finish,
            "tcgId",
            row.getExternalId());
    if (match.reason() != null) {
      return review(match.reason());
    }
    return new AppraisalResult(
        AppraisalResult.Decision.RESOLVED, null, match.cardId(), match.setId());
  }

  private static AppraisalResult review(String notes) {
    return new AppraisalResult(AppraisalResult.Decision.REVIEW, notes, null, null);
  }
}
