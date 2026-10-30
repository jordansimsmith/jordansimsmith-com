package com.jordansimsmith.tcginventory.photos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jordansimsmith.s3.S3Container;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.photos.client.CreatePhotoRequest;
import com.jordansimsmith.tcginventory.photos.client.DeletePhotoRequest;
import com.jordansimsmith.tcginventory.photos.client.FindPhotosRequest;
import com.jordansimsmith.tcginventory.photos.client.GetPhotoBytesRequest;
import com.jordansimsmith.ulid.UlidGenerator;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Testcontainers
class PhotosServiceIntegrationTest {
  private static final String BUCKET = "physical-photos";
  private static final byte[] PHOTO_BYTES = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};

  @Container private static final S3Container s3Container = new S3Container();

  private static S3Client s3Client;
  private static S3Presigner s3Presigner;
  private static S3PhotoStore photoStore;

  @BeforeAll
  static void setUpBeforeClass() {
    var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    s3Client =
        S3Client.builder()
            .endpointOverride(s3Container.getEndpoint())
            .region(Region.US_EAST_1)
            .credentialsProvider(credentials)
            .forcePathStyle(true)
            .build();
    s3Presigner =
        S3Presigner.builder()
            .endpointOverride(s3Container.getEndpoint())
            .region(Region.US_EAST_1)
            .credentialsProvider(credentials)
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
    s3Client.createBucket(request -> request.bucket(BUCKET));
    photoStore = new S3PhotoStore(s3Client, s3Presigner, BUCKET);
  }

  @AfterAll
  static void tearDownAfterClass() {
    s3Presigner.close();
    s3Client.close();
  }

  @Test
  void createAndReadShouldPreserveBytesAndUseExistingKeyLayout() throws Exception {
    // arrange
    var ulidGenerator = mock(UlidGenerator.class);
    when(ulidGenerator.generate()).thenReturn("01JEXAMPLEPHOTOULID00000");
    var createPhotoService = new CreatePhotoService(photoStore, ulidGenerator);
    var ctx = new RequestContext("alice");

    // act
    var created = createPhotoService.createPhoto(ctx, new CreatePhotoRequest(PHOTO_BYTES));
    var stored =
        new GetPhotoBytesService(photoStore)
            .getPhotoBytes(ctx, new GetPhotoBytesRequest(created.photoId()));

    // assert
    assertThat(created.photoId()).isEqualTo("01JEXAMPLEPHOTOULID00000");
    assertThat(stored.photoBytes()).isEqualTo(PHOTO_BYTES);
    var metadata =
        s3Client.headObject(
            HeadObjectRequest.builder()
                .bucket(BUCKET)
                .key("users/alice/photos/01JEXAMPLEPHOTOULID00000.jpg")
                .build());
    assertThat(metadata.contentType()).isEqualTo("image/jpeg");
  }

  @Test
  void readsShouldBeScopedToTheAuthenticatedUser() throws Exception {
    // arrange
    photoStore.create("alice", "private", PHOTO_BYTES);

    // act / assert
    assertThatThrownBy(
            () ->
                new GetPhotoBytesService(photoStore)
                    .getPhotoBytes(new RequestContext("bob"), new GetPhotoBytesRequest("private")))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Photo was not found");
  }

  @Test
  void lookupShouldDeduplicateInOrderAndSignTheExistingObjectKey() throws Exception {
    // arrange
    var service = new FindPhotosService(photoStore);

    // act
    var response =
        service.findPhotos(
            new RequestContext("alice"),
            new FindPhotosRequest(List.of("first", "second", "first")));

    // assert
    assertThat(response.photos()).extracting("photoId").containsExactly("first", "second");
    var photoUrl = URI.create(response.photos().getFirst().url());
    assertThat(photoUrl.getRawPath()).contains("/" + BUCKET + "/users/alice/photos/first.jpg");
    assertThat(photoUrl.getRawQuery()).contains("X-Amz-Expires=900");
  }

  @Test
  void lookupShouldSignAnAbsentObjectWithoutReadingStorage() throws Exception {
    // act
    var response =
        new FindPhotosService(photoStore)
            .findPhotos(new RequestContext("alice"), new FindPhotosRequest(List.of("absent")));

    // assert
    assertThat(response.photos()).hasSize(1);
    assertThat(response.photos().getFirst().url()).contains("absent.jpg");
  }

  @Test
  void deleteShouldRemoveTheObjectAndRemainSuccessfulWhenRepeated() throws Exception {
    // arrange
    var service = new DeletePhotoService(photoStore);
    var ctx = new RequestContext("alice");
    photoStore.create("alice", "delete-me", PHOTO_BYTES);

    // act
    service.deletePhoto(ctx, new DeletePhotoRequest("delete-me"));
    service.deletePhoto(ctx, new DeletePhotoRequest("delete-me"));

    // assert
    assertThatThrownBy(
            () ->
                new GetPhotoBytesService(photoStore)
                    .getPhotoBytes(ctx, new GetPhotoBytesRequest("delete-me")))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void createShouldRejectInvalidBytesBeforeStorageAccess() {
    // arrange
    var s3Client = mock(S3Client.class);
    var s3Presigner = mock(S3Presigner.class);
    var ulidGenerator = mock(UlidGenerator.class);
    var service =
        new CreatePhotoService(new S3PhotoStore(s3Client, s3Presigner, BUCKET), ulidGenerator);

    // act / assert
    assertThatThrownBy(
            () -> service.createPhoto(new RequestContext("alice"), new CreatePhotoRequest(null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("photo_bytes is required");
    assertThatThrownBy(
            () ->
                service.createPhoto(
                    new RequestContext("alice"), new CreatePhotoRequest(new byte[0])))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("photo_bytes is required");
    assertThatThrownBy(
            () ->
                service.createPhoto(
                    new RequestContext("alice"),
                    new CreatePhotoRequest(new byte[4 * 1024 * 1024 + 1])))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("photo_bytes must not exceed 4 MiB");
  }

  @Test
  void getShouldPropagateUnexpectedStorageFailures() {
    // arrange
    var s3Client = mock(S3Client.class);
    var failure = new IllegalStateException("storage failed");
    when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(failure);
    var service =
        new GetPhotoBytesService(new S3PhotoStore(s3Client, mock(S3Presigner.class), BUCKET));

    // act / assert
    assertThatThrownBy(
            () ->
                service.getPhotoBytes(
                    new RequestContext("alice"), new GetPhotoBytesRequest("photo")))
        .isSameAs(failure);
  }
}
