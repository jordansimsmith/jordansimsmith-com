package com.jordansimsmith.tcginventory.games.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.games.client.FakeGamesService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FindGamesHandlerTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";

  private ObjectMapper objectMapper;
  private FindGamesHandler handler;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    var factory = mock(TcgInventoryFactory.class);
    when(factory.gamesService()).thenReturn(new FakeGamesService());
    when(factory.requestContextFactory()).thenReturn(new RequestContextFactory());
    when(factory.httpResponseFactory())
        .thenReturn(new HttpResponseFactory.Builder(objectMapper).build());
    handler = new FindGamesHandler(factory);
  }

  @Test
  void handleRequestShouldReturnGamesClientResponseDirectly() throws Exception {
    // arrange
    var event =
        APIGatewayV2HTTPEvent.builder().withHeaders(Map.of("Authorization", AUTH_HEADER)).build();

    // act
    var response = handler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var games = objectMapper.readTree(response.getBody()).get("games");
    assertThat(games).hasSize(3);
    assertThat(games.get(0).get("id").asText()).isEqualTo("mtg");
    assertThat(games.get(1).get("id").asText()).isEqualTo("pokemon");
    assertThat(games.get(2))
        .isEqualTo(
            objectMapper.readTree(
                """
                {"id":"pokemon_jp","display_name":"Pokémon (JP)","external_source":"tcgplayer","language":"ja","scanning_enabled":true,"csv_import_enabled":false,"finishes":[{"id":"normal","display_name":"Normal"},{"id":"holofoil","display_name":"Holofoil"}],"scan_review_image_regions":[{"id":"set_and_number","display_name":"Set and number","x":0.0,"y":0.85,"width":0.5,"height":0.15},{"id":"artwork_stamps_left","display_name":"Left artwork stamps","x":0.0,"y":0.3,"width":0.48,"height":0.28},{"id":"artwork_stamps_right","display_name":"Right artwork stamps","x":0.52,"y":0.3,"width":0.48,"height":0.28},{"id":"lower_right_stamp_and_foil","display_name":"Lower-right stamp and foil","x":0.42,"y":0.54,"width":0.58,"height":0.35}]}
                """));
  }

  @Test
  void handleRequestShouldRequireAuthorization() {
    // arrange
    var event = APIGatewayV2HTTPEvent.builder().build();

    // act / assert
    assertThatThrownBy(() -> handler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IllegalStateException.class);
  }
}
