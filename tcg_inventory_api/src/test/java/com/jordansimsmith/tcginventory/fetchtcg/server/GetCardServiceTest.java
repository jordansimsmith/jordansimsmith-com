package com.jordansimsmith.tcginventory.fetchtcg.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardRequest;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class GetCardServiceTest {
  private final HttpFetchTcgClient fetchTcgClient = mock(HttpFetchTcgClient.class);
  private final GetCardService service = new GetCardService(fetchTcgClient);
  private final RequestContext ctx = new RequestContext("alice");

  @Test
  void getCardShouldReturnProviderFacts() throws ServiceException {
    // arrange
    when(fetchTcgClient.getCard("card"))
        .thenReturn(
            new HttpFetchTcgClient.GetCardResponse(
                "card", "Exact Card Name", new BigDecimal("1.257"), Map.of("scryfallId", "id")));

    // act
    var response = service.getCard(ctx, new GetCardRequest("card"));

    // assert
    assertThat(response.id()).isEqualTo("card");
    assertThat(response.name()).isEqualTo("Exact Card Name");
    assertThat(response.marketPrice()).isEqualByComparingTo("1.257");
    assertThat(response.externalReferences()).containsEntry("scryfallId", "id");
    verify(fetchTcgClient).getCard("card");
  }

  @Test
  void getCardShouldRejectBlankIdsBeforeProviderCall() {
    for (var request :
        new GetCardRequest[] {null, new GetCardRequest(null), new GetCardRequest(" ")}) {
      // act
      var thrown = catchThrowable(() -> service.getCard(ctx, request));

      // assert
      assertThat(thrown).isInstanceOf(BadRequestException.class).hasMessage("card_id is required");
    }
    verifyNoInteractions(fetchTcgClient);
  }

  @Test
  void getCardShouldReturnEmptyReferencesWhenProviderOmitsThem() throws ServiceException {
    // arrange
    when(fetchTcgClient.getCard("card"))
        .thenReturn(new HttpFetchTcgClient.GetCardResponse("card", "Card", null, null));

    // act
    var response = service.getCard(ctx, new GetCardRequest("card"));

    // assert
    assertThat(response.externalReferences()).isEmpty();
  }
}
