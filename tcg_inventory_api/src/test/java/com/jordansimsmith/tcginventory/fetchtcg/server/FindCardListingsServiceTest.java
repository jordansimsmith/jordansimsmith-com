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
import com.jordansimsmith.tcginventory.fetchtcg.client.FindCardListingsRequest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

public class FindCardListingsServiceTest {
  private final HttpFetchTcgClient fetchTcgClient = mock(HttpFetchTcgClient.class);
  private final FindCardListingsService service = new FindCardListingsService(fetchTcgClient);
  private final RequestContext ctx = new RequestContext("alice");

  @Test
  void findCardListingsShouldDefaultToOneHundredAndEncodeNextPage() throws ServiceException {
    // arrange
    var providerListing =
        new HttpFetchTcgClient.ProviderCardListing(1, "raw-nm", new BigDecimal("1.25"), "seller");
    when(fetchTcgClient.findCardListings("card", 100, 0))
        .thenReturn(new HttpFetchTcgClient.ListingsPage(0, 100, false, List.of(providerListing)));

    // act
    var response = service.findCardListings(ctx, new FindCardListingsRequest("card", null, null));

    // assert
    assertThat(response.listings()).hasSize(1);
    assertThat(response.listings().getFirst().sellerUsername()).isEqualTo("seller");
    assertThat(response.nextContinuation()).isEqualTo("1");
  }

  @Test
  void findCardListingsShouldReuseContinuationAndReturnNullWhenLast() throws ServiceException {
    // arrange
    when(fetchTcgClient.findCardListings("card", 2, 1))
        .thenReturn(new HttpFetchTcgClient.ListingsPage(1, 2, true, List.of()));

    // act
    var response = service.findCardListings(ctx, new FindCardListingsRequest("card", 2, "1"));

    // assert
    assertThat(response.listings()).isEmpty();
    assertThat(response.nextContinuation()).isNull();
    verify(fetchTcgClient).findCardListings("card", 2, 1);
  }

  @Test
  void findCardListingsShouldPassChangedParametersThroughWithOpaqueOffset()
      throws ServiceException {
    // arrange
    when(fetchTcgClient.findCardListings("another-card", 7, 3))
        .thenReturn(new HttpFetchTcgClient.ListingsPage(3, 7, true, List.of()));

    // act
    var response =
        service.findCardListings(ctx, new FindCardListingsRequest("another-card", 7, "3"));

    // assert
    assertThat(response.nextContinuation()).isNull();
    verify(fetchTcgClient).findCardListings("another-card", 7, 3);
  }

  @Test
  void findCardListingsShouldRejectInvalidInputsBeforeProviderCall() {
    for (var request :
        new FindCardListingsRequest[] {
          new FindCardListingsRequest(" ", null, null),
          new FindCardListingsRequest("card", 0, null),
          new FindCardListingsRequest("card", 101, null),
          new FindCardListingsRequest("card", null, "not-a-token"),
          new FindCardListingsRequest("card", null, "0"),
          new FindCardListingsRequest("card", null, "-1")
        }) {
      // act
      var thrown = catchThrowable(() -> service.findCardListings(ctx, request));

      // assert
      assertThat(thrown).isInstanceOf(BadRequestException.class);
    }
    verifyNoInteractions(fetchTcgClient);
  }

  @Test
  void findCardListingsShouldRejectMismatchedProviderPageMetadata() throws ServiceException {
    // arrange
    when(fetchTcgClient.findCardListings("card", 2, 0))
        .thenReturn(new HttpFetchTcgClient.ListingsPage(1, 2, true, List.of()));

    // act
    var thrown =
        catchThrowable(
            () -> service.findCardListings(ctx, new FindCardListingsRequest("card", 2, null)));

    // assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("FetchTCG listings response contained invalid pagination");
    verify(fetchTcgClient).findCardListings("card", 2, 0);
  }
}
