package com.jordansimsmith.dynamodb;

import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class EpochSecondConverter implements AttributeConverter<Instant> {
  @Override
  public AttributeValue transformFrom(Instant instant) {
    return AttributeValue.builder().n(String.valueOf(instant.getEpochSecond())).build();
  }

  @Override
  public Instant transformTo(AttributeValue attributeValue) {
    return Instant.ofEpochSecond(Long.parseLong(attributeValue.n()));
  }

  @Override
  public EnhancedType<Instant> type() {
    return EnhancedType.of(Instant.class);
  }

  @Override
  public AttributeValueType attributeValueType() {
    return AttributeValueType.N;
  }
}
