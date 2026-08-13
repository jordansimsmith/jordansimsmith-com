package com.jordansimsmith.queue;

import java.util.ArrayList;
import java.util.List;

public class FakeQueueClient<T> implements QueueClient<T> {
  private final List<T> messages = new ArrayList<>();

  @Override
  public void send(T message) {
    messages.add(message);
  }

  public List<T> getMessages() {
    return List.copyOf(messages);
  }

  public void reset() {
    messages.clear();
  }
}
