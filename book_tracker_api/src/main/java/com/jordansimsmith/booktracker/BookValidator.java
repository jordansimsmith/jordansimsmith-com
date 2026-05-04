package com.jordansimsmith.booktracker;

import com.jordansimsmith.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class BookValidator {

  static final Pattern OPEN_LIBRARY_WORK_ID_PATTERN = Pattern.compile("^OL[0-9]+W$");

  public static class ValidationException extends RuntimeException {
    public ValidationException(String message) {
      super(message);
    }
  }

  private final Clock clock;

  @Inject
  BookValidator(Clock clock) {
    this.clock = clock;
  }

  public void validate(Book book) {
    if (book.openLibraryWorkId() == null || book.openLibraryWorkId().isBlank()) {
      throw new ValidationException("open_library_work_id is required");
    }
    if (!OPEN_LIBRARY_WORK_ID_PATTERN.matcher(book.openLibraryWorkId()).matches()) {
      throw new ValidationException("open_library_work_id must match ^OL[0-9]+W$");
    }
    if (book.title() == null || book.title().isBlank()) {
      throw new ValidationException("title is required");
    }
    if (book.coverUrl() != null && book.coverUrl().isBlank()) {
      throw new ValidationException("cover_url must not be blank");
    }
    if (book.pageCount() != null && book.pageCount() <= 0) {
      throw new ValidationException("page_count must be a positive integer");
    }
    if (book.publicationYear() != null) {
      var maxYear = LocalDate.ofInstant(clock.now(), ZoneOffset.UTC).getYear() + 5;
      if (book.publicationYear() < 0 || book.publicationYear() > maxYear) {
        throw new ValidationException("publication_year must be between 0 and " + maxYear);
      }
    }
    validateFinishedDate(book.finishedDate());
  }

  public void validateFinishedDate(String finishedDate) {
    if (finishedDate == null || finishedDate.isBlank()) {
      throw new ValidationException("finished_date is required");
    }
    try {
      LocalDate.parse(finishedDate);
    } catch (DateTimeParseException e) {
      throw new ValidationException("finished_date must be in YYYY-MM-DD format");
    }
  }
}
