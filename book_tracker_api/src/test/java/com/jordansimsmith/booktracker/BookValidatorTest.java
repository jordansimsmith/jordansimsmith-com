package com.jordansimsmith.booktracker;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.time.FakeClock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BookValidatorTest {

  private FakeClock fakeClock;
  private BookValidator bookValidator;

  @BeforeEach
  void setUp() {
    fakeClock = new FakeClock();
    fakeClock.setTime(LocalDate.of(2026, 5, 4).atStartOfDay().toInstant(ZoneOffset.UTC));
    bookValidator = new BookValidator(fakeClock);
  }

  private Book validBook() {
    return new Book(
        "OL27448W",
        "The Lord of the Rings",
        List.of("J.R.R. Tolkien"),
        "https://covers.openlibrary.org/b/id/14625765-L.jpg",
        1193,
        1954,
        "2026-05-04",
        1714809600L,
        1714809600L);
  }

  @Test
  void validateShouldPassForValidBook() {
    // arrange
    var book = validBook();

    // act and assert
    assertThatCode(() -> bookValidator.validate(book)).doesNotThrowAnyException();
  }

  @Test
  void validateShouldThrowWhenOpenLibraryWorkIdIsNull() {
    // arrange
    var book =
        new Book(null, "The Lord of the Rings", List.of(), null, null, null, "2026-05-04", 0L, 0L);

    // act and assert
    assertThatThrownBy(() -> bookValidator.validate(book))
        .isInstanceOf(BookValidator.ValidationException.class)
        .hasMessage("open_library_work_id is required");
  }

  @Test
  void validateShouldThrowWhenOpenLibraryWorkIdIsBlank() {
    // arrange
    var book =
        new Book("  ", "The Lord of the Rings", List.of(), null, null, null, "2026-05-04", 0L, 0L);

    // act and assert
    assertThatThrownBy(() -> bookValidator.validate(book))
        .isInstanceOf(BookValidator.ValidationException.class)
        .hasMessage("open_library_work_id is required");
  }

  @Test
  void validateShouldThrowWhenOpenLibraryWorkIdHasWrongFormat() {
    // arrange
    var book =
        new Book(
            "OL12345", "The Lord of the Rings", List.of(), null, null, null, "2026-05-04", 0L, 0L);

    // act and assert
    assertThatThrownBy(() -> bookValidator.validate(book))
        .isInstanceOf(BookValidator.ValidationException.class)
        .hasMessage("open_library_work_id must match ^OL[0-9]+W$");
  }

  @Test
  void validateShouldThrowWhenOpenLibraryWorkIdIsLowerCase() {
    // arrange
    var book =
        new Book(
            "ol27448w", "The Lord of the Rings", List.of(), null, null, null, "2026-05-04", 0L, 0L);

    // act and assert
    assertThatThrownBy(() -> bookValidator.validate(book))
        .isInstanceOf(BookValidator.ValidationException.class)
        .hasMessage("open_library_work_id must match ^OL[0-9]+W$");
  }

  @Test
  void validateShouldThrowWhenTitleIsNull() {
    // arrange
    var book = new Book("OL27448W", null, List.of(), null, null, null, "2026-05-04", 0L, 0L);

    // act and assert
    assertThatThrownBy(() -> bookValidator.validate(book))
        .isInstanceOf(BookValidator.ValidationException.class)
        .hasMessage("title is required");
  }

  @Test
  void validateShouldThrowWhenTitleIsBlank() {
    // arrange
    var book = new Book("OL27448W", "   ", List.of(), null, null, null, "2026-05-04", 0L, 0L);

    // act and assert
    assertThatThrownBy(() -> bookValidator.validate(book))
        .isInstanceOf(BookValidator.ValidationException.class)
        .hasMessage("title is required");
  }

  @Test
  void validateShouldPassWhenCoverUrlIsNull() {
    // arrange
    var book =
        new Book(
            "OL27448W", "The Lord of the Rings", List.of(), null, null, null, "2026-05-04", 0L, 0L);

    // act and assert
    assertThatCode(() -> bookValidator.validate(book)).doesNotThrowAnyException();
  }

  @Test
  void validateShouldThrowWhenCoverUrlIsBlank() {
    // arrange
    var book =
        new Book(
            "OL27448W", "The Lord of the Rings", List.of(), "  ", null, null, "2026-05-04", 0L, 0L);

    // act and assert
    assertThatThrownBy(() -> bookValidator.validate(book))
        .isInstanceOf(BookValidator.ValidationException.class)
        .hasMessage("cover_url must not be blank");
  }

  @Test
  void validateShouldThrowWhenPageCountIsZero() {
    // arrange
    var book =
        new Book(
            "OL27448W", "The Lord of the Rings", List.of(), null, 0, null, "2026-05-04", 0L, 0L);

    // act and assert
    assertThatThrownBy(() -> bookValidator.validate(book))
        .isInstanceOf(BookValidator.ValidationException.class)
        .hasMessage("page_count must be a positive integer");
  }

  @Test
  void validateShouldThrowWhenPageCountIsNegative() {
    // arrange
    var book =
        new Book(
            "OL27448W", "The Lord of the Rings", List.of(), null, -10, null, "2026-05-04", 0L, 0L);

    // act and assert
    assertThatThrownBy(() -> bookValidator.validate(book))
        .isInstanceOf(BookValidator.ValidationException.class)
        .hasMessage("page_count must be a positive integer");
  }

  @Test
  void validateShouldThrowWhenPublicationYearIsNegative() {
    // arrange
    var book =
        new Book(
            "OL27448W", "The Lord of the Rings", List.of(), null, null, -1, "2026-05-04", 0L, 0L);

    // act and assert
    assertThatThrownBy(() -> bookValidator.validate(book))
        .isInstanceOf(BookValidator.ValidationException.class)
        .hasMessage("publication_year must be between 0 and 2031");
  }

  @Test
  void validateShouldThrowWhenPublicationYearTooFarInFuture() {
    // arrange
    var book =
        new Book(
            "OL27448W", "The Lord of the Rings", List.of(), null, null, 2032, "2026-05-04", 0L, 0L);

    // act and assert
    assertThatThrownBy(() -> bookValidator.validate(book))
        .isInstanceOf(BookValidator.ValidationException.class)
        .hasMessage("publication_year must be between 0 and 2031");
  }

  @Test
  void validateShouldPassWhenPublicationYearIsAtUpperBound() {
    // arrange
    var book =
        new Book(
            "OL27448W", "The Lord of the Rings", List.of(), null, null, 2031, "2026-05-04", 0L, 0L);

    // act and assert
    assertThatCode(() -> bookValidator.validate(book)).doesNotThrowAnyException();
  }

  @Test
  void validateShouldPassWhenPublicationYearIsZero() {
    // arrange
    var book =
        new Book(
            "OL27448W", "The Lord of the Rings", List.of(), null, null, 0, "2026-05-04", 0L, 0L);

    // act and assert
    assertThatCode(() -> bookValidator.validate(book)).doesNotThrowAnyException();
  }

  @Test
  void validateShouldThrowWhenFinishedDateIsNull() {
    // arrange
    var book =
        new Book("OL27448W", "The Lord of the Rings", List.of(), null, null, null, null, 0L, 0L);

    // act and assert
    assertThatThrownBy(() -> bookValidator.validate(book))
        .isInstanceOf(BookValidator.ValidationException.class)
        .hasMessage("finished_date is required");
  }

  @Test
  void validateShouldThrowWhenFinishedDateIsBlank() {
    // arrange
    var book =
        new Book("OL27448W", "The Lord of the Rings", List.of(), null, null, null, "  ", 0L, 0L);

    // act and assert
    assertThatThrownBy(() -> bookValidator.validate(book))
        .isInstanceOf(BookValidator.ValidationException.class)
        .hasMessage("finished_date is required");
  }

  @Test
  void validateShouldThrowWhenFinishedDateHasWrongFormat() {
    // arrange
    var book =
        new Book(
            "OL27448W", "The Lord of the Rings", List.of(), null, null, null, "04-05-2026", 0L, 0L);

    // act and assert
    assertThatThrownBy(() -> bookValidator.validate(book))
        .isInstanceOf(BookValidator.ValidationException.class)
        .hasMessage("finished_date must be in YYYY-MM-DD format");
  }

  @Test
  void validateShouldPassWithEmptyAuthorsList() {
    // arrange
    var book =
        new Book(
            "OL27448W", "The Lord of the Rings", List.of(), null, null, null, "2026-05-04", 0L, 0L);

    // act and assert
    assertThatCode(() -> bookValidator.validate(book)).doesNotThrowAnyException();
  }

  @Test
  void validateShouldPassWhenFinishedDateIsInTheFuture() {
    // arrange
    var book =
        new Book(
            "OL27448W", "The Lord of the Rings", List.of(), null, null, null, "2099-12-31", 0L, 0L);

    // act and assert
    assertThatCode(() -> bookValidator.validate(book)).doesNotThrowAnyException();
  }
}
