package com.jordansimsmith.tcginventory.games;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class GetGamesHandlerTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final GetGamesHandler handler =
      new GetGamesHandler(
          new RequestContextFactory(), new HttpResponseFactory.Builder(objectMapper).build());

  @Test
  void handleRequestShouldReturnOrderedMagicMetadataWithoutProviderDetails() throws Exception {
    // arrange
    var event =
        APIGatewayV2HTTPEvent.builder().withHeaders(Map.of("Authorization", AUTH_HEADER)).build();

    // act
    var response = handler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(objectMapper.readTree(response.getBody()))
        .isEqualTo(
            objectMapper.readTree(
                """
                {"games":[{"id":"mtg","display_name":"Magic: The Gathering","scanning_enabled":true,"csv_import_enabled":true,"finishes":[{"id":"normal","display_name":"Normal"},{"id":"foil","display_name":"Foil"},{"id":"etched","display_name":"Etched"}]}]}
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
