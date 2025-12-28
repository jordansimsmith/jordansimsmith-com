package com.jordansimsmith.packinglist;

import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class TripItemStatusConverter implements AttributeConverter<TripItemStatus> {
  @Override
  public AttributeValue transformFrom(TripItemStatus status) {
    return AttributeValue.builder().s(status.getValue()).build();
  }

  @Override
  public TripItemStatus transformTo(AttributeValue attributeValue) {
    return TripItemStatus.fromValue(attributeValue.s());
  }

  @Override
  public EnhancedType<TripItemStatus> type() {
    return EnhancedType.of(TripItemStatus.class);
  }

  @Override
  public AttributeValueType attributeValueType() {
    return AttributeValueType.S;
  }
}
