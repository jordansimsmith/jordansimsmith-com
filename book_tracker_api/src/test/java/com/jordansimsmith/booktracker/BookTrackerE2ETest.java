package com.jordansimsmith.booktracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Testcontainers
public class BookTrackerE2ETest {

  @Container private static final BookTrackerContainer container = new BookTrackerContainer();

  private HttpClient httpClient;
  private ObjectMapper objectMapper;
  private URI apiUrl;
  private String authHeader;

  @BeforeEach
  void setUp() {
    var dynamoDbClient =
        DynamoDbClient.builder().endpointOverride(container.getLocalstackUrl()).build();
    DynamoDbUtils.reset(dynamoDbClient);

    httpClient = HttpClient.newHttpClient();
    objectMapper = new ObjectMapper();
    apiUrl = container.getApiUrl();
    authHeader =
        "Basic "
            + Base64.getEncoder().encodeToString("alice:password".getBytes(StandardCharsets.UTF_8));
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder()
        .uri(URI.create(apiUrl + path))
        .header("Authorization", authHeader);
  }

  @Test
  void shouldCreateListGetUpdateAndDeleteBook() throws IOException, InterruptedException {
    var coverUrl = "https://covers.openlibrary.org/b/id/14625765-L.jpg";
    var createBody =
        """
        {
          "open_library_work_id": "OL27448W",
          "title": "The Lord of the Rings",
          "authors": ["J.R.R. Tolkien"],
          "cover_url": "%s",
          "page_count": 1193,
          "publication_year": 1954,
          "finished_date": "%s"
        }
        """
            .formatted(coverUrl, LocalDate.now().toString());

    // create
    var createResponse =
        httpClient.send(
            request("/books")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(createBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(createResponse.statusCode()).isEqualTo(201);

    var created =
        objectMapper.readValue(createResponse.body(), CreateBookHandler.CreateBookResponse.class);
    assertThat(created.book().openLibraryWorkId()).isEqualTo("OL27448W");
    assertThat(created.book().title()).isEqualTo("The Lord of the Rings");
    assertThat(created.book().authors()).containsExactly("J.R.R. Tolkien");
    assertThat(created.book().coverUrl())
        .as("cover_url should round-trip unchanged")
        .isEqualTo(coverUrl);
    assertThat(created.book().pageCount()).isEqualTo(1193);
    assertThat(created.book().publicationYear()).isEqualTo(1954);
    assertThat(created.book().createdAt()).isPositive();
    assertThat(created.book().updatedAt()).isEqualTo(created.book().createdAt());
    var originalCreatedAt = created.book().createdAt();

    // list books and check rolling count
    var listResponse =
        httpClient.send(request("/books").GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(listResponse.statusCode()).isEqualTo(200);

    var listBody =
        objectMapper.readValue(listResponse.body(), FindBooksHandler.FindBooksResponse.class);
    assertThat(listBody.books()).hasSize(1);
    assertThat(listBody.books().get(0).openLibraryWorkId()).isEqualTo("OL27448W");
    assertThat(listBody.books().get(0).coverUrl())
        .as("cover_url should round-trip unchanged on list response")
        .isEqualTo(coverUrl);
    assertThat(listBody.rollingTwelveMonthCount()).isEqualTo(1);

    // duplicate add returns 409
    var duplicateResponse =
        httpClient.send(
            request("/books")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(createBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(duplicateResponse.statusCode()).isEqualTo(409);
    var duplicateBody =
        objectMapper.readValue(duplicateResponse.body(), CreateBookHandler.ErrorResponse.class);
    assertThat(duplicateBody.message()).isEqualTo("already added on " + LocalDate.now());

    // get by id
    var getResponse =
        httpClient.send(
            request("/books/OL27448W").GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(getResponse.statusCode()).isEqualTo(200);

    var fetched = objectMapper.readValue(getResponse.body(), GetBookHandler.GetBookResponse.class);
    assertThat(fetched.book().openLibraryWorkId()).isEqualTo("OL27448W");
    assertThat(fetched.book().coverUrl()).isEqualTo(coverUrl);
    assertThat(fetched.book().createdAt()).isEqualTo(originalCreatedAt);

    // update finished_date
    var newFinishedDate = LocalDate.now().minusDays(30).toString();
    var updateBody =
        """
        {"finished_date": "%s"}
        """
            .formatted(newFinishedDate);
    var updateResponse =
        httpClient.send(
            request("/books/OL27448W")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(updateBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(updateResponse.statusCode()).isEqualTo(200);

    var updated =
        objectMapper.readValue(updateResponse.body(), UpdateBookHandler.UpdateBookResponse.class);
    assertThat(updated.book().finishedDate()).isEqualTo(newFinishedDate);
    assertThat(updated.book().createdAt()).isEqualTo(originalCreatedAt);
    assertThat(updated.book().updatedAt()).isGreaterThanOrEqualTo(originalCreatedAt);
    assertThat(updated.book().title()).isEqualTo("The Lord of the Rings");

    // get after update reflects new date and preserved created_at
    var getAfterUpdate =
        httpClient.send(
            request("/books/OL27448W").GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(getAfterUpdate.statusCode()).isEqualTo(200);
    var fetchedAfterUpdate =
        objectMapper.readValue(getAfterUpdate.body(), GetBookHandler.GetBookResponse.class);
    assertThat(fetchedAfterUpdate.book().finishedDate()).isEqualTo(newFinishedDate);
    assertThat(fetchedAfterUpdate.book().createdAt()).isEqualTo(originalCreatedAt);

    // delete
    var deleteResponse =
        httpClient.send(
            request("/books/OL27448W").DELETE().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(deleteResponse.statusCode()).isEqualTo(204);

    // verify gone via get
    var getAfterDelete =
        httpClient.send(
            request("/books/OL27448W").GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(getAfterDelete.statusCode()).isEqualTo(404);

    // verify gone via list
    var listAfterDelete =
        httpClient.send(request("/books").GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(listAfterDelete.statusCode()).isEqualTo(200);
    var listAfterDeleteBody =
        objectMapper.readValue(listAfterDelete.body(), FindBooksHandler.FindBooksResponse.class);
    assertThat(listAfterDeleteBody.books()).isEmpty();
    assertThat(listAfterDeleteBody.rollingTwelveMonthCount()).isZero();
  }
}
