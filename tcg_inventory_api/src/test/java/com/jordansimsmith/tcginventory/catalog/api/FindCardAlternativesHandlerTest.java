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
import com.jordansimsmith.tcginventory.catalog.client.FindCardAlternativesRequest;
import com.jordansimsmith.tcginventory.catalog.client.FindCardAlternativesResponse;
import com.jordansimsmith.tcginventory.catalog.client.ImageUrls;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FindCardAlternativesHandlerTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final CatalogService catalogService = mock(CatalogService.class);
  private FindCardAlternativesHandler handler;

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
    handler = new FindCardAlternativesHandler(factory);
  }

  @Test
  void handleRequestShouldReturnServiceResponseUnchanged() throws Exception {
    // arrange
    var event =
        objectMapper.readValue(
            """
            {
              "headers": {"Authorization": "%s"},
              "pathParameters": {"external_id": "opaque/id"},
              "queryStringParameters": {
                "game": "pokemon", "finish": "holofoil", "limit": "7", "continuation": "next/page"
              }
            }
            """
                .formatted(AUTH_HEADER),
            APIGatewayV2HTTPEvent.class);
    var response =
        new FindCardAlternativesResponse(
            List.of(
                new Card(
                    "pokemon",
                    "opaque/id",
                    "Pikachu",
                    "sv1",
                    "Scarlet & Violet",
                    "25",
                    new ImageUrls("small.jpg", null),
                    List.of("normal", "holofoil"))),
            "next-page");
    when(catalogService.findCardAlternatives(any(), any())).thenReturn(response);

    // act
    var result = handler.handleRequest(event, null);

    // assert
    assertThat(result.getStatusCode()).isEqualTo(200);
    assertThat(objectMapper.readTree(result.getBody()))
        .isEqualTo(
            objectMapper.readTree(
                """
                {"cards":[{"game":"pokemon","external_id":"opaque/id","name":"Pikachu","set_code":"sv1","set_name":"Scarlet & Violet","collector_number":"25","image_urls":{"small":"small.jpg","normal":null},"available_finishes":["normal","holofoil"]}],"next_continuation":"next-page"}
                """));
    verify(catalogService)
        .findCardAlternatives(
            eq(new RequestContext("jordan")),
            eq(
                new FindCardAlternativesRequest(
                    "pokemon", "opaque/id", "holofoil", 7, "next/page")));
  }

  @Test
  void handleRequestShouldMapServiceFailures() throws Exception {
    // arrange
    var event =
        objectMapper.readValue(
            """
            {
              "headers": {"Authorization": "%s"},
              "pathParameters": {"external_id": "card-id"},
              "queryStringParameters": {"game": "mtg", "finish": "normal"}
            }
            """
                .formatted(AUTH_HEADER),
            APIGatewayV2HTTPEvent.class);
    when(catalogService.findCardAlternatives(any(), any()))
        .thenThrow(new ServiceUnavailableException("catalog is temporarily unavailable"));

    // act
    var response = handler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(503);
    assertThat(objectMapper.readTree(response.getBody()))
        .isEqualTo(objectMapper.readTree("{\"message\":\"catalog is temporarily unavailable\"}"));
  }
}
