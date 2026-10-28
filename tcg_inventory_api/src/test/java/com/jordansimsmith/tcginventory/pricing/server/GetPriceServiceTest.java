package com.jordansimsmith.tcginventory.pricing.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.fetchtcg.client.CardListing;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetAccountRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetAccountResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardResponse;
import com.jordansimsmith.tcginventory.pricing.client.Condition;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceRequest;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GetPriceServiceTest {
  private final RequestContext ctx = new RequestContext("alice");

  @Test
  void getPriceShouldApplyCompetitorPolicyAndExcludeTheSavedSeller() throws Exception {
    // arrange
    var pricingRepository = mock(PricingRepository.class);
    var fetchTcgService = new FakeFetchTcgService();
    fetchTcgService.seedSellerUsername("alice", "seller-a");
    fetchTcgService.seedCard(
        "card-1", new GetCardResponse("card-1", "Card", new BigDecimal("5"), Map.of()));
    fetchTcgService.seedListings(
        "card-1",
        List.of(
            new CardListing(1, "raw-nm", new BigDecimal("5.00"), "seller-a"),
            new CardListing(2, "raw-nm", new BigDecimal("5.00"), "seller-b")));

    // act
    var response =
        new GetPriceService(pricingRepository, fetchTcgService)
            .getPrice(ctx, new GetPriceRequest("card-1", Condition.NM));

    // assert
    assertThat(response.marketPrice()).isEqualByComparingTo("5.00");
    assertThat(response.marketPrice().scale()).isEqualTo(2);
    assertThat(response.suggestedPrice()).isEqualByComparingTo("4.85");
    assertThat(response.suggestedPrice().scale()).isEqualTo(2);
    verify(pricingRepository).get("alice");
  }

  @Test
  void getPriceShouldUseDefaultFloorAndZeroForMissingMarketPrice() throws Exception {
    // arrange
    var pricingRepository = mock(PricingRepository.class);
    var fetchTcgService = new FakeFetchTcgService();
    fetchTcgService.seedCard("card-1", new GetCardResponse("card-1", "Card", null, Map.of()));

    // act
    var response =
        new GetPriceService(pricingRepository, fetchTcgService)
            .getPrice(ctx, new GetPriceRequest("card-1", Condition.LP));

    // assert
    assertThat(response.marketPrice()).isEqualByComparingTo("0.00");
    assertThat(response.suggestedPrice()).isEqualByComparingTo("0.25");
  }

  @Test
  void getPriceShouldRejectInvalidInputBeforeReadingDependencies() {
    // arrange
    var pricingRepository = mock(PricingRepository.class);
    var fetchTcgService = mock(FetchTcgService.class);
    var service = new GetPriceService(pricingRepository, fetchTcgService);

    // act & assert
    assertThatThrownBy(() -> service.getPrice(ctx, new GetPriceRequest("  ", Condition.NM)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("fetchtcg_card_id is required");
    assertThatThrownBy(() -> service.getPrice(ctx, new GetPriceRequest("card-1", null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("condition is required");
    verifyNoInteractions(pricingRepository, fetchTcgService);
  }

  @Test
  void getPriceShouldPropagateFetchTcgNotFound() throws Exception {
    // arrange
    var pricingRepository = mock(PricingRepository.class);
    var fetchTcgService = mock(FetchTcgService.class);
    var expected = new NotFoundException("account is not configured");
    when(fetchTcgService.getAccount(ctx, new GetAccountRequest())).thenThrow(expected);

    // act & assert
    assertThatThrownBy(
            () ->
                new GetPriceService(pricingRepository, fetchTcgService)
                    .getPrice(ctx, new GetPriceRequest("card-1", Condition.NM)))
        .isSameAs(expected);
  }

  @Test
  void getPriceShouldPropagateCardNotFoundUnchanged() throws Exception {
    // arrange
    var pricingRepository = mock(PricingRepository.class);
    var fetchTcgService = mock(FetchTcgService.class);
    var expected = new NotFoundException("FetchTCG card was not found");
    when(fetchTcgService.getAccount(ctx, new GetAccountRequest()))
        .thenReturn(new GetAccountResponse("alice", 0));
    when(fetchTcgService.getCard(ctx, new GetCardRequest("card-1"))).thenThrow(expected);

    // act & assert
    assertThatThrownBy(
            () ->
                new GetPriceService(pricingRepository, fetchTcgService)
                    .getPrice(ctx, new GetPriceRequest("card-1", Condition.NM)))
        .isSameAs(expected);
  }

  @Test
  void getPriceShouldPropagateCardAndProviderFailuresUnchanged() throws Exception {
    // arrange
    var pricingRepository = mock(PricingRepository.class);
    var fetchTcgService = mock(FetchTcgService.class);
    var expected = new ServiceUnavailableException("FetchTCG is temporarily unavailable");
    when(fetchTcgService.getAccount(ctx, new GetAccountRequest()))
        .thenReturn(new GetAccountResponse("alice", 0));
    when(fetchTcgService.getCard(ctx, new GetCardRequest("card-1"))).thenThrow(expected);

    // act & assert
    assertThatThrownBy(
            () ->
                new GetPriceService(pricingRepository, fetchTcgService)
                    .getPrice(ctx, new GetPriceRequest("card-1", Condition.NM)))
        .isSameAs(expected);
  }

  @Test
  void getPriceShouldPropagateStorageFailuresUnchangedBeforeProviderAccess() {
    // arrange
    var pricingRepository = mock(PricingRepository.class);
    var expected = new IllegalStateException("storage failure");
    when(pricingRepository.get("alice")).thenThrow(expected);
    var fetchTcgService = mock(FetchTcgService.class);

    // act & assert
    assertThatThrownBy(
            () ->
                new GetPriceService(pricingRepository, fetchTcgService)
                    .getPrice(ctx, new GetPriceRequest("card-1", Condition.NM)))
        .isSameAs(expected);
    verifyNoInteractions(fetchTcgService);
  }

  @Test
  void getPriceShouldFailOnMalformedSavedFloorBeforeProviderAccess() {
    // arrange
    var pricingRepository = mock(PricingRepository.class);
    when(pricingRepository.get("alice")).thenReturn(PricingItem.create("alice", "0.001"));
    var fetchTcgService = mock(FetchTcgService.class);

    // act & assert
    assertThatThrownBy(
            () ->
                new GetPriceService(pricingRepository, fetchTcgService)
                    .getPrice(ctx, new GetPriceRequest("card-1", Condition.NM)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("saved price floor is invalid");
    verifyNoInteractions(fetchTcgService);
  }

  @Test
  void requestAndResponseShouldUseSnakeCaseJsonProperties() throws Exception {
    // arrange
    var objectMapper = new ObjectMapper();
    var request =
        objectMapper.readValue(
            "{\"fetchtcg_card_id\":\"card-1\",\"condition\":\"DMG\"}", GetPriceRequest.class);

    // act
    var json =
        objectMapper.readTree(
            objectMapper.writeValueAsString(
                new GetPriceResponse(new BigDecimal("1.20"), new BigDecimal("1.25"))));

    // assert
    assertThat(request).isEqualTo(new GetPriceRequest("card-1", Condition.DMG));
    assertThat(json.get("market_price").decimalValue()).isEqualByComparingTo("1.20");
    assertThat(json.get("suggested_price").decimalValue()).isEqualByComparingTo("1.25");
    assertThat(json.size()).isEqualTo(2);
  }
}
