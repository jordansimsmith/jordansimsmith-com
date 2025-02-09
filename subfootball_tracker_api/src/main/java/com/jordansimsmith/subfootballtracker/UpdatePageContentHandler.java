package com.jordansimsmith.subfootballtracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.notifications.NotificationPublisher;
import com.jordansimsmith.time.Clock;
import java.util.StringJoiner;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class UpdatePageContentHandler implements RequestHandler<ScheduledEvent, Void> {
  @VisibleForTesting static final String TOPIC = "subfootball_tracker_api_page_content_updates";

  private final Clock clock;
  private final NotificationPublisher notificationPublisher;
  private final DynamoDbTable<SubfootballTrackerItem> subfootballTrackerTable;
  private final SubfootballClient subfootballClient;

  public UpdatePageContentHandler() {
    this(SubfootballTrackerFactory.create());
  }

  @VisibleForTesting
  UpdatePageContentHandler(SubfootballTrackerFactory factory) {
    this.clock = factory.clock();
    this.notificationPublisher = factory.notificationPublisher();
    this.subfootballTrackerTable = factory.subfootballTrackerTable();
    this.subfootballClient = factory.subfootballClient();
  }

  @Override
  public Void handleRequest(ScheduledEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private Void doHandleRequest(ScheduledEvent event, Context context) throws Exception {
    var now = clock.now();

    var content = subfootballClient.getRegistrationContent();
    var previous =
        subfootballTrackerTable
            .query(
                QueryEnhancedRequest.builder()
                    .queryConditional(
                        QueryConditional.keyEqualTo(
                            Key.builder()
                                .partitionValue(
                                    SubfootballTrackerItem.formatPk(
                                        SubfootballTrackerItem.Page.REGISTRATION))
                                .build()))
                    .limit(1)
                    .scanIndexForward(false)
                    .build())
            .items()
            .stream()
            .findFirst()
            .orElse(null);

    if (previous != null && !previous.getContent().equals(content)) {
      var subject = "SUB Football registration page updated";
      var message = new StringJoiner("\r\n\r\n");
      message.add("The latest content reads:");
      var lines = content.split("\\r?\\n");
      for (var line : lines) {
        message.add(line);
      }

      notificationPublisher.publish(TOPIC, subject, message.toString());
    }

    var current =
        SubfootballTrackerItem.create(
            SubfootballTrackerItem.Page.REGISTRATION, now.getEpochSecond(), content);
    subfootballTrackerTable.putItem(current);

    return null;
  }
}
