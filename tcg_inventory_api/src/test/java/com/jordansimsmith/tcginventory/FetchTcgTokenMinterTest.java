package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.secrets.FakeSecrets;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

public class FetchTcgTokenMinterTest {
  @Mock HttpClient httpClient;

  private ObjectMapper objectMapper;
  private FakeSecrets fakeSecrets;
  private FetchTcgTokenMinter minter;
  private AutoCloseable openMocks;

  @BeforeEach
  void setUp() {
    openMocks = openMocks(this);
    objectMapper = new ObjectMapper();
    fakeSecrets = new FakeSecrets();
    minter =
        new FetchTcgTokenMinter(
            URI.create("https://securetoken.googleapis.com/v1/token?key=FAKE_API_KEY"),
            httpClient,
            objectMapper,
            fakeSecrets);
  }

  @AfterEach
  void tearDown() throws Exception {
    openMocks.close();
  }

  @Test
  void mintShouldReturnIdToken() throws IOException, InterruptedException {
    // arrange
    fakeSecrets.set(FetchTcgTokenMinter.SECRET_NAME, "{\"jordan\": \"refresh-token-123\"}");
    var response =
        createMockResponse(
            200,
            """
            {
              "id_token": "bearer-token-abc",
              "refresh_token": "refresh-token-123",
              "expires_in": "3600"
            }
            """);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    var bearer = minter.mint("jordan");

    // assert
    assertThat(bearer).isEqualTo("bearer-token-abc");
  }

  @Test
  void mintShouldSendRefreshTokenToFirebase() throws IOException, InterruptedException {
    // arrange
    fakeSecrets.set(FetchTcgTokenMinter.SECRET_NAME, "{\"jordan\": \"my-refresh-token\"}");
    var response =
        createMockResponse(
            200,
            """
            {"id_token": "bearer", "refresh_token": "my-refresh-token", "expires_in": "3600"}
            """);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    minter.mint("jordan");

    // assert
    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(requestCaptor.getValue().uri())
        .isEqualTo(URI.create("https://securetoken.googleapis.com/v1/token?key=FAKE_API_KEY"));
    assertThat(requestCaptor.getValue().headers().firstValue("Content-Type"))
        .contains("application/x-www-form-urlencoded");
  }

  @Test
  void mintShouldPersistRotatedRefreshToken() throws IOException, InterruptedException {
    // arrange
    fakeSecrets.set(FetchTcgTokenMinter.SECRET_NAME, "{\"jordan\": \"old-refresh-token\"}");
    var response =
        createMockResponse(
            200,
            """
            {
              "id_token": "bearer-token",
              "refresh_token": "new-refresh-token",
              "expires_in": "3600"
            }
            """);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    minter.mint("jordan");

    // assert
    var updatedSecret = fakeSecrets.get(FetchTcgTokenMinter.SECRET_NAME);
    var updatedNode = objectMapper.readTree(updatedSecret);
    assertThat(updatedNode.get("jordan").asText()).isEqualTo("new-refresh-token");
  }

  @Test
  void mintShouldNotUpdateSecretWhenRefreshTokenUnchanged()
      throws IOException, InterruptedException {
    // arrange
    var originalSecret = "{\"jordan\": \"same-refresh-token\"}";
    fakeSecrets.set(FetchTcgTokenMinter.SECRET_NAME, originalSecret);
    var response =
        createMockResponse(
            200,
            """
            {
              "id_token": "bearer",
              "refresh_token": "same-refresh-token",
              "expires_in": "3600"
            }
            """);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    minter.mint("jordan");

    // assert
    var secret = fakeSecrets.get(FetchTcgTokenMinter.SECRET_NAME);
    assertThat(secret).isEqualTo(originalSecret);
  }

  @Test
  void mintShouldPreserveOtherUsersWhenRotating() throws IOException, InterruptedException {
    // arrange
    fakeSecrets.set(
        FetchTcgTokenMinter.SECRET_NAME, "{\"jordan\": \"old-token\", \"alice\": \"alice-token\"}");
    var response =
        createMockResponse(
            200,
            """
            {"id_token": "bearer", "refresh_token": "new-token", "expires_in": "3600"}
            """);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    minter.mint("jordan");

    // assert
    var updatedNode = objectMapper.readTree(fakeSecrets.get(FetchTcgTokenMinter.SECRET_NAME));
    assertThat(updatedNode.get("jordan").asText()).isEqualTo("new-token");
    assertThat(updatedNode.get("alice").asText()).isEqualTo("alice-token");
  }

  @Test
  void mintShouldThrowWhenFirebaseReturnsError() throws IOException, InterruptedException {
    // arrange
    fakeSecrets.set(FetchTcgTokenMinter.SECRET_NAME, "{\"jordan\": \"bad-token\"}");
    var response = createMockResponse(400, "{\"error\": {\"message\": \"TOKEN_EXPIRED\"}}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act & assert
    assertThatThrownBy(() -> minter.mint("jordan"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("400");
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> createMockResponse(int statusCode, String body) {
    HttpResponse<String> mockResponse = mock(HttpResponse.class);
    when(mockResponse.statusCode()).thenReturn(statusCode);
    when(mockResponse.body()).thenReturn(body);
    return mockResponse;
  }
}
