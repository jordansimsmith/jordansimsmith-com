package com.jordansimsmith.queue;

import java.util.ArrayList;
import java.util.List;

public class FakeQueueClient<T> implements QueueClient<T> {
  public record SentMessage<T>(T message, String messageGroupId, String messageDeduplicationId) {}

  private final List<SentMessage<T>> sends = new ArrayList<>();

  @Override
  public void send(T message) {
    sends.add(new SentMessage<>(message, null, null));
  }

  @Override
  public void send(T message, String messageGroupId) {
    sends.add(new SentMessage<>(message, messageGroupId, null));
  }

  @Override
  public void send(T message, String messageGroupId, String messageDeduplicationId) {
    sends.add(new SentMessage<>(message, messageGroupId, messageDeduplicationId));
  }

  public List<T> getMessages() {
    return sends.stream().map(SentMessage::message).toList();
  }

  public List<SentMessage<T>> getSends() {
    return List.copyOf(sends);
  }

  public void reset() {
    sends.clear();
  }
}
