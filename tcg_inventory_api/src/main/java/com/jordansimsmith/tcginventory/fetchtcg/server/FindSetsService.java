package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.fetchtcg.client.CardSet;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindSetsRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindSetsResponse;

public class FindSetsService {
  public FindSetsResponse findSets(RequestContext ctx, FindSetsRequest request)
      throws BadRequestException {
    if (request == null || request.gameId() == null || request.gameId().isBlank()) {
      throw new BadRequestException("game_id is required");
    }
    if (request.externalSetId() == null || request.externalSetId().isBlank()) {
      throw new BadRequestException("external_set_id is required");
    }
    if (!"mtg".equals(request.gameId())) {
      throw new BadRequestException("game_id is not supported for set mapping");
    }
    var sets =
        FetchTcgSetMapping.get(request.externalSetId()).stream()
            .map(set -> new CardSet(set.setId(), set.setName()))
            .toList();
    return new FindSetsResponse(sets);
  }
}
