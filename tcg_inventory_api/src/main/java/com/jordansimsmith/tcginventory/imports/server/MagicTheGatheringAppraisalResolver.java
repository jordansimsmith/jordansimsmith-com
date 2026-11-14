package com.jordansimsmith.tcginventory.imports.server;

import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindSetsRequest;
import java.text.Normalizer;

public class MagicTheGatheringAppraisalResolver implements AppraisalResolver {
  private final FetchTcgService fetchTcgService;
  private final FetchTcgCardMatcher cardMatcher;

  public MagicTheGatheringAppraisalResolver(
      FetchTcgService fetchTcgService, FetchTcgCardMatcher cardMatcher) {
    this.fetchTcgService = fetchTcgService;
    this.cardMatcher = cardMatcher;
  }

  @Override
  public AppraisalResult resolve(RequestContext ctx, ImportRowItem row) throws ServiceException {
    var fetchTcgFinish =
        switch (row.getFinish()) {
          case "normal" -> "normal";
          case "foil", "etched" -> "foil";
          default ->
              throw new IllegalArgumentException("unsupported finish for game: " + row.getFinish());
        };
    var sets = fetchTcgService.findSets(ctx, new FindSetsRequest("mtg", row.getSetCode())).sets();
    if (sets.isEmpty()) {
      return review("unmapped set");
    }
    var searchName =
        row.getName().contains("//") ? row.getName().split("//")[0].trim() : row.getName();
    searchName = Normalizer.normalize(searchName, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    var match =
        cardMatcher.findMatch(
            ctx,
            "mtg",
            sets.stream().map(set -> set.id()).toList(),
            searchName,
            fetchTcgFinish,
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
