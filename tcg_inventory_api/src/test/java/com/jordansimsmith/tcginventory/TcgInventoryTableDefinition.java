package com.jordansimsmith.tcginventory;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class TcgInventoryTableDefinition {
  private String pk;
  private String sk;
  private String gsi1pk;
  private String gsi1sk;
  private String gsi2pk;
  private String gsi2sk;
  private String gsi3pk;
  private Integer sequenceNumber;

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

  @DynamoDbSecondaryPartitionKey(indexNames = TcgInventoryTable.GSI1_NAME)
  @DynamoDbAttribute("gsi1pk")
  public String getGsi1pk() {
    return gsi1pk;
  }

  public void setGsi1pk(String gsi1pk) {
    this.gsi1pk = gsi1pk;
  }

  @DynamoDbSecondarySortKey(indexNames = TcgInventoryTable.GSI1_NAME)
  @DynamoDbAttribute("gsi1sk")
  public String getGsi1sk() {
    return gsi1sk;
  }

  public void setGsi1sk(String gsi1sk) {
    this.gsi1sk = gsi1sk;
  }

  @DynamoDbSecondaryPartitionKey(indexNames = TcgInventoryTable.GSI2_NAME)
  @DynamoDbAttribute("gsi2pk")
  public String getGsi2pk() {
    return gsi2pk;
  }

  public void setGsi2pk(String gsi2pk) {
    this.gsi2pk = gsi2pk;
  }

  @DynamoDbSecondarySortKey(indexNames = TcgInventoryTable.GSI2_NAME)
  @DynamoDbAttribute("gsi2sk")
  public String getGsi2sk() {
    return gsi2sk;
  }

  public void setGsi2sk(String gsi2sk) {
    this.gsi2sk = gsi2sk;
  }

  @DynamoDbSecondaryPartitionKey(indexNames = TcgInventoryTable.GSI3_NAME)
  @DynamoDbAttribute("gsi3pk")
  public String getGsi3pk() {
    return gsi3pk;
  }

  public void setGsi3pk(String gsi3pk) {
    this.gsi3pk = gsi3pk;
  }

  @DynamoDbSecondarySortKey(indexNames = TcgInventoryTable.GSI3_NAME)
  @DynamoDbAttribute("sequence_number")
  public Integer getSequenceNumber() {
    return sequenceNumber;
  }

  public void setSequenceNumber(Integer sequenceNumber) {
    this.sequenceNumber = sequenceNumber;
  }
}
