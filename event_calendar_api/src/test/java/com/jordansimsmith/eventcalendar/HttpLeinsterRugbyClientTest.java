package com.jordansimsmith.eventcalendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

public class HttpLeinsterRugbyClientTest {
  private static final String FIXTURE_RESPONSE =
      """
      [
        {
          "_name": "Leinster Rugby v Harlequins",
          "id": "fixture-123",
          "datetime": "2025-12-06T17:30:00Z",
          "venue": {
            "_name": "Aviva Stadium",
            "city": "Dublin"
          },
          "stage": {
            "season": {
              "competition": {
                "name": "Investec Champions Cup"
              }
            }
          }
        }
      ]
      """;

  private static final String INVALID_FIXTURE_RESPONSE =
      """
      [
        {
          "_name": null,
          "id": null,
          "datetime": null
        }
      ]
      """;

  @Mock HttpClient httpClient;

  private ObjectMapper objectMapper;
  private HttpLeinsterRugbyClient client;
  private AutoCloseable mocks;

  @BeforeEach
  void setUp() {
    mocks = openMocks(this);
    objectMapper = new ObjectMapper();
    client = new HttpLeinsterRugbyClient(httpClient, objectMapper);
  }

  @AfterEach
  void tearDown() throws Exception {
    mocks.close();
  }

  @Test
  void findFixturesShouldParseResponse() throws Exception {
    var response = createMockResponse(FIXTURE_RESPONSE);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    var fixtures = client.findFixtures();

    assertThat(fixtures).hasSize(1);
    var fixture = fixtures.get(0);
    assertThat(fixture.fixtureId()).isEqualTo("fixture-123");
    assertThat(fixture.title()).isEqualTo("Leinster Rugby v Harlequins");
    assertThat(fixture.startTime()).isEqualTo(Instant.parse("2025-12-06T17:30:00Z"));
    assertThat(fixture.competition()).isEqualTo("Investec Champions Cup");
    assertThat(fixture.location()).isEqualTo("Aviva Stadium, Dublin");
  }

  @Test
  void findFixturesShouldIgnoreInvalidEntries() throws Exception {
    var response = createMockResponse(INVALID_FIXTURE_RESPONSE);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    var fixtures = client.findFixtures();

    assertThat(fixtures).isEmpty();
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> createMockResponse(String body) {
    HttpResponse<String> mockResponse = mock(HttpResponse.class);
    when(mockResponse.body()).thenReturn(body);
    return mockResponse;
  }
}
