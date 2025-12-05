# Event calendar service

The event calendar service extracts, processes, and provides structured data about events from multiple sources including Go Media Stadium, meetup.com groups, and Leinster Rugby fixtures.

## System architecture

```mermaid
graph TD
  A[EventBridge Schedule] --> B[UpdateEventsHandler Lambda]
  B --> C[Go Media Stadium Website]
  C --> B
  B --> D[Meetup.com API]
  D --> B
  B --> I[Leinster Rugby API]
  I --> B
  B --> E[DynamoDB]
  F[GetCalendarSubscriptionHandler Lambda] --> E
  F --> G[iCal Response]
  G --> H[iPhone Calendar]
```

## Requirements

### Functional requirements

- Extract event information from Go Media Stadium website
- Extract event information from meetup.com groups via GraphQL API
- Extract event information from Leinster Rugby fixtures feed
- Track both upcoming and past events from configured meetup groups
- Process both individual event pages and season overview pages
- Capture comprehensive event details including:
  - Event title
  - Event date and time
  - Event URL
  - Event information (box office opening, gate opening, etc.)
  - Event location (for meetup events)
  - Venue (for Leinster Rugby fixtures)
  - Competition/tournament name (for Leinster Rugby fixtures)
- Store extracted data in DynamoDB
- Expose event data through iCal subscription endpoint
- Update event data every 15 minutes through scheduled polling
- Support calendar subscription in iPhone and other calendar apps

### Technical specifications

- Performance: System updates event data every 15 minutes automatically
- Reliability: Event data is persistently stored and consistently available
- Availability: Calendar subscription endpoint accessible 24/7

## Implementation details

### Technologies

- AWS Lambda for serverless execution
- Amazon EventBridge for scheduled task execution
- DynamoDB for storing structured event data
- AWS API Gateway for exposing the iCal endpoint
- Java 17 runtime environment
- Jsoup library for HTML parsing
- Java HTTP Client for REST API calls
- Jackson library for JSON processing
- Biweekly library for iCal generation
- AWS Certificate Manager for SSL/TLS
- Custom domain name with API Gateway

### Key components

- `UpdateEventsHandler`: Lambda handler that processes scheduled events to scrape the website
- `GetCalendarSubscriptionHandler`: Lambda handler that serves iCal subscription data
- `JsoupGoMediaEventClient`: Implementation that uses Jsoup to scrape event data
- `MeetupClient`: Interface for fetching events from meetup.com
- `HttpMeetupClient`: Implementation using HTTP client and JSON parsing
- `LeinsterRugbyClient`: Interface for fetching Leinster Rugby fixtures
- `HttpLeinsterRugbyClient`: HTTP implementation that consumes the Sotic fixtures API
- `EventCalendarItem`: Data model for storing event data in DynamoDB
- `EventCalendarFactory`: Factory for creating the required dependencies

### Configuration

- Lambda execution frequency: Every 15 minutes via EventBridge schedule
- DynamoDB table: "event_calendar" with hash key "pk" and range key "sk"
- API Gateway endpoint: GET /calendar
- Custom domain: api.event-calendar.jordansimsmith.com
- Time zone: Pacific/Auckland
- Lambda memory: 1024MB, timeout: 30 seconds
- Java 17 runtime for Lambda functions

### Meetup.com integration

The service fetches events from configured meetup.com groups using their GraphQL API. The integration makes two API calls per group to retrieve both upcoming and past events.

**Configured groups:**

- auckland-japanese-english-exchange-enkai-縁会 (Japanese/English Exchange ENKAI)

**API Details:**

- Endpoint: `https://www.meetup.com/gql2`
- Method: HTTP POST with JSON body
- Uses Apollo persisted queries (query hashes required)
- No authentication required for public events

**Request format for upcoming events:**

```json
{
  "operationName": "getUpcomingGroupEvents",
  "variables": {
    "urlname": "GROUP_URLNAME",
    "afterDateTime": "2025-10-25T05:09:23.000Z"
  },
  "extensions": {
    "persistedQuery": {
      "version": 1,
      "sha256Hash": "55bced4dca11114ce83c003609158f19b3ca289939c2e6c0b39ce728722756f4"
    }
  }
}
```

**Request format for past events:**

