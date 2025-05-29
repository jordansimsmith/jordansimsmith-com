# Auction tracker service

The auction tracker service automatically monitors auction listings on Trade Me and sends daily digest emails with new listings that match specified search criteria.

## System architecture

```mermaid
graph TD
  A[CloudWatch Event] -->|15 minute Trigger| B[Update Items Lambda]
  B -->|Get Search URLs| C[SearchFactory]
  B -->|Scrape Listings| D[Trade Me Client]
  D -->|Selenium Browser| E[Trade Me Website]
  B -->|Store Items| F[DynamoDB]
  B -->|LLM Evaluation| G[AWS Bedrock]
  G -->|Log Results| H[CloudWatch Logs]

  I[CloudWatch Event] -->|Daily Trigger| J[Daily Digest Lambda]
  J -->|Query New Items| F
  J -->|Send Email| K[SNS Topic]
  K -->|Notifications| L[Email Subscribers]
```

## Requirements and specifications

### Functional requirements

- Monitor auction listings on Trade Me using web scraping
- Support multiple predefined search criteria with configurable filters
- Store discovered auction items in DynamoDB with metadata
- Evaluate items using AWS Bedrock LLM integration
- Send daily digest emails with new listings found in the last 24 hours
- Check for new items every 15 minutes across all searches
- Support any category of items through flexible search configuration

### Technical specifications

- Serverless architecture using AWS Lambda
- Data persistence with DynamoDB using composite keys
- Web scraping with Selenium headless browser for client-side rendered pages
- LLM integration with AWS Bedrock for item evaluation
- Notification delivery through Amazon SNS
- Daily digest scheduling with CloudWatch Events
- 15-minute scraping frequency for all searches
- Search criteria factory pattern for maintainable configuration

## Implementation details

### Technologies

- AWS Lambda for serverless execution
- DynamoDB for storing auction item data
- Amazon SNS for email notification delivery
- AWS Bedrock for LLM-based item evaluation
- Selenium WebDriver for web scraping client-side rendered pages
- Java runtime environment
- AWS CloudWatch Events for scheduled triggers
- Terraform for infrastructure as code
- LocalStack Bedrock for E2E testing

### Key components

- `UpdateItemsHandler`: Lambda handler that scrapes Trade Me for new items
- `TradeMeClient`: Client interface for retrieving auction data from Trade Me
- `SeleniumTradeMeClient`: Implementation using Selenium WebDriver for scraping
- `SearchFactory`: Factory providing predefined search URLs and criteria
- `AuctionTrackerItem`: Data model for storing auction data in DynamoDB
- `ItemDigestHandler`: Lambda handler that sends daily email summaries
- `ItemEvaluator`: Client for AWS Bedrock LLM item evaluation
- `AuctionTrackerFactory`: Factory for creating required dependencies

### Configuration

- Item scraping frequency: Every 15 minutes via CloudWatch Events
- Daily digest frequency: Once per day via CloudWatch Events
- DynamoDB table: "auction_tracker" with partition key "pk" and sort key "sk"
- SNS topic name: "auction_tracker_api_digest"
- Email subscribers: Configured in Terraform

### Data schema

DynamoDB table structure:

- **Partition Key**: pk (String) - Trade Me search URL
- **Sort Key**: sk (String) - Timestamp + Trade Me item URL
- **Attributes**:
  - title (String) - Auction item title
  - ttl (Number) - Time to live expiry timestamp (30 days from item creation)

Example DynamoDB item:

```json
{
  "pk": {
    "S": "SEARCH#https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/search?search_string=titleist%20wedge"
  },
  "sk": {
    "S": "TIMESTAMP#1748489155ITEM#https://www.trademe.co.nz/a/marketplace/sports/golf/wedges-chippers/listing/5337003621"
  },
  "title": {
    "S": "Titleist Vokey SM6 Wedge 60* K Grind (Rattle in Head) $1 RESERVE!!!"
  },
  "ttl": {
    "N": "1751081155"
  }
}
```
