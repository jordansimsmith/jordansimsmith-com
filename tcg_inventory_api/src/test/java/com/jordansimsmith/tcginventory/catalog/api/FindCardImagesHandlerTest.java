package com.jordansimsmith.tcginventory.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.catalog.client.CardImages;
import com.jordansimsmith.tcginventory.catalog.client.CatalogService;
import com.jordansimsmith.tcginventory.catalog.client.FindCardImagesRequest;
import com.jordansimsmith.tcginventory.catalog.client.FindCardImagesResponse;
import com.jordansimsmith.tcginventory.catalog.client.ImageUrls;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FindCardImagesHandlerTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final CatalogService catalogService = mock(CatalogService.class);
  private FindCardImagesHandler handler;

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
    handler = new FindCardImagesHandler(factory);
  }

  @Test
  void handleRequestShouldPassRepeatedValuesToServiceAndReturnResponse() throws Exception {
    // arrange
    var event =
        objectMapper.readValue(
            """
            {
              "headers": {"Authorization": "%s"},
              "queryStringParameters": {"game": "mtg"},
              "multiValueQueryStringParameters": {
                "external_ids": ["a/b", "space id", "a/b"]
              }
            }
            """
                .formatted(AUTH_HEADER),
            FindCardImagesHandler.HttpEvent.class);
    var result =
        new FindCardImagesResponse(
            List.of(
                new CardImages(
                    "a/b",
                    new ImageUrls(
                        "https://api.scryfall.com/cards/a%2Fb?format=image&version=small", null))));
    when(catalogService.findCardImages(any(), any())).thenReturn(result);

    // act
    var response = handler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders().get("Content-Type"))
        .isEqualTo("application/json; charset=utf-8");
    assertThat(response.getHeaders().get("Access-Control-Allow-Origin"))
        .isEqualTo("https://tcg-inventory.jordansimsmith.com");
    assertThat(objectMapper.readTree(response.getBody()))
        .isEqualTo(
            objectMapper.readTree(
                """
                {"images":[{"external_id":"a/b","image_urls":{"small":"https://api.scryfall.com/cards/a%2Fb?format=image&version=small","normal":null}}]}
                """));
    verify(catalogService)
        .findCardImages(
            eq(new RequestContext("jordan")),
            eq(new FindCardImagesRequest("mtg", List.of("a/b", "space id", "a/b"))));
  }

  @Test
  void handleRequestShouldMapServiceException() throws Exception {
    // arrange
    var event =
        objectMapper.readValue(
            """
            {
              "headers": {"Authorization": "%s"},
              "queryStringParameters": {"game": "missing"},
              "multiValueQueryStringParameters": {"external_ids": ["id"]}
            }
            """
                .formatted(AUTH_HEADER),
            FindCardImagesHandler.HttpEvent.class);
    when(catalogService.findCardImages(any(), any()))
        .thenThrow(new NotFoundException("unsupported game: missing"));

    // act
    var response = handler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("unsupported game: missing");
  }

  @Test
  void handleRequestShouldPropagateUnexpectedFailure() throws Exception {
    // arrange
    var event =
        objectMapper.readValue(
            """
            {
              "headers": {"Authorization": "%s"},
              "queryStringParameters": {"game": "mtg"},
              "multiValueQueryStringParameters": {"external_ids": ["id"]}
            }
            """
                .formatted(AUTH_HEADER),
            FindCardImagesHandler.HttpEvent.class);
    when(catalogService.findCardImages(any(), any()))
        .thenThrow(new IllegalStateException("broken"));

    // act / assert
    assertThatThrownBy(() -> handler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IllegalStateException.class);
  }
}
