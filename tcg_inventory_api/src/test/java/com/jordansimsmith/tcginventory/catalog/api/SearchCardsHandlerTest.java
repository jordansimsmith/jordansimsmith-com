package com.jordansimsmith.tcginventory.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.catalog.client.Card;
import com.jordansimsmith.tcginventory.catalog.client.CatalogService;
import com.jordansimsmith.tcginventory.catalog.client.ImageUrls;
import com.jordansimsmith.tcginventory.catalog.client.SearchCardsRequest;
import com.jordansimsmith.tcginventory.catalog.client.SearchCardsResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SearchCardsHandlerTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final CatalogService catalogService = mock(CatalogService.class);
  private SearchCardsHandler handler;

  @BeforeEach
  void setUp() {
    var factory = mock(TcgInventoryFactory.class);
    when(factory.catalogService()).thenReturn(catalogService);
    when(factory.requestContextFactory()).thenReturn(new RequestContextFactory());
    when(factory.httpResponseFactory())
        .thenReturn(
            new HttpResponseFactory.Builder(objectMapper)
                .withAllowedOrigin("https://tcg-inventory.jordansimsmith.com")
                .build());
    handler = new SearchCardsHandler(factory);
  }

  @Test
  void handleRequestShouldReturnServiceResponseUnchanged() throws Exception {
    // arrange
    var event =
        objectMapper.readValue(
            """
            {
              "headers": {"Authorization": "%s"},
              "queryStringParameters": {
                "game": "mtg",
                "query": " Lightning Bolt ",
                "finish": "normal",
                "limit": "7",
                "continuation": "next/page"
              }
            }
            """
                .formatted(AUTH_HEADER),
            APIGatewayV2HTTPEvent.class);
    var response =
        new SearchCardsResponse(
            List.of(
                new Card(
                    "mtg",
                    "card-id",
                    "Lightning Bolt",
                    "lea",
                    "Limited Edition Alpha",
                    "161",
                    new ImageUrls("small.jpg", "normal.jpg"),
                    List.of("normal", "foil"))),
            "next-page");
    when(catalogService.searchCards(any(), any())).thenReturn(response);

    // act
    var result = handler.handleRequest(event, null);

    // assert
    assertThat(result.getStatusCode()).isEqualTo(200);
    assertThat(objectMapper.readTree(result.getBody()))
        .isEqualTo(
            objectMapper.readTree(
                """
                {"cards":[{"game":"mtg","external_id":"card-id","name":"Lightning Bolt","set_code":"lea","set_name":"Limited Edition Alpha","collector_number":"161","image_urls":{"small":"small.jpg","normal":"normal.jpg"},"available_finishes":["normal","foil"]}],"next_continuation":"next-page"}
                """));
    verify(catalogService)
        .searchCards(
            eq(new RequestContext("jordan")),
            eq(new SearchCardsRequest("mtg", " Lightning Bolt ", "normal", 7, "next/page")));
  }

  @Test
  void handleRequestShouldMapServiceFailures() throws Exception {
    // arrange
    var event =
        objectMapper.readValue(
            """
            {
              "headers": {"Authorization": "%s"},
              "queryStringParameters": {"game": "mtg", "query": "Bolt", "finish": "normal"}
            }
            """
                .formatted(AUTH_HEADER),
            APIGatewayV2HTTPEvent.class);
    when(catalogService.searchCards(any(), any()))
        .thenThrow(new ServiceUnavailableException("catalog is temporarily unavailable"));

    // act
    var response = handler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(503);
    assertThat(objectMapper.readTree(response.getBody()))
        .isEqualTo(objectMapper.readTree("{\"message\":\"catalog is temporarily unavailable\"}"));
  }
}
