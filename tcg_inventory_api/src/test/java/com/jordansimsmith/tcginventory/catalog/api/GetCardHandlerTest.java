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
import com.jordansimsmith.tcginventory.catalog.client.GetCardRequest;
import com.jordansimsmith.tcginventory.catalog.client.GetCardResponse;
import com.jordansimsmith.tcginventory.catalog.client.ImageUrls;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GetCardHandlerTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final CatalogService catalogService = mock(CatalogService.class);
  private GetCardHandler handler;

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
    handler = new GetCardHandler(factory);
  }

  @Test
  void handleRequestShouldReturnServiceResponseUnchanged() throws Exception {
    // arrange
    var event =
        objectMapper.readValue(
            """
            {
              "headers": {"Authorization": "%s"},
              "queryStringParameters": {"game": "mtg"},
              "pathParameters": {"external_id": "card-id"}
            }
            """
                .formatted(AUTH_HEADER),
            APIGatewayV2HTTPEvent.class);
    var result =
        new GetCardResponse(
            new Card(
                "mtg",
                "card-id",
                "Lightning Bolt",
                "lea",
                "Limited Edition Alpha",
                "161",
                new ImageUrls("small.jpg", null),
                List.of("normal", "foil")));
    when(catalogService.getCard(any(), any())).thenReturn(result);

    // act
    var response = handler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(objectMapper.readTree(response.getBody()))
        .isEqualTo(
            objectMapper.readTree(
                """
                {"card":{"game":"mtg","external_id":"card-id","name":"Lightning Bolt","set_code":"lea","set_name":"Limited Edition Alpha","collector_number":"161","image_urls":{"small":"small.jpg","normal":null},"available_finishes":["normal","foil"]}}
                """));
    verify(catalogService)
        .getCard(eq(new RequestContext("jordan")), eq(new GetCardRequest("mtg", "card-id")));
  }

  @Test
  void handleRequestShouldMapServiceFailures() throws Exception {
    // arrange
    var event =
        objectMapper.readValue(
            """
            {
              "headers": {"Authorization": "%s"},
              "queryStringParameters": {"game": "mtg"},
              "pathParameters": {"external_id": "card-id"}
            }
            """
                .formatted(AUTH_HEADER),
            APIGatewayV2HTTPEvent.class);
    when(catalogService.getCard(any(), any()))
        .thenThrow(new ServiceUnavailableException("catalog is temporarily unavailable"));

    // act
    var response = handler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(503);
    assertThat(objectMapper.readTree(response.getBody()))
        .isEqualTo(objectMapper.readTree("{\"message\":\"catalog is temporarily unavailable\"}"));
  }
}
