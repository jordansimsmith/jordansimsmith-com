package com.jordansimsmith.subfootballtracker;

import static org.assertj.core.api.Assertions.*;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.notifications.FakeNotificationPublisher;
import com.jordansimsmith.testcontainers.DynamoDbContainer;
import com.jordansimsmith.time.FakeClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

@Testcontainers
public class UpdatePageContentHandlerIntegrationTest {
  private FakeClock fakeClock;
  private FakeNotificationPublisher fakeNotificationPublisher;
  private FakeSubfootballClient fakeSubfootballClient;
  private DynamoDbTable<SubfootballTrackerItem> subfootballTrackerTable;

  private UpdatePageContentHandler updatePageContentHandler;

  @Container DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeEach
  void setUp() {
    var factory = SubfootballTrackerTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    fakeNotificationPublisher = factory.fakeNotificationPublisher();
    fakeSubfootballClient = factory.fakeSubfootballClient();
    subfootballTrackerTable = factory.subfootballTrackerTable();

    DynamoDbUtils.createTable(factory.dynamoDbClient(), subfootballTrackerTable);

    updatePageContentHandler = new UpdatePageContentHandler(factory);
  }

  @Test
  void handleRequestShouldUpdatePageContent() {
    // arrange
    var contentHistory1 =
        SubfootballTrackerItem.create(
            SubfootballTrackerItem.Page.REGISTRATION, 1_000, "content 1\ncontent 1");
    var contentHistory2 =
        SubfootballTrackerItem.create(
            SubfootballTrackerItem.Page.REGISTRATION, 2_000, "content 2\ncontent 2");
    subfootballTrackerTable.putItem(contentHistory1);
    subfootballTrackerTable.putItem(contentHistory2);

    fakeClock.setTime(3_000);

    fakeSubfootballClient.setRegistrationContent("content 3\ncontent 3");

    // act
    updatePageContentHandler.handleRequest(new ScheduledEvent(), null);

    // assert
    var contentHistory3 =
        subfootballTrackerTable.getItem(
            Key.builder()
                .partitionValue(
                    SubfootballTrackerItem.formatPk(SubfootballTrackerItem.Page.REGISTRATION))
                .sortValue(SubfootballTrackerItem.formatSk(fakeClock.now().getEpochSecond()))
                .build());
    assertThat(contentHistory3).isNotNull();
    assertThat(contentHistory3.getPage()).isEqualTo(SubfootballTrackerItem.Page.REGISTRATION);
    assertThat(contentHistory3.getTimestamp()).isEqualTo(fakeClock.now().getEpochSecond());
    assertThat(contentHistory3.getContent())
        .isEqualTo(fakeSubfootballClient.getRegistrationContent());

    var notifications = fakeNotificationPublisher.findNotifications(UpdatePageContentHandler.TOPIC);
    assertThat(notifications.size()).isEqualTo(1);
    var notification = notifications.get(0);
    assertThat(notification.subject()).isEqualTo("SUB Football registration page updated");
    assertThat(notification.message())
        .isEqualTo("The latest content reads:\r\n\r\ncontent 3\r\n\r\ncontent 3");
  }
}
