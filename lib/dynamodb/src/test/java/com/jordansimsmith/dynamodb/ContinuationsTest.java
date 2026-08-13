package com.jordansimsmith.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class ContinuationsTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldRoundTripStringAttributes() {
    // arrange
    var key =
        Map.of(
            "pk", AttributeValue.builder().s("USER#jordan#SKU#abc#normal#NM").build(),
            "sk", AttributeValue.builder().s("SKU").build());

    // act
    var encoded = Continuations.encode(key, objectMapper);
    var decoded = Continuations.decode(encoded, objectMapper);

    // assert
    assertThat(encoded).isNotNull();
    assertThat(encoded).doesNotContain("USER#");
    assertThat(decoded).isEqualTo(key);
  }

  @Test
  void shouldRoundTripNumericAttributes() {
    // arrange
    var key =
        Map.of(
            "pk", AttributeValue.builder().s("USER#jordan").build(),
            "sk", AttributeValue.builder().n("42").build());

    // act
    var encoded = Continuations.encode(key, objectMapper);
    var decoded = Continuations.decode(encoded, objectMapper);

    // assert
    assertThat(decoded).isEqualTo(key);
  }

  @Test
  void shouldRoundTripGsiKeyAttributes() {
    // arrange
    var key =
        Map.of(
            "gsi2pk", AttributeValue.builder().s("USER#jordan#SKUS").build(),
            "gsi2sk", AttributeValue.builder().s("NAME#elvish aberration#abc#normal#NM").build(),
            "pk", AttributeValue.builder().s("USER#jordan#SKU#abc#normal#NM").build(),
            "sk", AttributeValue.builder().s("SKU").build());

    // act
    var encoded = Continuations.encode(key, objectMapper);
    var decoded = Continuations.decode(encoded, objectMapper);

    // assert
    assertThat(decoded).isEqualTo(key);
  }

  @Test
  void shouldReturnNullForNullInput() {
    assertThat(Continuations.encode(null, objectMapper)).isNull();
    assertThat(Continuations.decode(null, objectMapper)).isNull();
  }

  @Test
  void shouldReturnNullForEmptyInput() {
    assertThat(Continuations.encode(Map.of(), objectMapper)).isNull();
    assertThat(Continuations.decode("", objectMapper)).isNull();
  }
}
