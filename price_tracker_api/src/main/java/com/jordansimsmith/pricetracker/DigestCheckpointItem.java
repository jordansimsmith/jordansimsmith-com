package com.jordansimsmith.pricetracker;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class DigestCheckpointItem {
  public static final String PK_VALUE = "DIGEST#PRICE_DECREASES";
  public static final String SK_VALUE = "CHECKPOINT";

  private String pk;
  private String sk;
  private Instant processedThrough;

  @DynamoDbPartitionKey
  @DynamoDbAttribute("pk")
  public String getPk() {
    return pk;
  }

  public void setPk(String pk) {
    this.pk = pk;
  }

  @DynamoDbSortKey
  @DynamoDbAttribute("sk")
  public String getSk() {
    return sk;
  }

  public void setSk(String sk) {
    this.sk = sk;
  }

  @DynamoDbAttribute("processed_through")
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  public Instant getProcessedThrough() {
    return processedThrough;
  }

  public void setProcessedThrough(Instant processedThrough) {
    this.processedThrough = processedThrough;
  }

  public static DigestCheckpointItem create(Instant processedThrough) {
    var item = new DigestCheckpointItem();
    item.setPk(PK_VALUE);
    item.setSk(SK_VALUE);
    item.setProcessedThrough(processedThrough);
    return item;
  }
}
