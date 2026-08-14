package com.jordansimsmith.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class SqsQueueClient<T> implements QueueClient<T> {
  private final SqsClient sqsClient;
  private final ObjectMapper objectMapper;
  private final String queueUrl;

  public SqsQueueClient(SqsClient sqsClient, ObjectMapper objectMapper, String queueUrl) {
    this.sqsClient = sqsClient;
    this.objectMapper = objectMapper;
    this.queueUrl = queueUrl;
  }

  public static <T> SqsQueueClient<T> create(
      SqsClient sqsClient, ObjectMapper objectMapper, String queueName) {
    var queueUrl = sqsClient.getQueueUrl(b -> b.queueName(queueName)).queueUrl();
    return new SqsQueueClient<>(sqsClient, objectMapper, queueUrl);
  }

  @Override
  public void send(T message) {
    try {
      var body = objectMapper.writeValueAsString(message);
      sqsClient.sendMessage(
          SendMessageRequest.builder().queueUrl(queueUrl).messageBody(body).build());
    } catch (Exception e) {
      throw new RuntimeException("failed to send message to queue", e);
    }
  }

  @Override
  public void send(T message, String messageGroupId) {
    try {
      var body = objectMapper.writeValueAsString(message);
      sqsClient.sendMessage(
          SendMessageRequest.builder()
              .queueUrl(queueUrl)
              .messageBody(body)
              .messageGroupId(messageGroupId)
              .build());
    } catch (Exception e) {
      throw new RuntimeException("failed to send message to queue", e);
    }
  }
}