```json
{
  "operationName": "getPastGroupEvents",
  "variables": {
    "urlname": "GROUP_URLNAME",
    "beforeDateTime": "2025-10-25T05:07:29.933Z"
  },
  "extensions": {
    "persistedQuery": {
      "version": 1,
      "sha256Hash": "84d621b514d4bfad36d9b37d78f469ee558b01ebe97ba9fb9183fe958b2ad1f1"
    }
  }
}
```

**Response format:**
Both operations return events at `.data.groupByUrlname.events.edges[].node` with fields:

- `id`: Event identifier
- `title`: Event name
- `eventUrl`: Link to event page
- `dateTime`: Start time (ISO 8601 with timezone)
- `venue`: Location details (name, address, city) - may be null for future events
- `status`: "ACTIVE", "PAST", or "CANCELLED"

### Leinster Rugby integration

The service also ingests fixtures for the Leinster Rugby men's team using the Sotic API feed.

**Configured team:**

- Leinster Rugby (`https://www.leinsterrugby.ie/teams/mens-senior/mens-matches/`)

**API details:**

- Endpoint: `https://stats-api.leinster.soticclient.net/custom/fixtureList/9941733f-560d-4cd3-89d1-78af7cd3b995/76c82394-adfd-4cdb-8c02-a65a03ec1f88`
- Method: HTTP GET
- Authentication: none
- Pagination: not required (feed currently returns 22 fixtures covering the season)

**Example response (single fixture):**

```json
{
  "_name": "Leinster Rugby v Harlequins",
  "id": "e2b6a6ac-0371-4cf4-81bc-c9d75b973c34",
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
```

**Relevant response fields:**

- `._name`: Fixture name used for the calendar summary
- `.datetime`: Kick-off in UTC, parsed into `timestamp`
- `.id`: Stable fixture identifier stored as `event_id` and the basis for the DynamoDB sort key
- `.stage.season.competition.name`: Competition or tournament name stored in `event_info`
- `.venue._name` + `.venue.city`: Combined into the `location` string

**Storage conventions:**

- Partition key: `SPORTS_TEAM#https://www.leinsterrugby.ie/teams/mens-senior/mens-matches/`
- Sort key: `EVENT#{id}` (uses the same formatting helper as other event types)
- `sports_team_url`: `https://www.leinsterrugby.ie/teams/mens-senior/mens-matches/`
- `event_id`: Raw `{id}` stored separately for querying/auditing

### DynamoDB item examples

Event data is persisted in the `event_calendar` table with snake_case attribute names. Below are sample JSON items for each supported source:

**Go Media Stadium event**

```json
{
  "pk": "STADIUM#https://www.aucklandstadiums.co.nz/our-venues/go-media-stadium",
  "sk": "EVENT#https://www.aucklandstadiums.co.nz/event/warriors-storm",
  "title": "Warriors vs Storm",
  "event_url": "https://www.aucklandstadiums.co.nz/event/warriors-storm",
  "event_info": "Box office opens at 17:30, gates open at 18:30",
  "timestamp": 1711414200,
  "stadium_url": "https://www.aucklandstadiums.co.nz/our-venues/go-media-stadium"
}
```

**Meetup.com event**

```json
{
  "pk": "MEETUP_GROUP#https://www.meetup.com/auckland-japanese-english-exchange-enkai-縁会",
  "sk": "EVENT#https://www.meetup.com/auckland-japanese-english-exchange-enkai-縁会/events/123456789/",
  "title": "Japanese/English Exchange ENKAI",
  "event_url": "https://www.meetup.com/auckland-japanese-english-exchange-enkai-縁会/events/123456789/",
  "timestamp": 1769079600,
  "meetup_group_url": "https://www.meetup.com/auckland-japanese-english-exchange-enkai-縁会",
  "location": "The Occidental, 6 Vulcan Lane, Auckland"
}
```

**Leinster Rugby fixture**

```json
{
  "pk": "SPORTS_TEAM#https://www.leinsterrugby.ie/teams/mens-senior/mens-matches/",
  "sk": "EVENT#e2b6a6ac-0371-4cf4-81bc-c9d75b973c34",
  "title": "Leinster Rugby v Harlequins",
  "event_info": "Investec Champions Cup",
  "timestamp": 1760040600,
  "sports_team_url": "https://www.leinsterrugby.ie/teams/mens-senior/mens-matches/",
  "event_id": "e2b6a6ac-0371-4cf4-81bc-c9d75b973c34",
  "location": "Aviva Stadium, Dublin"
}
```
