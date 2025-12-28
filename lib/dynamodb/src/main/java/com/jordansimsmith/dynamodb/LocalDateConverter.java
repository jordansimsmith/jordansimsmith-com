package com.jordansimsmith.dynamodb;

import java.time.LocalDate;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class LocalDateConverter implements AttributeConverter<LocalDate> {
  @Override
  public AttributeValue transformFrom(LocalDate localDate) {
    return AttributeValue.builder().s(localDate.toString()).build();
  }

  @Override
  public LocalDate transformTo(AttributeValue attributeValue) {
    return LocalDate.parse(attributeValue.s());
  }

  @Override
  public EnhancedType<LocalDate> type() {
    return EnhancedType.of(LocalDate.class);
  }

  @Override
  public AttributeValueType attributeValueType() {
    return AttributeValueType.S;
  }
}
