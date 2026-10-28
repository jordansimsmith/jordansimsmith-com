package com.jordansimsmith.tcginventory.fetchtcg.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.fetchtcg.client.DeleteListingRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateListingRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateListingResponse;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ListingMutationServiceTest {
  private static final RequestContext CTX = new RequestContext("alice");
  private static final String RECONNECT_MESSAGE =
      "Save or re-save your FetchTCG refresh token in settings to verify your seller account.";

  @Test
  void updateListingShouldUseContextUserBearer() throws Exception {
    // arrange
    var tokenProvider = mock(FetchTcgTokenProvider.class);
    var fetchTcgClient = mock(HttpFetchTcgClient.class);
    var request =
        new UpdateListingRequest("card-1", "raw-nm", 2, new BigDecimal("1.50"), null, List.of());
    var response = new UpdateListingResponse(975737, 2);
    when(tokenProvider.getBearer("alice")).thenReturn("bearer");
    when(fetchTcgClient.updateListing("bearer", request)).thenReturn(response);
    var service = new UpdateListingService(tokenProvider, fetchTcgClient);

    // act
    var result = service.updateListing(CTX, request);

    // assert
    assertThat(result).isEqualTo(response);
    verify(tokenProvider).getBearer("alice");
    verify(fetchTcgClient).updateListing("bearer", request);
  }

  @Test
  void updateListingShouldTranslateProviderAuthenticationFailure() throws Exception {
    // arrange
    var tokenProvider = mock(FetchTcgTokenProvider.class);
    var fetchTcgClient = mock(HttpFetchTcgClient.class);
    when(tokenProvider.getBearer("alice")).thenReturn("bearer");
    when(fetchTcgClient.updateListing(
            "bearer", new UpdateListingRequest("card-1", "raw-nm", 1, BigDecimal.ONE, null, null)))
        .thenThrow(new FetchTcgAuthenticationException(401));
    var service = new UpdateListingService(tokenProvider, fetchTcgClient);

    // act / assert
    assertThatThrownBy(
            () ->
                service.updateListing(
                    CTX,
                    new UpdateListingRequest("card-1", "raw-nm", 1, BigDecimal.ONE, null, null)))
        .isInstanceOf(ConflictException.class)
        .hasMessage(RECONNECT_MESSAGE);
  }

  @Test
  void deleteListingShouldTranslateProviderAuthenticationFailure() throws Exception {
    // arrange
    var tokenProvider = mock(FetchTcgTokenProvider.class);
    var fetchTcgClient = mock(HttpFetchTcgClient.class);
    when(tokenProvider.getBearer("alice")).thenReturn("bearer");
    doThrow(new FetchTcgAuthenticationException(403))
        .when(fetchTcgClient)
        .deleteListing("bearer", 975737);
    var service = new DeleteListingService(tokenProvider, fetchTcgClient);

    // act / assert
    assertThatThrownBy(() -> service.deleteListing(CTX, new DeleteListingRequest(975737)))
        .isInstanceOf(ConflictException.class)
        .hasMessage(RECONNECT_MESSAGE);
  }
}
