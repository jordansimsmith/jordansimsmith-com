package com.jordansimsmith.tcginventory.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.s3.S3Container;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobMessage;
import com.jordansimsmith.tcginventory.JobsHandler;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.fetchtcg.client.CreateListingImageResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateListingRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateListingResponse;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
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
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

@Testcontainers
public class ListingPhaseProcessorIntegrationTest {
  private static final byte[] FRONT_JPEG =
      new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
  private static final byte[] BACK_JPEG =
      new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, (byte) 0x00};
  private static final String FRONT_PHOTO_ID = "photo-front";
  private static final String BACK_PHOTO_ID = "photo-back";
  private static final String UNIT_TWO_FRONT_ID = "photo-unit-2-front";
  private static final String SKU_ID = "mtg#scryfall#scryfall-1#normal#NM";

  private FakeClock fakeClock;
  private TcgInventoryTestFactory factory;
  private FakeFetchTcgService fakeFetchTcgService;
  private ObjectMapper objectMapper;
  private DynamoDbTable<SkuItem> skuTable;
  private DynamoDbTable<UnitItem> unitTable;
  private DynamoDbTable<JobItem> jobTable;
  private JobsHandler jobsHandler;
  private ListAppender<ILoggingEvent> logAppender;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();
  @Container private static final S3Container s3Container = new S3Container();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), s3Container.getEndpoint());
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.tableDefinition());
  }

  @BeforeEach
  void setUp() {
    factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), s3Container.getEndpoint());

    fakeClock = factory.fakeClock();
    fakeFetchTcgService = factory.fakeFetchTcgService();
    objectMapper = factory.objectMapper();
    skuTable = factory.skuTable();
    unitTable = factory.unitTable();
    jobTable = factory.jobTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeFetchTcgService.reset();

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
    var unit = getUnit("jordan", SKU_ID, 1);
    assertThat(unit.getPhotos())
        .extracting(UnitItem.Photo::getPhotoId)
        .containsExactly(FRONT_PHOTO_ID, BACK_PHOTO_ID);
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
    skuTable.putItem(sku);
    createPhotographedUnit(
        "jordan",
        SKU_ID,
        1,
        List.of(FRONT_PHOTO_ID, BACK_PHOTO_ID),
        List.of(FRONT_JPEG, BACK_JPEG));

    // act
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job1", "publish"), null);

    // assert
    var unit = getUnit("jordan", SKU_ID, 1);
    assertThat(unit.getPhotos())
        .extracting(UnitItem.Photo::getPhotoId)
        .containsExactly(FRONT_PHOTO_ID, BACK_PHOTO_ID);
  }

  @Test
  void uploadFailureShouldLeavePhotoIdsUnchangedAndSkipListingUpdate() throws Exception {
    // arrange
    createDirtySku("jordan", SKU_ID, "1.50");
    createPhotographedUnit("jordan", SKU_ID, 1, List.of(FRONT_PHOTO_ID), List.of(FRONT_JPEG));
    var fetchTcgService = mock(FetchTcgService.class);
    var uploadFailure = new ServiceUnavailableException("image upload unavailable");
    when(fetchTcgService.createListingImage(any(RequestContext.class), any()))
        .thenThrow(uploadFailure);
    var processor = listingPhaseProcessor(fetchTcgService);

    // act / assert
    assertThatThrownBy(() -> processor.process("jordan", 0)).isSameAs(uploadFailure);

    // assert persisted outcome
    assertThat(getUnit("jordan", SKU_ID, 1).getPhotos())
        .extracting(UnitItem.Photo::getPhotoId)
        .containsExactly(FRONT_PHOTO_ID);
    verify(fetchTcgService, never()).updateListing(any(RequestContext.class), any());
  }

  @Test
  void retryShouldUploadPhotoAgainAfterListingFailure() throws Exception {
    // arrange
    createDirtySku("jordan", SKU_ID, "1.50");
    createPhotographedUnit("jordan", SKU_ID, 1, List.of(FRONT_PHOTO_ID), List.of(FRONT_JPEG));
    var fetchTcgService = mock(FetchTcgService.class);
    var imageUrl = "https://listing-img.fetchtcg.example/persisted.jpg";
    when(fetchTcgService.createListingImage(any(RequestContext.class), any()))
        .thenReturn(new CreateListingImageResponse(imageUrl));
    when(fetchTcgService.updateListing(any(RequestContext.class), any(UpdateListingRequest.class)))
        .thenThrow(new ServiceUnavailableException("listing update unavailable"))
        .thenReturn(new UpdateListingResponse(975737, 1));
    var processor = listingPhaseProcessor(fetchTcgService);

    // act
    assertThatThrownBy(() -> processor.process("jordan", 0))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("listing update unavailable");
    processor.process("jordan", 0);

    // assert
    verify(fetchTcgService, times(2)).createListingImage(any(RequestContext.class), any());
    verify(fetchTcgService, times(2))
        .updateListing(any(RequestContext.class), any(UpdateListingRequest.class));
    assertThat(getUnit("jordan", SKU_ID, 1).getPhotos())
        .extracting(UnitItem.Photo::getPhotoId)
        .containsExactly(FRONT_PHOTO_ID);
    assertThat(getSku("jordan", SKU_ID).getDirty()).isFalse();
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
    assertThat(getSku("jordan", SKU_ID).getDirty()).isFalse();
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

    var sold = getUnit("jordan", SKU_ID, 1);
    sold.setStatus("sold");
    unitTable.putItem(sold);
    redirtySku("jordan", SKU_ID);
    createPublishJob("jordan", "job2");
    jobsHandler.handleRequest(buildSqsEvent("jordan", "job2", "publish"), null);

    // assert
    var unitOne = getUnit("jordan", SKU_ID, 1);
    assertThat(unitOne.getPhotos())
        .extracting(UnitItem.Photo::getPhotoId)
        .containsExactly(FRONT_PHOTO_ID, BACK_PHOTO_ID);
    var unitTwo = getUnit("jordan", SKU_ID, 2);
    assertThat(unitTwo.getPhotos())
        .extracting(UnitItem.Photo::getPhotoId)
        .containsExactly(UNIT_TWO_FRONT_ID);
  }

  private void createDirtySku(String user, String skuId, String suggestedPrice) {
    var parts = skuId.split("#");
    var skuItem =
        SkuItem.create(
            user,
            skuId,
            parts[0],
            parts[2],
            parts[3],
            parts[4],
            "Test Card",
            "dom",
            "Dominaria",
            "168",
            "mtg_168_c_dom_normal",
            suggestedPrice);
    skuTable.putItem(skuItem);
  }

  private void createPhotographedUnit(
      String user,
      String skuId,
      int sequenceNumber,
      List<String> photoIds,
      List<byte[]> jpegBytes) {
    var photos = new ArrayList<UnitItem.Photo>();
    for (int i = 0; i < photoIds.size(); i++) {
      var photoId = photoIds.get(i);
      photos.add(UnitItem.Photo.create(photoId));
      factory.fakePhotosService().seedPhoto(user, photoId, jpegBytes.get(i));
    }
    createUnit(user, skuId, sequenceNumber, "in_stock", photos);
  }

  private void createUnit(
      String user, String skuId, int sequenceNumber, String status, List<UnitItem.Photo> photos) {
    var unit =
        UnitItem.create(
            user,
            "mtg",
            skuId,
            sequenceNumber,
            status,
            "import1",
            Instant.ofEpochSecond(1700000000));
    if (photos != null) {
      unit.setPhotos(photos);
    }
    unitTable.putItem(unit);
  }

  private void redirtySku(String user, String skuId) {
    var sku = getSku(user, skuId);
    sku.setDirty(true);
    sku.setGsi1pk(SkuItem.formatGsi1pk(user));
    skuTable.putItem(sku);
  }

  private void createPublishJob(String user, String jobId) {
    var jobItem =
        JobItem.create(user, jobId, "publish", null, null, Instant.ofEpochSecond(1700000000));
    jobTable.putItem(jobItem);
  }

  private SkuItem getSku(String user, String skuId) {
    return skuTable.getItem(
        Key.builder()
            .partitionValue(SkuItem.formatPk(user, skuId))
            .sortValue(SkuItem.formatSk())
            .build());
  }

  private ListingPhaseProcessor listingPhaseProcessor(FetchTcgService fetchTcgService) {
    var dynamoDbClient = factory.dynamoDbClient();
    return new ListingPhaseProcessor(
        skuTable,
        new InventoryRepository(unitTable, dynamoDbClient, fakeClock, factory.ulidGenerator()),
        dynamoDbClient,
        fakeClock,
        fetchTcgService,
        factory.photosService());
  }

  private UnitItem getUnit(String user, String skuId, int sequenceNumber) {
    return unitTable.getItem(
        Key.builder()
            .partitionValue(SkuItem.formatPk(user, skuId))
            .sortValue(UnitItem.formatSk(sequenceNumber))
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
