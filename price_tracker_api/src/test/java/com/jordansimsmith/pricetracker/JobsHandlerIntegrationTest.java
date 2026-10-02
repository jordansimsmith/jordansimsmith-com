package com.jordansimsmith.pricetracker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class JobsHandlerIntegrationTest {
  private JobsHandler handler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory = PriceTrackerTestFactory.create(dynamoDbContainer.getEndpoint());
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.priceTrackerTable());
  }

  @BeforeEach
  void setUp() {
    var factory = PriceTrackerTestFactory.create(dynamoDbContainer.getEndpoint());
    DynamoDbUtils.reset(factory.dynamoDbClient());
    handler = new JobsHandler(factory);
  }

  @Test
  void handleRequestShouldRejectUnknownJobTypes() {
    // arrange
    var event = event("{\"job_type\":\"unknown\",\"scheduled_at\":\"2026-09-30T08:05:00Z\"}");

    // act / assert
    assertThatThrownBy(() -> handler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void handleRequestShouldRejectMissingScheduledTime() {
    // arrange
    var event = event("{\"job_type\":\"send_digest\"}");

    // act / assert
    assertThatThrownBy(() -> handler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void handleRequestShouldRejectMultipleRecords() {
    // arrange
    var event = event("{\"job_type\":\"send_digest\",\"scheduled_at\":\"2026-09-30T08:05:00Z\"}");
    event.setRecords(List.of(event.getRecords().getFirst(), new SQSEvent.SQSMessage()));

    // act / assert
    assertThatThrownBy(() -> handler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  private SQSEvent event(String body) {
    var message = new SQSEvent.SQSMessage();
    message.setBody(body);
    var event = new SQSEvent();
    event.setRecords(List.of(message));
    return event;
  }
}
