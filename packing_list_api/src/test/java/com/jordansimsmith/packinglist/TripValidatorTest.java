package com.jordansimsmith.packinglist;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TripValidatorTest {

  private TripValidator tripValidator;

  @BeforeEach
  void setUp() {
    tripValidator = new TripValidator();
  }

  @Test
  void validateShouldPassForValidTrip() {
    // arrange
    var trip =
        new Trip(
            "trip-123",
            "Japan 2026",
            "Tokyo",
            "2026-01-12",
            "2026-01-26",
            List.of(new Trip.Item("passport", "travel", 1, List.of(), "unpacked")),
            1700000000,
            1700000000);

    // act & assert
    assertThatCode(() -> tripValidator.validate(trip)).doesNotThrowAnyException();
  }

  @Test
  void validateShouldThrowWhenNameIsNull() {
    // arrange
    var trip =
        new Trip(
            "trip-123",
            null,
            "Tokyo",
            "2026-01-12",
            "2026-01-26",
            List.of(new Trip.Item("passport", "travel", 1, List.of(), "unpacked")),
            1700000000,
            1700000000);

    // act & assert
    assertThatThrownBy(() -> tripValidator.validate(trip))
        .isInstanceOf(TripValidator.ValidationException.class)
        .hasMessage("name is required");
  }

  @Test
  void validateShouldThrowWhenNameIsBlank() {
    // arrange
    var trip =
        new Trip(
            "trip-123",
            "   ",
            "Tokyo",
            "2026-01-12",
            "2026-01-26",
            List.of(new Trip.Item("passport", "travel", 1, List.of(), "unpacked")),
            1700000000,
            1700000000);

    // act & assert
    assertThatThrownBy(() -> tripValidator.validate(trip))
        .isInstanceOf(TripValidator.ValidationException.class)
        .hasMessage("name is required");
  }

  @Test
  void validateShouldThrowWhenDestinationIsNull() {
    // arrange
    var trip =
        new Trip(
            "trip-123",
            "Japan 2026",
            null,
            "2026-01-12",
            "2026-01-26",
            List.of(new Trip.Item("passport", "travel", 1, List.of(), "unpacked")),
            1700000000,
            1700000000);

    // act & assert
    assertThatThrownBy(() -> tripValidator.validate(trip))
        .isInstanceOf(TripValidator.ValidationException.class)
        .hasMessage("destination is required");
  }

  @Test
  void validateShouldThrowWhenDepartureDateIsNull() {
    // arrange
    var trip =
        new Trip(
            "trip-123",
            "Japan 2026",
            "Tokyo",
            null,
            "2026-01-26",
            List.of(new Trip.Item("passport", "travel", 1, List.of(), "unpacked")),
            1700000000,
            1700000000);

    // act & assert
    assertThatThrownBy(() -> tripValidator.validate(trip))
        .isInstanceOf(TripValidator.ValidationException.class)
        .hasMessage("departure_date is required");
  }

  @Test
  void validateShouldThrowWhenReturnDateIsNull() {
    // arrange
    var trip =
        new Trip(
            "trip-123",
            "Japan 2026",
            "Tokyo",
            "2026-01-12",
            null,
            List.of(new Trip.Item("passport", "travel", 1, List.of(), "unpacked")),
            1700000000,
            1700000000);

    // act & assert
    assertThatThrownBy(() -> tripValidator.validate(trip))
        .isInstanceOf(TripValidator.ValidationException.class)
        .hasMessage("return_date is required");
  }

  @Test
  void validateShouldThrowWhenDepartureDateIsInvalidFormat() {
    // arrange
    var trip =
        new Trip(
            "trip-123",
            "Japan 2026",
            "Tokyo",
            "01-12-2026",
            "2026-01-26",
            List.of(new Trip.Item("passport", "travel", 1, List.of(), "unpacked")),
            1700000000,
            1700000000);

    // act & assert
    assertThatThrownBy(() -> tripValidator.validate(trip))
        .isInstanceOf(TripValidator.ValidationException.class)
        .hasMessage("departure_date must be in YYYY-MM-DD format");
  }

  @Test
  void validateShouldThrowWhenReturnDateIsInvalidFormat() {
    // arrange
    var trip =
        new Trip(
            "trip-123",
            "Japan 2026",
            "Tokyo",
            "2026-01-12",
            "invalid-date",
            List.of(new Trip.Item("passport", "travel", 1, List.of(), "unpacked")),
            1700000000,
            1700000000);

    // act & assert
    assertThatThrownBy(() -> tripValidator.validate(trip))
        .isInstanceOf(TripValidator.ValidationException.class)
        .hasMessage("return_date must be in YYYY-MM-DD format");
  }

  @Test
  void validateShouldThrowWhenItemsIsNull() {
    // arrange
    var trip =
        new Trip(
            "trip-123",
            "Japan 2026",
            "Tokyo",
            "2026-01-12",
            "2026-01-26",
            null,
            1700000000,
            1700000000);

    // act & assert
    assertThatThrownBy(() -> tripValidator.validate(trip))
        .isInstanceOf(TripValidator.ValidationException.class)
        .hasMessage("items is required");
  }

  @Test
  void validateShouldThrowWhenItemsIsEmpty() {
    // arrange
    var trip =
        new Trip(
            "trip-123",
            "Japan 2026",
            "Tokyo",
            "2026-01-12",
            "2026-01-26",
            List.of(),
            1700000000,
            1700000000);

    // act & assert
    assertThatThrownBy(() -> tripValidator.validate(trip))
        .isInstanceOf(TripValidator.ValidationException.class)
        .hasMessage("items is required");
  }

  @Test
  void validateShouldThrowWhenItemNameIsNull() {
    // arrange
    var trip =
        new Trip(
            "trip-123",
            "Japan 2026",
            "Tokyo",
            "2026-01-12",
            "2026-01-26",
            List.of(new Trip.Item(null, "travel", 1, List.of(), "unpacked")),
            1700000000,
            1700000000);

    // act & assert
    assertThatThrownBy(() -> tripValidator.validate(trip))
        .isInstanceOf(TripValidator.ValidationException.class)
        .hasMessage("item name is required");
  }

  @Test
  void validateShouldThrowWhenItemCategoryIsNull() {
    // arrange
    var trip =
        new Trip(
            "trip-123",
            "Japan 2026",
            "Tokyo",
            "2026-01-12",
            "2026-01-26",
            List.of(new Trip.Item("passport", null, 1, List.of(), "unpacked")),
            1700000000,
            1700000000);

    // act & assert
    assertThatThrownBy(() -> tripValidator.validate(trip))
        .isInstanceOf(TripValidator.ValidationException.class)
        .hasMessage("item category is required");
  }

  @Test
  void validateShouldThrowWhenItemQuantityIsZero() {
    // arrange
    var trip =
        new Trip(
            "trip-123",
            "Japan 2026",
            "Tokyo",
            "2026-01-12",
            "2026-01-26",
            List.of(new Trip.Item("passport", "travel", 0, List.of(), "unpacked")),
            1700000000,
            1700000000);

    // act & assert
    assertThatThrownBy(() -> tripValidator.validate(trip))
        .isInstanceOf(TripValidator.ValidationException.class)
        .hasMessage("quantity must be >= 1");
  }

  @Test
  void validateShouldThrowWhenItemStatusIsNull() {
    // arrange
    var trip =
        new Trip(
            "trip-123",
            "Japan 2026",
            "Tokyo",
            "2026-01-12",
            "2026-01-26",
            List.of(new Trip.Item("passport", "travel", 1, List.of(), null)),
            1700000000,
            1700000000);

    // act & assert
    assertThatThrownBy(() -> tripValidator.validate(trip))
        .isInstanceOf(TripValidator.ValidationException.class)
        .hasMessage("item status is required");
  }

  @Test
  void validateShouldThrowWhenItemStatusIsInvalid() {
    // arrange
    var trip =
        new Trip(
            "trip-123",
            "Japan 2026",
            "Tokyo",
            "2026-01-12",
            "2026-01-26",
            List.of(new Trip.Item("passport", "travel", 1, List.of(), "invalid")),
            1700000000,
            1700000000);

    // act & assert
    assertThatThrownBy(() -> tripValidator.validate(trip))
        .isInstanceOf(TripValidator.ValidationException.class)
        .hasMessage("invalid item status: invalid");
  }

  @Test
  void validateShouldThrowWhenDuplicateItemNames() {
    // arrange
    var trip =
        new Trip(
            "trip-123",
            "Japan 2026",
            "Tokyo",
            "2026-01-12",
            "2026-01-26",
            List.of(
                new Trip.Item("passport", "travel", 1, List.of(), "unpacked"),
                new Trip.Item("passport", "documents", 1, List.of(), "unpacked")),
            1700000000,
            1700000000);

    // act & assert
    assertThatThrownBy(() -> tripValidator.validate(trip))
        .isInstanceOf(TripValidator.ValidationException.class)
        .hasMessage("duplicate item name: passport");
  }

  @Test
  void validateShouldThrowWhenDuplicateItemNamesCaseInsensitive() {
    // arrange
    var trip =
        new Trip(
            "trip-123",
            "Japan 2026",
            "Tokyo",
            "2026-01-12",
            "2026-01-26",
            List.of(
                new Trip.Item("Passport", "travel", 1, List.of(), "unpacked"),
                new Trip.Item("PASSPORT", "documents", 1, List.of(), "unpacked")),
            1700000000,
            1700000000);

    // act & assert
    assertThatThrownBy(() -> tripValidator.validate(trip))
        .isInstanceOf(TripValidator.ValidationException.class)
        .hasMessage("duplicate item name: PASSPORT");
  }

  @Test
  void validateShouldPassForAllValidStatuses() {
    // arrange
    var trip =
        new Trip(
            "trip-123",
            "Japan 2026",
            "Tokyo",
            "2026-01-12",
            "2026-01-26",
            List.of(
                new Trip.Item("passport", "travel", 1, List.of(), "unpacked"),
                new Trip.Item("toothbrush", "toiletries", 1, List.of(), "packed"),
                new Trip.Item("phone charger", "electronics", 1, List.of(), "pack-just-in-time")),
            1700000000,
            1700000000);

    // act & assert
    assertThatCode(() -> tripValidator.validate(trip)).doesNotThrowAnyException();
  }
}
