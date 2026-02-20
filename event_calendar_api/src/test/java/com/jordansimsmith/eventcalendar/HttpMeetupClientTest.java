package com.jordansimsmith.eventcalendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.time.Clock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

public class HttpMeetupClientTest {
  private static final String UPCOMING_EVENTS_RESPONSE =
      """
      {
        "data": {
          "groupByUrlname": {
            "events": {
              "edges": [
                {
                  "node": {
                    "id": "310482719",
                    "title": "Test Event",
                    "eventUrl": "https://www.meetup.com/test-group/events/310482719/",
                    "dateTime": "2025-11-22T15:00:00+13:00",
                    "venue": {
                      "name": "Test Venue",
                      "address": "123 Test St",
                      "city": "Auckland"
                    }
                  }
                }
              ]
            }
          }
        }
      }
      """;

  private static final String PAST_EVENTS_RESPONSE =
      """
      {
        "data": {
          "groupByUrlname": {
            "events": {
              "edges": [
                {
                  "node": {
                    "id": "308779907",
                    "title": "Past Event",
                    "eventUrl": "https://www.meetup.com/test-group/events/308779907/",
                    "dateTime": "2025-09-27T15:00:00+12:00",
                    "venue": {
                      "name": "Past Venue",
                      "address": "456 Past Ave",
                      "city": "Wellington"
                    }
                  }
                }
              ]
            }
          }
        }
      }
      """;

  private static final String NULL_VENUE_RESPONSE =
      """
      {
        "data": {
          "groupByUrlname": {
            "events": {
              "edges": [
                {
                  "node": {
                    "id": "310482720",
                    "title": "TBD Event",
                    "eventUrl": "https://www.meetup.com/test-group/events/310482720/",
                    "dateTime": "2025-12-01T15:00:00+13:00",
                    "venue": null
                  }
                }
              ]
            }
          }
        }
      }
      """;

  @Mock HttpClient httpClient;
  @Mock Clock clock;

  private ObjectMapper objectMapper;
  private HttpMeetupClient client;
  private AutoCloseable openMocks;

  @BeforeEach
  void setUp() {
    openMocks = openMocks(this);
    objectMapper = new ObjectMapper();
    client = new HttpMeetupClient(httpClient, clock, objectMapper, URI.create("http://stub"));
  }

  @AfterEach
  void tearDown() throws Exception {
    openMocks.close();
  }

  @Test
  void getEventsShouldReturnEventsWithCompleteVenue() throws Exception {
    // arrange
    when(clock.now()).thenReturn(Instant.parse("2025-10-25T05:00:00Z"));

    var upcomingResponse = createMockResponse(UPCOMING_EVENTS_RESPONSE);
    var pastResponse = createMockResponse(PAST_EVENTS_RESPONSE);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(upcomingResponse)
        .thenReturn(pastResponse);

    // act
    var events = client.findEvents(URI.create("https://www.meetup.com/test-group"));

    // assert
    assertThat(events).hasSize(2);

    var upcomingEvent = events.get(0);
    assertThat(upcomingEvent.title()).isEqualTo("Test Event");
    assertThat(upcomingEvent.groupUrl()).isEqualTo("https://www.meetup.com/test-group");
    assertThat(upcomingEvent.eventUrl())
        .isEqualTo("https://www.meetup.com/test-group/events/310482719/");
    assertThat(upcomingEvent.startTime()).isEqualTo(Instant.parse("2025-11-22T02:00:00Z"));
    assertThat(upcomingEvent.location()).isEqualTo("Test Venue, 123 Test St, Auckland");

    var pastEvent = events.get(1);
    assertThat(pastEvent.title()).isEqualTo("Past Event");
    assertThat(pastEvent.groupUrl()).isEqualTo("https://www.meetup.com/test-group");
    assertThat(pastEvent.eventUrl())
        .isEqualTo("https://www.meetup.com/test-group/events/308779907/");
    assertThat(pastEvent.startTime()).isEqualTo(Instant.parse("2025-09-27T03:00:00Z"));
    assertThat(pastEvent.location()).isEqualTo("Past Venue, 456 Past Ave, Wellington");
  }

  @Test
  void getEventsShouldHandleNullVenue() throws Exception {
    // arrange
    when(clock.now()).thenReturn(Instant.parse("2025-10-25T05:00:00Z"));

    var upcomingResponse = createMockResponse(NULL_VENUE_RESPONSE);
    var pastResponse =
        createMockResponse("{\"data\":{\"groupByUrlname\":{\"events\":{\"edges\":[]}}}}");

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(upcomingResponse)
        .thenReturn(pastResponse);

    // act
    var events = client.findEvents(URI.create("https://www.meetup.com/test-group"));

    // assert
    assertThat(events).hasSize(1);
    assertThat(events.get(0).location()).isEqualTo("TBD");
  }

  @Test
  void getEventsShouldCombineUpcomingAndPastEvents() throws Exception {
    // arrange
    when(clock.now()).thenReturn(Instant.parse("2025-10-25T05:00:00Z"));

    var upcomingResponse = createMockResponse(UPCOMING_EVENTS_RESPONSE);
    var pastResponse = createMockResponse(PAST_EVENTS_RESPONSE);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(upcomingResponse)
        .thenReturn(pastResponse);

    // act
    var events = client.findEvents(URI.create("https://www.meetup.com/test-group"));

    // assert
    assertThat(events).hasSize(2);
    assertThat(events.stream().map(MeetupClient.MeetupEvent::title))
        .containsExactly("Test Event", "Past Event");
  }

  @Test
  void getEventsShouldParseTimestampsWithTimezone() throws Exception {
    // arrange
    when(clock.now()).thenReturn(Instant.parse("2025-10-25T05:00:00Z"));

    var upcomingResponse = createMockResponse(UPCOMING_EVENTS_RESPONSE);
    var pastResponse =
        createMockResponse("{\"data\":{\"groupByUrlname\":{\"events\":{\"edges\":[]}}}}");

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(upcomingResponse)
        .thenReturn(pastResponse);

    // act
    var events = client.findEvents(URI.create("https://www.meetup.com/test-group"));

    // assert
    assertThat(events).hasSize(1);
    assertThat(events.get(0).startTime()).isEqualTo(Instant.parse("2025-11-22T02:00:00Z"));
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> createMockResponse(String body) {
    HttpResponse<String> mockResponse = mock(HttpResponse.class);
    when(mockResponse.body()).thenReturn(body);
    return mockResponse;
  }
}
