package com.jordansimsmith.dynamodb;

import java.time.Duration;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class DurationSecondsConverter implements AttributeConverter<Duration> {
  @Override
  public AttributeValue transformFrom(Duration duration) {
    return AttributeValue.builder().n(String.valueOf(duration.getSeconds())).build();
  }

  @Override
  public Duration transformTo(AttributeValue attributeValue) {
    return Duration.ofSeconds(Long.parseLong(attributeValue.n()));
  }

  @Override
  public EnhancedType<Duration> type() {
    return EnhancedType.of(Duration.class);
  }

  @Override
  public AttributeValueType attributeValueType() {
    return AttributeValueType.N;
  }
}
