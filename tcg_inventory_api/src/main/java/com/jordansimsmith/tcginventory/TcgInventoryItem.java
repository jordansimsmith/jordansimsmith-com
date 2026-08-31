package com.jordansimsmith.tcginventory;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class TcgInventoryItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String SKU_PREFIX = "SKU" + DELIMITER;
  public static final String UNIT_PREFIX = "UNIT" + DELIMITER;
  public static final String IMPORT_PREFIX = "IMPORT" + DELIMITER;
  public static final String ROW_PREFIX = "ROW" + DELIMITER;
  public static final String ORDER_PREFIX = "ORDER" + DELIMITER;
  public static final String JOB_PREFIX = "JOB" + DELIMITER;
  public static final String COUNTER_PREFIX = "COUNTER" + DELIMITER;
  public static final String DIRTY_SUFFIX = "DIRTY";
  public static final String SKUS_SUFFIX = "SKUS";
  public static final String UNITS_SUFFIX = "UNITS";
  public static final String NAME_PREFIX = "NAME" + DELIMITER;
  public static final String AUDIT_SUFFIX = "AUDIT";

  public static final String TABLE_NAME = "tcg_inventory";
  public static final String GSI1_NAME = "gsi1";
  public static final String GSI2_NAME = "gsi2";
  public static final String GSI3_NAME = "gsi3";

  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String GSI1PK = "gsi1pk";
  public static final String GSI1SK = "gsi1sk";
  public static final String GSI2PK = "gsi2pk";
  public static final String GSI2SK = "gsi2sk";
  public static final String GSI3PK = "gsi3pk";
  public static final String SKU_ID = "sku_id";
  public static final String SCRYFALL_ID = "scryfall_id";
  public static final String FINISH = "finish";
  public static final String CONDITION = "condition";
  public static final String NAME = "name";
  public static final String SET_CODE = "set_code";
  public static final String SET_NAME = "set_name";
  public static final String COLLECTOR_NUMBER = "collector_number";
  public static final String FETCHTCG_CARD_ID = "fetchtcg_card_id";
  public static final String FETCHTCG_SET_ID = "fetchtcg_set_id";
  public static final String VERSION = "version";
  public static final String DIRTY = "dirty";
  public static final String SEQUENCE_NUMBER = "sequence_number";
  public static final String STATUS = "status";
  public static final String IMPORT_ID = "import_id";
  public static final String ORDER_ID = "order_id";
  public static final String FILENAME = "filename";
  public static final String NEXT_SEQUENCE_NUMBER = "next_sequence_number";
  public static final String POSITION = "position";
  public static final String DECISION = "decision";
  public static final String DECISION_REASON = "decision_reason";
  public static final String MARKET_PRICE = "market_price";
  public static final String SUGGESTED_PRICE = "suggested_price";
  public static final String ROW_COUNT = "row_count";
  public static final String ERROR = "error";
  public static final String LANGUAGE = "language";
  public static final String JOB_TYPE = "job_type";
  public static final String JOB_ID = "job_id";
  public static final String CONTINUATION = "continuation";
  public static final String PROCESSED_COUNT = "processed_count";
  public static final String FETCHTCG_LISTING_ID = "fetchtcg_listing_id";
  public static final String LAST_PUBLISHED_QUANTITY = "last_published_quantity";
  public static final String LAST_PUBLISHED_PRICE = "last_published_price";
  public static final String LAST_PUBLISHED_AT = "last_published_at";
  public static final String DELIVERY_MODE = "delivery_mode";
  public static final String TOTAL_PRICE = "total_price";
  public static final String LINES = "lines";
  public static final String FETCHTCG_STATUS = "fetchtcg_status";
  public static final String FETCHTCG_CURRENT_ACTION = "fetchtcg_current_action";
  public static final String EVENT_TYPE = "event_type";
  public static final String TRACK_ORDERS_AFTER = "track_orders_after";
  public static final String REPORT = "report";
  public static final String AS_OF_AUDIT_ULID = "as_of_audit_ulid";
  public static final String CREATED_AT = "created_at";
  public static final String UPDATED_AT = "updated_at";
  public static final String PHOTOS = "photos";
  public static final String PHOTO_ID = "photo_id";
  public static final String FETCHTCG_URL = "fetchtcg_url";

  private String pk;
  private String sk;
  private String gsi1pk;
  private String gsi1sk;
  private String gsi2pk;
  private String gsi2sk;
  private String gsi3pk;
  private String skuId;
  private String scryfallId;
  private String finish;
  private String condition;
  private String name;
  private String setCode;
  private String setName;
  private String collectorNumber;
  private String fetchtcgCardId;
  private Integer fetchtcgSetId;
  private Integer version;
  private Boolean dirty;
  private Integer sequenceNumber;
  private String status;
  private String importId;
  private String orderId;
  private String filename;
  private Integer nextSequenceNumber;
  private Integer position;
  private String decision;
  private String decisionReason;
  private String marketPrice;
  private String suggestedPrice;
  private Integer rowCount;
  private String error;
  private String language;
  private String jobType;
  private String jobId;
  private Integer continuation;
  private Integer processedCount;
  private Integer fetchtcgListingId;
  private Integer lastPublishedQuantity;
  private String lastPublishedPrice;
  private Instant lastPublishedAt;
  private String deliveryMode;
  private String totalPrice;
  private String lines;
  private String fetchtcgStatus;
  private String fetchtcgCurrentAction;
  private String eventType;
  private Instant trackOrdersAfter;
  private String report;
  private String asOfAuditUlid;
  private Instant createdAt;
  private Instant updatedAt;
  private List<Photo> photos;

  @DynamoDbPartitionKey
  @DynamoDbAttribute(PK)
  public String getPk() {
    return pk;
  }

  public void setPk(String pk) {
    this.pk = pk;
  }

  @DynamoDbSortKey
  @DynamoDbAttribute(SK)
  public String getSk() {
    return sk;
  }

  public void setSk(String sk) {
    this.sk = sk;
  }

  @Nullable
  @DynamoDbSecondaryPartitionKey(indexNames = GSI1_NAME)
  @DynamoDbAttribute(GSI1PK)
  public String getGsi1pk() {
    return gsi1pk;
  }

  public void setGsi1pk(@Nullable String gsi1pk) {
    this.gsi1pk = gsi1pk;
  }

  @Nullable
  @DynamoDbSecondarySortKey(indexNames = GSI1_NAME)
  @DynamoDbAttribute(GSI1SK)
  public String getGsi1sk() {
    return gsi1sk;
  }

  public void setGsi1sk(@Nullable String gsi1sk) {
    this.gsi1sk = gsi1sk;
  }

  @Nullable
  @DynamoDbSecondaryPartitionKey(indexNames = GSI2_NAME)
  @DynamoDbAttribute(GSI2PK)
  public String getGsi2pk() {
    return gsi2pk;
  }

  public void setGsi2pk(@Nullable String gsi2pk) {
    this.gsi2pk = gsi2pk;
  }

  @Nullable
  @DynamoDbSecondarySortKey(indexNames = GSI2_NAME)
  @DynamoDbAttribute(GSI2SK)
  public String getGsi2sk() {
    return gsi2sk;
  }

  public void setGsi2sk(@Nullable String gsi2sk) {
    this.gsi2sk = gsi2sk;
  }

  @Nullable
  @DynamoDbSecondaryPartitionKey(indexNames = GSI3_NAME)
  @DynamoDbAttribute(GSI3PK)
  public String getGsi3pk() {
    return gsi3pk;
  }

  public void setGsi3pk(@Nullable String gsi3pk) {
    this.gsi3pk = gsi3pk;
  }

  @Nullable
  @DynamoDbAttribute(SKU_ID)
  public String getSkuId() {
    return skuId;
  }

  public void setSkuId(@Nullable String skuId) {
    this.skuId = skuId;
  }

  @Nullable
  @DynamoDbAttribute(SCRYFALL_ID)
  public String getScryfallId() {
    return scryfallId;
  }

  public void setScryfallId(@Nullable String scryfallId) {
    this.scryfallId = scryfallId;
  }

  @Nullable
  @DynamoDbAttribute(FINISH)
  public String getFinish() {
    return finish;
  }

  public void setFinish(@Nullable String finish) {
    this.finish = finish;
  }

  @Nullable
  @DynamoDbAttribute(CONDITION)
  public String getCondition() {
    return condition;
  }

  public void setCondition(@Nullable String condition) {
    this.condition = condition;
  }

  @Nullable
  @DynamoDbAttribute(NAME)
  public String getName() {
    return name;
  }

  public void setName(@Nullable String name) {
    this.name = name;
  }

  @Nullable
  @DynamoDbAttribute(SET_CODE)
  public String getSetCode() {
    return setCode;
  }

  public void setSetCode(@Nullable String setCode) {
    this.setCode = setCode;
  }

  @Nullable
  @DynamoDbAttribute(SET_NAME)
  public String getSetName() {
    return setName;
  }

  public void setSetName(@Nullable String setName) {
    this.setName = setName;
  }

  @Nullable
  @DynamoDbAttribute(COLLECTOR_NUMBER)
  public String getCollectorNumber() {
    return collectorNumber;
  }

  public void setCollectorNumber(@Nullable String collectorNumber) {
    this.collectorNumber = collectorNumber;
  }

  @Nullable
  @DynamoDbAttribute(FETCHTCG_CARD_ID)
  public String getFetchtcgCardId() {
    return fetchtcgCardId;
  }

  public void setFetchtcgCardId(@Nullable String fetchtcgCardId) {
    this.fetchtcgCardId = fetchtcgCardId;
  }

  @Nullable
  @DynamoDbAttribute(FETCHTCG_SET_ID)
  public Integer getFetchtcgSetId() {
    return fetchtcgSetId;
  }

  public void setFetchtcgSetId(@Nullable Integer fetchtcgSetId) {
    this.fetchtcgSetId = fetchtcgSetId;
  }

  @Nullable
  @DynamoDbAttribute(VERSION)
  public Integer getVersion() {
    return version;
  }

  public void setVersion(@Nullable Integer version) {
    this.version = version;
  }

  @Nullable
  @DynamoDbAttribute(DIRTY)
  public Boolean getDirty() {
    return dirty;
  }

  public void setDirty(@Nullable Boolean dirty) {
    this.dirty = dirty;
  }

  @Nullable
  @DynamoDbSecondarySortKey(indexNames = GSI3_NAME)
  @DynamoDbAttribute(SEQUENCE_NUMBER)
  public Integer getSequenceNumber() {
    return sequenceNumber;
  }

  public void setSequenceNumber(@Nullable Integer sequenceNumber) {
    this.sequenceNumber = sequenceNumber;
  }

  @Nullable
  @DynamoDbAttribute(STATUS)
  public String getStatus() {
    return status;
  }

  public void setStatus(@Nullable String status) {
    this.status = status;
  }

  @Nullable
  @DynamoDbAttribute(IMPORT_ID)
  public String getImportId() {
    return importId;
  }

  public void setImportId(@Nullable String importId) {
    this.importId = importId;
  }

  @Nullable
  @DynamoDbAttribute(ORDER_ID)
  public String getOrderId() {
    return orderId;
  }

  public void setOrderId(@Nullable String orderId) {
    this.orderId = orderId;
  }

  @Nullable
  @DynamoDbAttribute(FILENAME)
  public String getFilename() {
    return filename;
  }

  public void setFilename(@Nullable String filename) {
    this.filename = filename;
  }

  @Nullable
  @DynamoDbAttribute(NEXT_SEQUENCE_NUMBER)
  public Integer getNextSequenceNumber() {
    return nextSequenceNumber;
  }

  public void setNextSequenceNumber(@Nullable Integer nextSequenceNumber) {
    this.nextSequenceNumber = nextSequenceNumber;
  }

  @Nullable
  @DynamoDbAttribute(POSITION)
  public Integer getPosition() {
    return position;
  }

  public void setPosition(@Nullable Integer position) {
    this.position = position;
  }

  @Nullable
  @DynamoDbAttribute(DECISION)
  public String getDecision() {
    return decision;
  }

  public void setDecision(@Nullable String decision) {
    this.decision = decision;
  }

  @Nullable
  @DynamoDbAttribute(DECISION_REASON)
  public String getDecisionReason() {
    return decisionReason;
  }

  public void setDecisionReason(@Nullable String decisionReason) {
    this.decisionReason = decisionReason;
  }

  @Nullable
  @DynamoDbAttribute(MARKET_PRICE)
  public String getMarketPrice() {
    return marketPrice;
  }

  public void setMarketPrice(@Nullable String marketPrice) {
    this.marketPrice = marketPrice;
  }

  @Nullable
  @DynamoDbAttribute(SUGGESTED_PRICE)
  public String getSuggestedPrice() {
    return suggestedPrice;
  }

  public void setSuggestedPrice(@Nullable String suggestedPrice) {
    this.suggestedPrice = suggestedPrice;
  }

  @Nullable
  @DynamoDbAttribute(ROW_COUNT)
  public Integer getRowCount() {
    return rowCount;
  }

  public void setRowCount(@Nullable Integer rowCount) {
    this.rowCount = rowCount;
  }

  @Nullable
  @DynamoDbAttribute(ERROR)
  public String getError() {
    return error;
  }

  public void setError(@Nullable String error) {
    this.error = error;
  }

  @Nullable
  @DynamoDbAttribute(LANGUAGE)
  public String getLanguage() {
    return language;
  }

  public void setLanguage(@Nullable String language) {
    this.language = language;
  }

  @Nullable
  @DynamoDbAttribute(JOB_TYPE)
  public String getJobType() {
    return jobType;
  }

  public void setJobType(@Nullable String jobType) {
    this.jobType = jobType;
  }

  @Nullable
  @DynamoDbAttribute(JOB_ID)
  public String getJobId() {
    return jobId;
  }

  public void setJobId(@Nullable String jobId) {
    this.jobId = jobId;
  }

  @Nullable
  @DynamoDbAttribute(CONTINUATION)
  public Integer getContinuation() {
    return continuation;
  }

  public void setContinuation(@Nullable Integer continuation) {
    this.continuation = continuation;
  }

  @Nullable
  @DynamoDbAttribute(PROCESSED_COUNT)
  public Integer getProcessedCount() {
    return processedCount;
  }

  public void setProcessedCount(@Nullable Integer processedCount) {
    this.processedCount = processedCount;
  }

  @Nullable
  @DynamoDbAttribute(FETCHTCG_LISTING_ID)
  public Integer getFetchtcgListingId() {
    return fetchtcgListingId;
  }

  public void setFetchtcgListingId(@Nullable Integer fetchtcgListingId) {
    this.fetchtcgListingId = fetchtcgListingId;
  }

  @Nullable
  @DynamoDbAttribute(LAST_PUBLISHED_QUANTITY)
  public Integer getLastPublishedQuantity() {
    return lastPublishedQuantity;
  }

  public void setLastPublishedQuantity(@Nullable Integer lastPublishedQuantity) {
    this.lastPublishedQuantity = lastPublishedQuantity;
  }

  @Nullable
  @DynamoDbAttribute(LAST_PUBLISHED_PRICE)
  public String getLastPublishedPrice() {
    return lastPublishedPrice;
  }

  public void setLastPublishedPrice(@Nullable String lastPublishedPrice) {
    this.lastPublishedPrice = lastPublishedPrice;
  }

  @Nullable
  @DynamoDbAttribute(LAST_PUBLISHED_AT)
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  public Instant getLastPublishedAt() {
    return lastPublishedAt;
  }

  public void setLastPublishedAt(@Nullable Instant lastPublishedAt) {
    this.lastPublishedAt = lastPublishedAt;
  }

  @Nullable
  @DynamoDbAttribute(DELIVERY_MODE)
  public String getDeliveryMode() {
    return deliveryMode;
  }

  public void setDeliveryMode(@Nullable String deliveryMode) {
    this.deliveryMode = deliveryMode;
  }

  @Nullable
  @DynamoDbAttribute(TOTAL_PRICE)
  public String getTotalPrice() {
    return totalPrice;
  }

  public void setTotalPrice(@Nullable String totalPrice) {
    this.totalPrice = totalPrice;
  }

  @Nullable
  @DynamoDbAttribute(LINES)
  public String getLines() {
    return lines;
  }

  public void setLines(@Nullable String lines) {
    this.lines = lines;
  }

  @Nullable
  @DynamoDbAttribute(FETCHTCG_STATUS)
  public String getFetchtcgStatus() {
    return fetchtcgStatus;
  }

  public void setFetchtcgStatus(@Nullable String fetchtcgStatus) {
    this.fetchtcgStatus = fetchtcgStatus;
  }

  @Nullable
  @DynamoDbAttribute(FETCHTCG_CURRENT_ACTION)
  public String getFetchtcgCurrentAction() {
    return fetchtcgCurrentAction;
  }

  public void setFetchtcgCurrentAction(@Nullable String fetchtcgCurrentAction) {
    this.fetchtcgCurrentAction = fetchtcgCurrentAction;
  }

  @Nullable
  @DynamoDbAttribute(EVENT_TYPE)
  public String getEventType() {
    return eventType;
  }

  public void setEventType(@Nullable String eventType) {
    this.eventType = eventType;
  }

  @Nullable
  @DynamoDbAttribute(TRACK_ORDERS_AFTER)
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  public Instant getTrackOrdersAfter() {
    return trackOrdersAfter;
  }

  public void setTrackOrdersAfter(@Nullable Instant trackOrdersAfter) {
    this.trackOrdersAfter = trackOrdersAfter;
  }

  @Nullable
  @DynamoDbAttribute(REPORT)
  public String getReport() {
    return report;
  }

  public void setReport(@Nullable String report) {
    this.report = report;
  }

  @Nullable
  @DynamoDbAttribute(AS_OF_AUDIT_ULID)
  public String getAsOfAuditUlid() {
    return asOfAuditUlid;
  }

  public void setAsOfAuditUlid(@Nullable String asOfAuditUlid) {
    this.asOfAuditUlid = asOfAuditUlid;
  }

  @Nullable
  @DynamoDbAttribute(CREATED_AT)
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(@Nullable Instant createdAt) {
    this.createdAt = createdAt;
  }

  @Nullable
  @DynamoDbAttribute(UPDATED_AT)
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(@Nullable Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  @Nullable
  @DynamoDbAttribute(PHOTOS)
  public List<Photo> getPhotos() {
    return photos;
  }

  public void setPhotos(@Nullable List<Photo> photos) {
    this.photos = photos;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    TcgInventoryItem that = (TcgInventoryItem) o;
    return Objects.equals(pk, that.pk)
        && Objects.equals(sk, that.sk)
        && Objects.equals(gsi1pk, that.gsi1pk)
        && Objects.equals(gsi1sk, that.gsi1sk)
        && Objects.equals(gsi2pk, that.gsi2pk)
        && Objects.equals(gsi2sk, that.gsi2sk)
        && Objects.equals(gsi3pk, that.gsi3pk)
        && Objects.equals(skuId, that.skuId)
        && Objects.equals(scryfallId, that.scryfallId)
        && Objects.equals(finish, that.finish)
        && Objects.equals(condition, that.condition)
        && Objects.equals(name, that.name)
        && Objects.equals(setCode, that.setCode)
        && Objects.equals(setName, that.setName)
        && Objects.equals(collectorNumber, that.collectorNumber)
        && Objects.equals(fetchtcgCardId, that.fetchtcgCardId)
        && Objects.equals(fetchtcgSetId, that.fetchtcgSetId)
        && Objects.equals(version, that.version)
        && Objects.equals(dirty, that.dirty)
        && Objects.equals(sequenceNumber, that.sequenceNumber)
        && Objects.equals(status, that.status)
        && Objects.equals(importId, that.importId)
        && Objects.equals(orderId, that.orderId)
        && Objects.equals(filename, that.filename)
        && Objects.equals(nextSequenceNumber, that.nextSequenceNumber)
        && Objects.equals(position, that.position)
        && Objects.equals(decision, that.decision)
        && Objects.equals(decisionReason, that.decisionReason)
        && Objects.equals(marketPrice, that.marketPrice)
        && Objects.equals(suggestedPrice, that.suggestedPrice)
        && Objects.equals(rowCount, that.rowCount)
        && Objects.equals(error, that.error)
        && Objects.equals(language, that.language)
        && Objects.equals(jobType, that.jobType)
        && Objects.equals(jobId, that.jobId)
        && Objects.equals(continuation, that.continuation)
        && Objects.equals(processedCount, that.processedCount)
        && Objects.equals(fetchtcgListingId, that.fetchtcgListingId)
        && Objects.equals(lastPublishedQuantity, that.lastPublishedQuantity)
        && Objects.equals(lastPublishedPrice, that.lastPublishedPrice)
        && Objects.equals(lastPublishedAt, that.lastPublishedAt)
        && Objects.equals(deliveryMode, that.deliveryMode)
        && Objects.equals(totalPrice, that.totalPrice)
        && Objects.equals(lines, that.lines)
        && Objects.equals(fetchtcgStatus, that.fetchtcgStatus)
        && Objects.equals(fetchtcgCurrentAction, that.fetchtcgCurrentAction)
        && Objects.equals(eventType, that.eventType)
        && Objects.equals(trackOrdersAfter, that.trackOrdersAfter)
        && Objects.equals(report, that.report)
        && Objects.equals(asOfAuditUlid, that.asOfAuditUlid)
        && Objects.equals(createdAt, that.createdAt)
        && Objects.equals(updatedAt, that.updatedAt)
        && Objects.equals(photos, that.photos);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        pk,
        sk,
        gsi1pk,
        gsi1sk,
        gsi2pk,
        gsi2sk,
        gsi3pk,
        skuId,
        scryfallId,
        finish,
        condition,
        name,
        setCode,
        setName,
        collectorNumber,
        fetchtcgCardId,
        fetchtcgSetId,
        version,
        dirty,
        sequenceNumber,
        status,
        importId,
        orderId,
        filename,
        nextSequenceNumber,
        position,
        decision,
        decisionReason,
        marketPrice,
        suggestedPrice,
        rowCount,
        error,
        language,
        jobType,
        jobId,
        continuation,
        processedCount,
        fetchtcgListingId,
        lastPublishedQuantity,
        lastPublishedPrice,
        lastPublishedAt,
        deliveryMode,
        totalPrice,
        lines,
        fetchtcgStatus,
        fetchtcgCurrentAction,
        eventType,
        trackOrdersAfter,
        report,
        asOfAuditUlid,
        createdAt,
        updatedAt,
        photos);
  }

  @Override
  public String toString() {
    return "TcgInventoryItem{"
        + "pk='"
        + pk
        + '\''
        + ", sk='"
        + sk
        + '\''
        + ", gsi1pk='"
        + gsi1pk
        + '\''
        + ", gsi1sk='"
        + gsi1sk
        + '\''
        + ", gsi2pk='"
        + gsi2pk
        + '\''
        + ", gsi2sk='"
        + gsi2sk
        + '\''
        + ", gsi3pk='"
        + gsi3pk
        + '\''
        + ", skuId='"
        + skuId
        + '\''
        + ", scryfallId='"
        + scryfallId
        + '\''
        + ", finish='"
        + finish
        + '\''
        + ", condition='"
        + condition
        + '\''
        + ", name='"
        + name
        + '\''
        + ", setCode='"
        + setCode
        + '\''
        + ", setName='"
        + setName
        + '\''
        + ", collectorNumber='"
        + collectorNumber
        + '\''
        + ", fetchtcgCardId="
        + fetchtcgCardId
        + ", fetchtcgSetId="
        + fetchtcgSetId
        + ", version="
        + version
        + ", dirty="
        + dirty
        + ", sequenceNumber="
        + sequenceNumber
        + ", status='"
        + status
        + '\''
        + ", importId='"
        + importId
        + '\''
        + ", orderId='"
        + orderId
        + '\''
        + ", filename='"
        + filename
        + '\''
        + ", nextSequenceNumber="
        + nextSequenceNumber
        + ", position="
        + position
        + ", decision='"
        + decision
        + '\''
        + ", decisionReason='"
        + decisionReason
        + '\''
        + ", marketPrice='"
        + marketPrice
        + '\''
        + ", suggestedPrice='"
        + suggestedPrice
        + '\''
        + ", rowCount="
        + rowCount
        + ", error='"
        + error
        + '\''
        + ", language='"
        + language
        + '\''
        + ", jobType='"
        + jobType
        + '\''
        + ", jobId='"
        + jobId
        + '\''
        + ", continuation="
        + continuation
        + ", processedCount="
        + processedCount
        + ", fetchtcgListingId="
        + fetchtcgListingId
        + ", lastPublishedQuantity="
        + lastPublishedQuantity
        + ", lastPublishedPrice='"
        + lastPublishedPrice
        + '\''
        + ", lastPublishedAt="
        + lastPublishedAt
        + ", deliveryMode='"
        + deliveryMode
        + '\''
        + ", totalPrice='"
        + totalPrice
        + '\''
        + ", lines='"
        + lines
        + '\''
        + ", fetchtcgStatus='"
        + fetchtcgStatus
        + '\''
        + ", fetchtcgCurrentAction='"
        + fetchtcgCurrentAction
        + '\''
        + ", eventType='"
        + eventType
        + '\''
        + ", trackOrdersAfter="
        + trackOrdersAfter
        + ", report='"
        + report
        + '\''
        + ", asOfAuditUlid='"
        + asOfAuditUlid
        + '\''
        + ", createdAt="
        + createdAt
        + ", updatedAt="
        + updatedAt
        + ", photos="
        + photos
        + '}';
  }

  public static String formatSkuPk(String user, String skuId) {
    return USER_PREFIX + user + DELIMITER + SKU_PREFIX + skuId;
  }

  public static String formatSkuSk() {
    return "SKU";
  }

  public static String formatUnitSk(int sequenceNumber) {
    return UNIT_PREFIX + String.format("%010d", sequenceNumber);
  }

  public static String formatUserPk(String user) {
    return USER_PREFIX + user;
  }

  public static String formatImportSk(String importId) {
    return IMPORT_PREFIX + importId;
  }

  public static String formatImportRowPk(String user, String importId) {
    return USER_PREFIX + user + DELIMITER + IMPORT_PREFIX + importId;
  }

  public static String formatImportRowSk(int stackPosition) {
    return ROW_PREFIX + String.format("%010d", stackPosition);
  }

  public static String formatOrderSk(String offerId) {
    return ORDER_PREFIX + offerId;
  }

  public static String formatJobSk(String jobId) {
    return JOB_PREFIX + jobId;
  }

  public static String formatCounterSk() {
    return COUNTER_PREFIX + "SEQUENCE";
  }

  public static String formatSettingsSk() {
    return "SETTINGS";
  }

  public static String formatReportSk() {
    return "REPORT";
  }

  public static String formatAuditPk(String user) {
    return USER_PREFIX + user + DELIMITER + AUDIT_SUFFIX;
  }

  public static String formatGsi1pk(String user) {
    return USER_PREFIX + user + DELIMITER + DIRTY_SUFFIX;
  }

  public static String formatGsi1sk(String skuId) {
    return SKU_PREFIX + skuId;
  }

  public static String formatGsi2pk(String user) {
    return USER_PREFIX + user + DELIMITER + SKUS_SUFFIX;
  }

  public static String formatGsi3pk(String user) {
    return USER_PREFIX + user + DELIMITER + UNITS_SUFFIX;
  }

  public static String formatGsi2sk(String normalizedName, String skuId) {
    return NAME_PREFIX + normalizedName + DELIMITER + skuId;
  }

  public static TcgInventoryItem createSku(
      String user,
      String skuId,
      String scryfallId,
      String finish,
      String condition,
      String name,
      String setCode,
      String setName,
      String collectorNumber,
      @Nullable String fetchtcgCardId,
      @Nullable String suggestedPrice) {
    var item = new TcgInventoryItem();
    item.setPk(formatSkuPk(user, skuId));
    item.setSk(formatSkuSk());
    item.setSkuId(skuId);
    item.setScryfallId(scryfallId);
    item.setFinish(finish);
    item.setCondition(condition);
    item.setName(name);
    item.setSetCode(setCode);
    item.setSetName(setName);
    item.setCollectorNumber(collectorNumber);
    item.setFetchtcgCardId(fetchtcgCardId);
    item.setSuggestedPrice(suggestedPrice);
    item.setVersion(1);
    item.setDirty(true);
    item.setGsi1pk(formatGsi1pk(user));
    item.setGsi1sk(formatGsi1sk(skuId));
    item.setGsi2pk(formatGsi2pk(user));
    item.setGsi2sk(formatGsi2sk(name.toLowerCase(), skuId));
    return item;
  }

  public static TcgInventoryItem createUnit(
      String user,
      String skuId,
      int sequenceNumber,
      String status,
      String importId,
      Instant createdAt) {
    var item = new TcgInventoryItem();
    item.setPk(formatSkuPk(user, skuId));
    item.setSk(formatUnitSk(sequenceNumber));
    item.setGsi3pk(formatGsi3pk(user));
    item.setSequenceNumber(sequenceNumber);
    item.setStatus(status);
    item.setImportId(importId);
    item.setCreatedAt(createdAt);
    return item;
  }

  public static TcgInventoryItem createImport(
      String user,
      String importId,
      String filename,
      int rowCount,
      @Nullable String jobId,
      Instant createdAt) {
    var item = new TcgInventoryItem();
    item.setPk(formatUserPk(user));
    item.setSk(formatImportSk(importId));
    item.setImportId(importId);
    item.setFilename(filename);
    item.setStatus("appraising");
    item.setRowCount(rowCount);
    item.setJobId(jobId);
    item.setCreatedAt(createdAt);
    return item;
  }

  public static TcgInventoryItem createImportRow(
      String user,
      String importId,
      int position,
      String name,
      String setCode,
      String setName,
      String collectorNumber,
      String finish,
      String condition,
      String scryfallId,
      String language) {
    var item = new TcgInventoryItem();
    item.setPk(formatImportRowPk(user, importId));
    item.setSk(formatImportRowSk(position));
    item.setPosition(position);
    item.setName(name);
    item.setSetCode(setCode);
    item.setSetName(setName);
    item.setCollectorNumber(collectorNumber);
    item.setFinish(finish);
    item.setCondition(condition);
    item.setScryfallId(scryfallId);
    item.setLanguage(language);
    return item;
  }

  public static TcgInventoryItem createOrder(
      String user,
      String orderId,
      String status,
      @Nullable String fetchtcgStatus,
      @Nullable String fetchtcgCurrentAction,
      @Nullable String deliveryMode,
      @Nullable String totalPrice,
      @Nullable String lines,
      Instant createdAt) {
    var item = new TcgInventoryItem();
    item.setPk(formatUserPk(user));
    item.setSk(formatOrderSk(orderId));
    item.setOrderId(orderId);
    item.setStatus(status);
    item.setFetchtcgStatus(fetchtcgStatus);
    item.setFetchtcgCurrentAction(fetchtcgCurrentAction);
    item.setDeliveryMode(deliveryMode);
    item.setTotalPrice(totalPrice);
    item.setLines(lines);
    item.setCreatedAt(createdAt);
    item.setUpdatedAt(createdAt);
    return item;
  }

  public static TcgInventoryItem createJob(
      String user, String jobId, String jobType, @Nullable String importId, Instant createdAt) {
    var item = new TcgInventoryItem();
    item.setPk(formatUserPk(user));
    item.setSk(formatJobSk(jobId));
    item.setJobId(jobId);
    item.setJobType(jobType);
    item.setStatus("queued");
    item.setImportId(importId);
    item.setCreatedAt(createdAt);
    return item;
  }

  public static TcgInventoryItem createSettings(String user, Instant updatedAt) {
    var item = new TcgInventoryItem();
    item.setPk(formatUserPk(user));
    item.setSk(formatSettingsSk());
    item.setUpdatedAt(updatedAt);
    return item;
  }

  public static TcgInventoryItem createReport(
      String user, String report, @Nullable String asOfAuditUlid, Instant updatedAt) {
    var item = new TcgInventoryItem();
    item.setPk(formatUserPk(user));
    item.setSk(formatReportSk());
    item.setReport(report);
    item.setAsOfAuditUlid(asOfAuditUlid);
    item.setUpdatedAt(updatedAt);
    return item;
  }

  @DynamoDbBean
  public static class Photo {
    private String photoId;
    private String fetchtcgUrl;

    @DynamoDbAttribute(PHOTO_ID)
    public String getPhotoId() {
      return photoId;
    }

    public void setPhotoId(String photoId) {
      this.photoId = photoId;
    }

    @Nullable
    @DynamoDbAttribute(FETCHTCG_URL)
    public String getFetchtcgUrl() {
      return fetchtcgUrl;
    }

    public void setFetchtcgUrl(@Nullable String fetchtcgUrl) {
      this.fetchtcgUrl = fetchtcgUrl;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Photo photo = (Photo) o;
      return Objects.equals(photoId, photo.photoId)
          && Objects.equals(fetchtcgUrl, photo.fetchtcgUrl);
    }

    @Override
    public int hashCode() {
      return Objects.hash(photoId, fetchtcgUrl);
    }

    @Override
    public String toString() {
      return "Photo{" + "photoId='" + photoId + '\'' + ", fetchtcgUrl='" + fetchtcgUrl + '\'' + '}';
    }

    public static Photo create(String photoId, @Nullable String fetchtcgUrl) {
      var photo = new Photo();
      photo.setPhotoId(photoId);
      photo.setFetchtcgUrl(fetchtcgUrl);
      return photo;
    }
  }
}
