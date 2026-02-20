package com.jordansimsmith.queue;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

public class QueueUtils {
  public static void reset(SqsClient sqsClient) {
    var queueUrls = sqsClient.listQueues().queueUrls();

    for (var queueUrl : queueUrls) {
      while (true) {
        var receiveRequest =
            ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(0)
                .build();
        var messages = sqsClient.receiveMessage(receiveRequest).messages();

        if (messages.isEmpty()) {
          break;
        }

        for (var message : messages) {
          sqsClient.deleteMessage(
              b -> b.queueUrl(queueUrl).receiptHandle(message.receiptHandle()).build());
        }
      }
    }
  }
}
