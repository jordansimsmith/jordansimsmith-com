package com.jordansimsmith.eventcalendar;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.time.Clock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class HttpMeetupClient implements MeetupClient {
  private static final String API_ENDPOINT = "https://www.meetup.com/gql2";
  private static final String UPCOMING_EVENTS_HASH =
      "55bced4dca11114ce83c003609158f19b3ca289939c2e6c0b39ce728722756f4";
  private static final String PAST_EVENTS_HASH =
      "84d621b514d4bfad36d9b37d78f469ee558b01ebe97ba9fb9183fe958b2ad1f1";

  private final HttpClient httpClient;
  private final Clock clock;
  private final ObjectMapper objectMapper;

  public HttpMeetupClient(HttpClient httpClient, Clock clock, ObjectMapper objectMapper) {
    this.httpClient = httpClient;
    this.clock = clock;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<MeetupEvent> getEvents(URI groupUrl) {
    try {
      return doGetEvents(groupUrl);
    } catch (Exception e) {
      throw new RuntimeException("Failed to get meetup events for group: " + groupUrl, e);
    }
  }

  private List<MeetupEvent> doGetEvents(URI groupUrl) throws Exception {
    var events = new ArrayList<MeetupEvent>();
    var currentTime = clock.now().toString();
    var groupUrlname = extractGroupUrlname(groupUrl);

    var upcomingRequest = buildUpcomingEventsRequest(groupUrlname, currentTime);
    var upcomingJson = objectMapper.writeValueAsString(upcomingRequest);
    var upcomingResponse = fetchEvents(upcomingJson);
    events.addAll(toMeetupEvents(upcomingResponse, groupUrl.toString()));

    var pastRequest = buildPastEventsRequest(groupUrlname, currentTime);
    var pastJson = objectMapper.writeValueAsString(pastRequest);
    var pastResponse = fetchEvents(pastJson);
    events.addAll(toMeetupEvents(pastResponse, groupUrl.toString()));

    return events;
  }

  private GraphQLResponse fetchEvents(String jsonBody) throws Exception {
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(API_ENDPOINT))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return objectMapper.readValue(response.body(), GraphQLResponse.class);
  }

  private String extractGroupUrlname(URI groupUrl) {
    var path = groupUrl.getPath();
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    return path;
  }

  private List<MeetupEvent> toMeetupEvents(GraphQLResponse response, String groupUrl) {
    var events = new ArrayList<MeetupEvent>();

    if (response.data() != null && response.data().groupByUrlname() != null) {
      var edges = response.data().groupByUrlname().events().edges();
      for (var edge : edges) {
        var node = edge.node();
        var title = node.title();
        var eventUrl = node.eventUrl();
        var startTime = Instant.parse(node.dateTime());
        var location = formatLocation(node.venue());

        events.add(new MeetupEvent(title, groupUrl, eventUrl, startTime, location));
      }
    }

    return events;
  }

  private String formatLocation(Venue venue) {
    if (venue == null) {
      return "TBD";
    }

    var parts = new ArrayList<String>();

    if (venue.name() != null && !venue.name().isEmpty()) {
      parts.add(venue.name());
    }
    if (venue.address() != null && !venue.address().isEmpty()) {
      parts.add(venue.address());
    }
    if (venue.city() != null && !venue.city().isEmpty()) {
      parts.add(venue.city());
    }

    return parts.isEmpty() ? "TBD" : String.join(", ", parts);
  }

  private GraphQLRequest buildUpcomingEventsRequest(String groupUrlname, String afterDateTime) {
    return new GraphQLRequest(
        "getUpcomingGroupEvents",
        Variables.forUpcoming(groupUrlname, afterDateTime),
        new Extensions(new PersistedQuery(1, UPCOMING_EVENTS_HASH)));
  }

  private GraphQLRequest buildPastEventsRequest(String groupUrlname, String beforeDateTime) {
    return new GraphQLRequest(
        "getPastGroupEvents",
        Variables.forPast(groupUrlname, beforeDateTime),
        new Extensions(new PersistedQuery(1, PAST_EVENTS_HASH)));
  }

  private record GraphQLRequest(
      @JsonProperty("operationName") String operationName,
      @JsonProperty("variables") Variables variables,
      @JsonProperty("extensions") Extensions extensions) {}

  private record Variables(
      @JsonProperty("urlname") String urlname,
      @JsonProperty("afterDateTime") String afterDateTime,
      @JsonProperty("beforeDateTime") String beforeDateTime) {

    static Variables forUpcoming(String urlname, String afterDateTime) {
      return new Variables(urlname, afterDateTime, null);
    }

    static Variables forPast(String urlname, String beforeDateTime) {
      return new Variables(urlname, null, beforeDateTime);
    }
  }

  private record Extensions(@JsonProperty("persistedQuery") PersistedQuery persistedQuery) {}

  private record PersistedQuery(
      @JsonProperty("version") int version, @JsonProperty("sha256Hash") String sha256Hash) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record GraphQLResponse(@JsonProperty("data") Data data) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Data(@JsonProperty("groupByUrlname") GroupByUrlname groupByUrlname) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record GroupByUrlname(@JsonProperty("events") Events events) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Events(@JsonProperty("edges") List<Edge> edges) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Edge(@JsonProperty("node") Node node) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Node(
      @JsonProperty("id") String id,
      @JsonProperty("title") String title,
      @JsonProperty("eventUrl") String eventUrl,
      @JsonProperty("dateTime") String dateTime,
      @JsonProperty("venue") Venue venue) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Venue(
      @JsonProperty("name") String name,
      @JsonProperty("address") String address,
      @JsonProperty("city") String city) {}
}
