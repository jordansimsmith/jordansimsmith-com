package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.s3.S3Container;
import com.jordansimsmith.time.FakeClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Testcontainers
public class ListingPhaseProcessorIntegrationTest {
  private static final byte[] FRONT_JPEG =
      new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
  private static final byte[] BACK_JPEG =
      new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, (byte) 0x00};
  private static final String FRONT_PHOTO_ID = "photo-front";
  private static final String BACK_PHOTO_ID = "photo-back";
  private static final String UNIT_TWO_FRONT_ID = "photo-unit-2-front";
  private static final String SKU_ID = "scryfall-1#normal#NM";

  private FakeClock fakeClock;
  private FakeFetchTcgClient fakeFetchTcgClient;
  private ObjectMapper objectMapper;
  private DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private S3Client s3Client;
  private JobsHandler jobsHandler;
  private ListAppender<ILoggingEvent> logAppender;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();
  @Container private static final S3Container s3Container = new S3Container();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), s3Container.getEndpoint());
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.tcgInventoryTable());
    factory.s3Client().createBucket(b -> b.bucket(Photos.BUCKET));
  }

  @BeforeEach
  void setUp() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), s3Container.getEndpoint());

    fakeClock = factory.fakeClock();
    fakeFetchTcgClient = factory.fakeFetchTcgClient();
    objectMapper = factory.objectMapper();
    tcgInventoryTable = factory.tcgInventoryTable();
    s3Client = factory.s3Client();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeFetchTcgClient.reset();

    jobsHandler = new JobsHandler(factory);

    logAppender = new ListAppender<>();
    logAppender.start();
    listingLogger().addAppender(logAppender);

    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
  }

  @AfterEach
  void tearDown() {
    var logger = listingLogger();
    logger.detachAppender(logAppender);
    logAppender.stop();
  }

  @Test
  void publishPhaseShouldCreateListingWithFirstUnitPhotos() {
    // arrange
    createPublishJob("jordan", "job1");
    createDirtySku("jordan", SKU_ID, "1.50");
    createPhotographedUnit(
        "jordan",
        SKU_ID,
        1,
        List.of(FRONT_PHOTO_ID, BACK_PHOTO_ID),
        List.of(FRONT_JPEG, BACK_JPEG));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    assertThat(fakeFetchTcgClient.getUploadCalls()).hasSize(2);
    assertThat(fakeFetchTcgClient.getUploadCalls().get(0).bytes()).isEqualTo(FRONT_JPEG);
    assertThat(fakeFetchTcgClient.getUploadCalls().get(0).filename())
        .isEqualTo(FRONT_PHOTO_ID + ".jpg");
    assertThat(fakeFetchTcgClient.getUploadCalls().get(1).bytes()).isEqualTo(BACK_JPEG);
    assertThat(fakeFetchTcgClient.getUploadCalls().get(1).filename())
        .isEqualTo(BACK_PHOTO_ID + ".jpg");

    var frontUrl = "https://listing-img.fetchtcg.com/fake/listing/1.jpg";
    var backUrl = "https://listing-img.fetchtcg.com/fake/listing/2.jpg";
    assertThat(fakeFetchTcgClient.getUpsertCalls()).hasSize(1);
    var upsert = fakeFetchTcgClient.getUpsertCalls().get(0);
    assertThat(upsert.frontImage()).isEqualTo(frontUrl);
    assertThat(upsert.additionalImages()).containsExactly(backUrl);

    var unit = getUnit("jordan", SKU_ID, 1);
    assertThat(unit.getPhotos().get(0).getFetchtcgUrl()).isEqualTo(frontUrl);
    assertThat(unit.getPhotos().get(1).getFetchtcgUrl()).isEqualTo(backUrl);
  }

  @Test
  void publishPhaseShouldUpdateListingWithFirstUnitPhotos() {
    // arrange
    createPublishJob("jordan", "job1");
    createDirtySku("jordan", SKU_ID, "2.00");
    var sku = getSku("jordan", SKU_ID);
    sku.setFetchtcgListingId(975737);
    sku.setLastPublishedQuantity(1);
    sku.setLastPublishedPrice("1.80");
    tcgInventoryTable.putItem(sku);
    createPhotographedUnit(
        "jordan",
        SKU_ID,
        1,
        List.of(FRONT_PHOTO_ID, BACK_PHOTO_ID),
        List.of(FRONT_JPEG, BACK_JPEG));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    assertThat(fakeFetchTcgClient.getUploadCalls()).hasSize(2);
    var upsert = fakeFetchTcgClient.getUpsertCalls().get(0);
    assertThat(upsert.frontImage())
        .isEqualTo("https://listing-img.fetchtcg.com/fake/listing/1.jpg");
    assertThat(upsert.additionalImages())
        .containsExactly("https://listing-img.fetchtcg.com/fake/listing/2.jpg");
    assertThat(upsert.quantity()).isEqualTo(1);
  }

  @Test
  void publishPhaseShouldReusePersistedFetchtcgUrlsOnSecondPublish() {
    // arrange
    createPublishJob("jordan", "job1");
    createDirtySku("jordan", SKU_ID, "1.50");
    createPhotographedUnit(
        "jordan",
        SKU_ID,
        1,
        List.of(FRONT_PHOTO_ID, BACK_PHOTO_ID),
        List.of(FRONT_JPEG, BACK_JPEG));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);
    redirtySku("jordan", SKU_ID);
    createPublishJob("jordan", "job2");
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job2", "publish"), null);

    // assert
    assertThat(fakeFetchTcgClient.getUploadCalls()).hasSize(2);
    assertThat(fakeFetchTcgClient.getUpsertCalls()).hasSize(2);
    var firstUrls = imageUrls(fakeFetchTcgClient.getUpsertCalls().get(0));
    var secondUrls = imageUrls(fakeFetchTcgClient.getUpsertCalls().get(1));
    assertThat(secondUrls).isEqualTo(firstUrls);

    var unit = getUnit("jordan", SKU_ID, 1);
    assertThat(unit.getPhotos().get(0).getFetchtcgUrl()).isEqualTo(firstUrls.get(0));
    assertThat(unit.getPhotos().get(1).getFetchtcgUrl()).isEqualTo(firstUrls.get(1));
  }

  @Test
  void publishPhaseShouldSendEmptyImageStateWhenFirstUnitHasNoPhotos() {
    // arrange
    createPublishJob("jordan", "job1");
    createDirtySku("jordan", SKU_ID, "1.50");
    createUnit("jordan", SKU_ID, 1, "in_stock", null);

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    assertThat(fakeFetchTcgClient.getUploadCalls()).isEmpty();
    assertThat(fakeFetchTcgClient.getUpsertCalls()).hasSize(1);
    var upsert = fakeFetchTcgClient.getUpsertCalls().get(0);
    assertThat(upsert.frontImage()).isNull();
    assertThat(upsert.additionalImages()).isEmpty();
  }

  @Test
  void publishPhaseShouldWarnWhenPhotoLessAtOrAboveFifty() {
    // arrange
    createPublishJob("jordan", "job1");
    createDirtySku("jordan", SKU_ID, "50.00");
    createUnit("jordan", SKU_ID, 1, "in_stock", null);

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    assertThat(fakeFetchTcgClient.getUpsertCalls()).hasSize(1);
    assertThat(warnMessages())
        .anySatisfy(message -> assertThat(message).contains(SKU_ID).contains("50"));
  }

  @Test
  void publishPhaseShouldWarnWhenPhotoLessAboveFifty() {
    // arrange
    createPublishJob("jordan", "job1");
    createDirtySku("jordan", SKU_ID, "50.01");
    createUnit("jordan", SKU_ID, 1, "in_stock", null);

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    assertThat(fakeFetchTcgClient.getUpsertCalls()).hasSize(1);
    assertThat(warnMessages())
        .anySatisfy(message -> assertThat(message).contains(SKU_ID).contains("50.01"));
  }

  @Test
  void publishPhaseShouldNotWarnWhenPhotoLessBelowFifty() {
    // arrange
    createPublishJob("jordan", "job1");
    createDirtySku("jordan", SKU_ID, "49.99");
    createUnit("jordan", SKU_ID, 1, "in_stock", null);

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    assertThat(fakeFetchTcgClient.getUpsertCalls()).hasSize(1);
    assertThat(warnMessages()).isEmpty();
  }

  @Test
  void publishPhaseShouldNotWarnWhenPhotographedAtOrAboveFifty() {
    // arrange
    createPublishJob("jordan", "job1");
    createDirtySku("jordan", SKU_ID, "60.00");
    createPhotographedUnit("jordan", SKU_ID, 1, List.of(FRONT_PHOTO_ID), List.of(FRONT_JPEG));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    assertThat(fakeFetchTcgClient.getUpsertCalls()).hasSize(1);
    assertThat(warnMessages()).isEmpty();
  }

  @Test
  void publishPhaseShouldSwapListingImagesAfterFirstUnitSells() {
    // arrange
    createPublishJob("jordan", "job1");
    createDirtySku("jordan", SKU_ID, "1.50");
    createPhotographedUnit(
        "jordan",
        SKU_ID,
        1,
        List.of(FRONT_PHOTO_ID, BACK_PHOTO_ID),
        List.of(FRONT_JPEG, BACK_JPEG));
    createPhotographedUnit("jordan", SKU_ID, 2, List.of(UNIT_TWO_FRONT_ID), List.of(FRONT_JPEG));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    var firstUpsert = fakeFetchTcgClient.getUpsertCalls().get(0);
    assertThat(firstUpsert.frontImage())
        .isEqualTo("https://listing-img.fetchtcg.com/fake/listing/1.jpg");
    assertThat(firstUpsert.additionalImages())
        .containsExactly("https://listing-img.fetchtcg.com/fake/listing/2.jpg");

    var sold = getUnit("jordan", SKU_ID, 1);
    sold.setStatus("sold");
    tcgInventoryTable.putItem(sold);
    redirtySku("jordan", SKU_ID);
    createPublishJob("jordan", "job2");
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job2", "publish"), null);

    // assert
    assertThat(fakeFetchTcgClient.getUploadCalls()).hasSize(3);
    assertThat(fakeFetchTcgClient.getUpsertCalls()).hasSize(2);
    var secondUpsert = fakeFetchTcgClient.getUpsertCalls().get(1);
    assertThat(secondUpsert.frontImage())
        .isEqualTo("https://listing-img.fetchtcg.com/fake/listing/3.jpg");
    assertThat(secondUpsert.additionalImages()).isEmpty();

    var unitOne = getUnit("jordan", SKU_ID, 1);
    assertThat(unitOne.getPhotos().get(0).getFetchtcgUrl())
        .isEqualTo("https://listing-img.fetchtcg.com/fake/listing/1.jpg");
    var unitTwo = getUnit("jordan", SKU_ID, 2);
    assertThat(unitTwo.getPhotos().get(0).getFetchtcgUrl())
        .isEqualTo("https://listing-img.fetchtcg.com/fake/listing/3.jpg");
  }

  private void createDirtySku(String user, String skuId, String suggestedPrice) {
    var parts = skuId.split("#");
    var skuItem =
        TcgInventoryItem.createSku(
            user,
            skuId,
            parts[0],
            parts[1],
            parts[2],
            "Test Card",
            "dom",
            "Dominaria",
            "168",
            "mtg_168_c_dom_normal",
            suggestedPrice);
    tcgInventoryTable.putItem(skuItem);
  }

  private void createPhotographedUnit(
      String user,
      String skuId,
      int sequenceNumber,
      List<String> photoIds,
      List<byte[]> jpegBytes) {
    var photos = new ArrayList<TcgInventoryItem.Photo>();
    for (int i = 0; i < photoIds.size(); i++) {
      var photoId = photoIds.get(i);
      photos.add(TcgInventoryItem.Photo.create(photoId, null));
      s3Client.putObject(
          PutObjectRequest.builder()
              .bucket(Photos.BUCKET)
              .key(Photos.key(user, photoId))
              .contentType("image/jpeg")
              .build(),
          RequestBody.fromBytes(jpegBytes.get(i)));
    }
    createUnit(user, skuId, sequenceNumber, "in_stock", photos);
  }

  private void createUnit(
      String user,
      String skuId,
      int sequenceNumber,
      String status,
      List<TcgInventoryItem.Photo> photos) {
    var unit =
        TcgInventoryItem.createUnit(
            user, skuId, sequenceNumber, status, "import1", Instant.ofEpochSecond(1700000000));
    if (photos != null) {
      unit.setPhotos(photos);
    }
    tcgInventoryTable.putItem(unit);
  }

  private void redirtySku(String user, String skuId) {
    var sku = getSku(user, skuId);
    sku.setDirty(true);
    sku.setGsi1pk(TcgInventoryItem.formatGsi1pk(user));
    tcgInventoryTable.putItem(sku);
  }

  private void createPublishJob(String user, String jobId) {
    var jobItem =
        TcgInventoryItem.createJob(user, jobId, "publish", null, Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(jobItem);
  }

  private TcgInventoryItem getSku(String user, String skuId) {
    return tcgInventoryTable.getItem(
        Key.builder()
            .partitionValue(TcgInventoryItem.formatSkuPk(user, skuId))
            .sortValue(TcgInventoryItem.formatSkuSk())
            .build());
  }

  private TcgInventoryItem getUnit(String user, String skuId, int sequenceNumber) {
    return tcgInventoryTable.getItem(
        Key.builder()
            .partitionValue(TcgInventoryItem.formatSkuPk(user, skuId))
            .sortValue(TcgInventoryItem.formatUnitSk(sequenceNumber))
            .build());
  }

  private SQSEvent buildSqsEvent(String user, String jobId, String jobType) {
    try {
      var message = new JobMessage(user, jobId, jobType);
      var body = objectMapper.writeValueAsString(message);
      var sqsMessage = new SQSEvent.SQSMessage();
      sqsMessage.setBody(body);
      var event = new SQSEvent();
      event.setRecords(List.of(sqsMessage));
      return event;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static List<String> imageUrls(FetchTcgClient.UpsertListingRequest upsert) {
    var urls = new ArrayList<String>();
    urls.add(upsert.frontImage());
    urls.addAll(upsert.additionalImages());
    return urls;
  }

  private List<String> warnMessages() {
    return logAppender.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private static Logger listingLogger() {
    return (Logger) LoggerFactory.getLogger(ListingPhaseProcessor.class);
  }
}
