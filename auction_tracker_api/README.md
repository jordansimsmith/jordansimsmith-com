# Auction tracker service

The auction tracker service automatically monitors auction listings on Trade Me and sends daily digest emails with new listings that match specified search criteria.

## System architecture

```mermaid
graph TD
  A[CloudWatch Event] -->|15 minute Trigger| B[Update Items Lambda]
  B -->|Get Search URLs| C[SearchFactory]
  B -->|Scrape Listings| D[Trade Me Client]
  D -->|HTTP Requests| E[Trade Me Website]
  B -->|Store Items| F[DynamoDB]

  G[CloudWatch Event] -->|Daily Trigger| H[Daily Digest Lambda]
  H -->|Query New Items| F
  H -->|Send Email| I[SNS Topic]
  I -->|Notifications| J[Email Subscribers]
```

## Requirements

### Functional requirements

- Monitor auction listings on Trade Me using web scraping
- Support multiple predefined search criteria with configurable filters
- Store discovered auction items in DynamoDB with metadata
- Send daily digest emails with new listings found in the last 24 hours
- Check for new items every 15 minutes across all searches
- Support any category of items through flexible search configuration

### Technical specifications

- Serverless architecture using AWS Lambda
- Data persistence with DynamoDB using composite keys
- Web scraping with Jsoup HTTP client for server-side rendered pages
- Notification delivery through Amazon SNS
- Daily digest scheduling with CloudWatch Events
- 15-minute scraping frequency for all searches
- Search criteria factory pattern for maintainable configuration

## Implementation details

### Technologies

- AWS Lambda for serverless execution
- DynamoDB for storing auction item data
- Amazon SNS for email notification delivery
- Jsoup for web scraping server-side rendered pages
- Java runtime environment
- AWS CloudWatch Events for scheduled triggers
- Terraform for infrastructure as code

### Key components

- `UpdateItemsHandler`: Lambda handler that scrapes Trade Me for new items with GSI-optimized duplicate detection
- `TradeMeClient`: Client interface for retrieving auction data from Trade Me
- `JsoupTradeMeClient`: Implementation using Jsoup HTTP client for scraping
- `SearchFactory`: Factory providing predefined search URLs and criteria
- `AuctionTrackerItem`: Data model for storing auction data in DynamoDB with GSI support
- `SendDigestHandler`: Lambda handler that sends daily email summaries
- `AuctionTrackerFactory`: Factory for creating required dependencies

### Configuration

- Item scraping frequency: Every 15 minutes via CloudWatch Events
- Daily digest frequency: Once per day via CloudWatch Events
- DynamoDB table: "auction_tracker" with partition key "pk" and sort key "sk"
- SNS topic name: "auction_tracker_api_digest"
- Email subscribers: Configured in Terraform

### Web scraping process

The JsoupTradeMeClient implements a lightweight web scraping pipeline for extracting auction data from Trade Me's server-side rendered content:

#### Search and pagination

1. **Search URL construction**: Builds Trade Me search URLs with encoded search terms and price filters
2. **HTTP requests**: Makes HTTP GET requests to search pages using Jsoup with proper user agent headers
3. **Result parsing**: Parses HTML response to extract search results and pagination information
4. **Link extraction**: Extracts individual listing URLs from search pages using CSS selectors (`a[href*='/listing/']`)

#### Individual item extraction

1. **HTTP requests**: Makes direct HTTP requests to each listing URL using Jsoup connection
2. **Title extraction**: Uses CSS selector `h1.tm-marketplace-buyer-options__listing_title, h1.tm-marketplace-koru-listing__title` to extract item titles
3. **Content extraction**: Gathers all text from `.tm-marketplace-listing-body__container, .tm-marketplace-koru-listing__body` elements for descriptions
4. **Text processing**:
   - Joins multiple content sections with spaces
   - Normalizes whitespace and removes excessive formatting
   - Truncates descriptions to 1000 characters for storage efficiency
5. **Error handling**: Graceful handling of failed requests with warning logs

#### Technical details

- **HTTP client**: Uses Jsoup's built-in HTTP client with comprehensive headers and cookies
- **Error handling**: Warns on individual item fetch failures and continues processing
- **CSS selectors**: Uses specific Trade Me CSS classes validated through testing
- **Timeout handling**: 30-second request timeout for HTTP connections

### Data schema

DynamoDB table structure:

- **Partition Key**: pk (String) - Complete Trade Me search URL with query parameters and prefix
- **Sort Key**: sk (String) - Timestamp + Trade Me item URL with prefixes
- **Attributes**:
  - title (String) - Auction item title
  - url (String) - Trade Me item URL
  - timestamp (Number) - Item creation timestamp (epoch seconds)
  - ttl (Number) - Time to live expiry timestamp (30 days from item creation)
  - version (Number) - Optimistic locking version
  - gsi1pk (String) - GSI partition key (prefixed complete search URL)
  - gsi1sk (String) - GSI sort key (prefixed item URL)

**Global Secondary Index (gsi1)**:

- **GSI Partition Key**: gsi1pk (String) - Prefixed complete Trade Me search URL with parameters (`SEARCH#<full_search_url>`)
- **GSI Sort Key**: gsi1sk (String) - Prefixed Trade Me item URL (`ITEM#<url>`)
- **Purpose**: Efficient duplicate detection using direct key lookup with consistent prefixing

Example DynamoDB item:

```json
{
  "pk": {
    "S": "SEARCH#https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/search?search_string=titleist%20wedge&price_max=70&sort_order=expirydesc"
  },
  "sk": {
    "S": "TIMESTAMP#1748489155ITEM#https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003621"
  },
  "title": {
    "S": "Titleist Vokey SM6 Wedge 60* K Grind (Rattle in Head) $1 RESERVE!!!"
  },
  "url": {
    "S": "https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003621"
  },
  "timestamp": {
    "N": "1748489155"
  },
  "ttl": {
    "N": "1751081155"
  },
  "version": {
    "N": "1"
  },
  "gsi1pk": {
    "S": "SEARCH#https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/search?search_string=titleist%20wedge&price_max=70&sort_order=expirydesc"
  },
  "gsi1sk": {
    "S": "ITEM#https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003621"
  }
}
```

### Duplicate detection optimization

The service uses a Global Secondary Index (gsi1) for efficient duplicate detection:

- **Performance**: O(1) direct key lookup instead of O(n) table scan
- **Implementation**: Query gsi1 using `formatGsi1pk()` and `formatGsi1sk()` methods for consistent key formation
- **Key Format**: GSI keys use same prefixes as main table (`SEARCH#` and `ITEM#`) for consistency
- **Benefits**: Scales efficiently with large datasets and reduces DynamoDB read costs
- **Pattern**: Uses stream-based query processing with page flattening for reliable existence checking
