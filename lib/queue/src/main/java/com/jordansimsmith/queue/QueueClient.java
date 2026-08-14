package com.jordansimsmith.queue;

public interface QueueClient<T> {
  void send(T message);

  void send(T message, String messageGroupId);
}
