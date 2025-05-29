package com.jordansimsmith.auctiontracker;

import static org.assertj.core.api.Assertions.*;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.testcontainers.DynamoDbContainer;
import com.jordansimsmith.time.FakeClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Testcontainers
public class UpdateItemsHandlerIntegrationTest {
  private FakeClock fakeClock;
  private FakeSearchFactory fakeSearchFactory;
  private FakeTradeMeClient fakeTradeMeClient;
  private DynamoDbTable<AuctionTrackerItem> auctionTrackerTable;

  private UpdateItemsHandler updateItemsHandler;

  @Container DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeEach
  void setUp() {
    var factory = AuctionTrackerTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    fakeSearchFactory = factory.fakeSearchFactory();
    fakeTradeMeClient = factory.fakeTradeMeClient();
    auctionTrackerTable = factory.auctionTrackerTable();

    DynamoDbUtils.createTable(factory.dynamoDbClient(), auctionTrackerTable);

    updateItemsHandler = new UpdateItemsHandler(factory);
  }

  @Test
  void handleRequestShouldUpdateItems() {
    // arrange
    fakeClock.setTime(3_000_000);

    // act
    updateItemsHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    // stub test - just verify handler runs without error
  }
}
