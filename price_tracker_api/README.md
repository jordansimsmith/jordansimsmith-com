# Price tracker service

The price tracker service automatically monitors product prices on e-commerce websites and notifies users when prices change.

## System architecture

```mermaid
graph TD
  A[CloudWatch Event] -->|Hourly Trigger| B[Update Prices Lambda]
  B -->|Fetch Products| C[ProductsFactory]
  B -->|Get Price| D[ChemistWarehouseClient]
  D -->|Scrape| E[Chemist Warehouse Website]
  B -->|Store Data| F[DynamoDB]
  B -->|Compare Prices| G[Price Comparison Logic]
  G -->|On Price Change| H[SNS Topic]
  H -->|Notifications| I[Email Subscribers]
```

## Requirements and specifications

### Functional requirements
- Monitor product prices from Chemist Warehouse website
- Store price history for each tracked product
- Send notifications when prices change
- Support multiple product monitoring
- Automatically update prices hourly

### Technical specifications
- Serverless architecture using AWS Lambda
- Data persistence with DynamoDB
- Notification delivery through Amazon SNS
- Price history retention with timestamp tracking
- Support for multiple email subscribers
- Hourly price checks

## Implementation details

### Technologies
- AWS Lambda for serverless execution
- DynamoDB for storing product and price data
- Amazon SNS for notification delivery
- Java runtime environment
- Jsoup library for HTML parsing
- AWS CloudWatch Events for scheduled triggers
- Terraform for infrastructure as code

### Key components
- `UpdatePricesHandler`: Lambda handler that processes scheduled events and updates prices
- `ChemistWarehouseClient`: Client interface for retrieving product data from Chemist Warehouse
- `JsoupChemistWarehouseClient`: Implementation that uses Jsoup to scrape product data
- `ProductsFactory`: Interface for finding products to track
- `ProductsFactoryImpl`: Implementation that provides the list of products to track
- `PriceTrackerItem`: Data model for storing price data in DynamoDB
- `PriceTrackerFactory`: Factory for creating the required dependencies
- `NotificationPublisher`: Interface for sending price change alerts

### Configuration
- Lambda execution frequency: Once per hour via CloudWatch Events
- DynamoDB table: "price_tracker" with hash key "pk" and range key "sk"
- SNS topic name: "price_tracker_api_price_updates"
- Email subscribers: Configured in Terraform 