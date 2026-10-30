package com.jordansimsmith.tcginventory.photos.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.photos.client.CreatePhotoRequest;
import com.jordansimsmith.tcginventory.photos.client.FindPhotosRequest;
import com.jordansimsmith.tcginventory.photos.client.FindPhotosResponse;
import com.jordansimsmith.tcginventory.photos.client.GetPhotoBytesResponse;
import com.jordansimsmith.tcginventory.photos.client.Photo;
import com.jordansimsmith.tcginventory.photos.client.PhotosService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FindPhotosHandlerTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final PhotosService photosService = mock(PhotosService.class);
  private FindPhotosHandler handler;

  @BeforeEach
  void setUp() {
    var factory = mock(TcgInventoryFactory.class);
    when(factory.photosService()).thenReturn(photosService);
    when(factory.requestContextFactory()).thenReturn(new RequestContextFactory());
    when(factory.httpResponseFactory())
        .thenReturn(
            new HttpResponseFactory.Builder(objectMapper)
                .withAllowedOrigin("https://tcg-inventory.jordansimsmith.com")
                .build());
    handler = new FindPhotosHandler(factory);
  }

  @Test
  void handleRequestShouldPassRepeatedIdsAndReturnServiceResponse() throws Exception {
    // arrange
    var event =
        objectMapper.readValue(
            """
            {
              "headers": {"Authorization": "%s"},
              "multiValueQueryStringParameters": {
                "photo_ids": ["photo one", "photo/two", "photo one"]
              }
            }
            """
                .formatted(AUTH_HEADER),
            FindPhotosHandler.HttpEvent.class);
    var result = new FindPhotosResponse(List.of(new Photo("photo one", "https://photos.test/one")));
    when(photosService.findPhotos(any(), any())).thenReturn(result);

    // act
    var response = handler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders().get("Content-Type"))
        .isEqualTo("application/json; charset=utf-8");
    assertThat(response.getHeaders().get("Access-Control-Allow-Origin"))
        .isEqualTo("https://tcg-inventory.jordansimsmith.com");
    assertThat(objectMapper.readTree(response.getBody()))
        .isEqualTo(
            objectMapper.readTree(
                """
                {"photos":[{"photo_id":"photo one","url":"https://photos.test/one"}]}
                """));
    verify(photosService)
        .findPhotos(
            eq(new RequestContext("jordan")),
            eq(new FindPhotosRequest(List.of("photo one", "photo/two", "photo one"))));
  }

  @Test
  void handleRequestShouldMapCheckedServiceErrors() throws Exception {
    // arrange
    var event =
        objectMapper.readValue(
            """
            {
              "headers": {"Authorization": "%s"},
              "multiValueQueryStringParameters": {"photo_ids": [""]}
            }
            """
                .formatted(AUTH_HEADER),
            FindPhotosHandler.HttpEvent.class);
    when(photosService.findPhotos(any(), any()))
        .thenThrow(new BadRequestException("photo_ids must not contain blank values"));

    // act
    var response = handler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("photo_ids must not contain blank values");
  }

  @Test
  void handleRequestShouldPropagateUnexpectedFailures() throws Exception {
    // arrange
    var event =
        objectMapper.readValue(
            """
            {
              "headers": {"Authorization": "%s"},
              "multiValueQueryStringParameters": {"photo_ids": ["id"]}
            }
            """
                .formatted(AUTH_HEADER),
            FindPhotosHandler.HttpEvent.class);
    when(photosService.findPhotos(any(), any())).thenThrow(new IllegalStateException("broken"));

    // act / assert
    assertThatThrownBy(() -> handler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void photoByteContractsShouldRoundTripAsBase64AndSnakeCase() throws Exception {
    // arrange
    var photoBytes = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff};

    // act
    var requestJson = objectMapper.writeValueAsString(new CreatePhotoRequest(photoBytes));
    var responseJson = objectMapper.writeValueAsString(new GetPhotoBytesResponse(photoBytes));

    // assert
    assertThat(objectMapper.readTree(requestJson).fieldNames())
        .toIterable()
        .containsExactly("photo_bytes");
    assertThat(objectMapper.readTree(responseJson).fieldNames())
        .toIterable()
        .containsExactly("photo_bytes");
    assertThat(objectMapper.readValue(requestJson, CreatePhotoRequest.class).photoBytes())
        .isEqualTo(photoBytes);
    assertThat(objectMapper.readValue(responseJson, GetPhotoBytesResponse.class).photoBytes())
        .isEqualTo(photoBytes);
  }
}
