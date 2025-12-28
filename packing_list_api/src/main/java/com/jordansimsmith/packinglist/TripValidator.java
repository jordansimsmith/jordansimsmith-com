package com.jordansimsmith.packinglist;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class TripValidator {

  public static class ValidationException extends RuntimeException {
    public ValidationException(String message) {
      super(message);
    }
  }

  @Inject
  TripValidator() {}

  public void validate(Trip trip) {
    if (trip.name() == null || trip.name().isBlank()) {
      throw new ValidationException("name is required");
    }
    if (trip.destination() == null || trip.destination().isBlank()) {
      throw new ValidationException("destination is required");
    }
    if (trip.departureDate() == null || trip.departureDate().isBlank()) {
      throw new ValidationException("departure_date is required");
    }
    if (trip.returnDate() == null || trip.returnDate().isBlank()) {
      throw new ValidationException("return_date is required");
    }
    if (!isValidLocalDate(trip.departureDate())) {
      throw new ValidationException("departure_date must be in YYYY-MM-DD format");
    }
    if (!isValidLocalDate(trip.returnDate())) {
      throw new ValidationException("return_date must be in YYYY-MM-DD format");
    }
    if (trip.items() == null || trip.items().isEmpty()) {
      throw new ValidationException("items is required");
    }

    var normalizedNames = new HashSet<String>();
    for (var item : trip.items()) {
      if (item.name() == null || item.name().isBlank()) {
        throw new ValidationException("item name is required");
      }
      if (item.category() == null || item.category().isBlank()) {
        throw new ValidationException("item category is required");
      }
      if (item.quantity() < 1) {
        throw new ValidationException("quantity must be >= 1");
      }
      if (item.status() == null || item.status().isBlank()) {
        throw new ValidationException("item status is required");
      }
      try {
        TripItemStatus.fromValue(item.status());
      } catch (IllegalArgumentException e) {
        throw new ValidationException("invalid item status: " + item.status());
      }

      var normalizedName = normalizeName(item.name());
      if (normalizedNames.contains(normalizedName)) {
        throw new ValidationException("duplicate item name: " + item.name());
      }
      normalizedNames.add(normalizedName);
    }
  }

  private boolean isValidLocalDate(String date) {
    try {
      LocalDate.parse(date);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  private String normalizeName(String name) {
    return name.toLowerCase().trim().replaceAll("\\s+", " ");
  }
}
