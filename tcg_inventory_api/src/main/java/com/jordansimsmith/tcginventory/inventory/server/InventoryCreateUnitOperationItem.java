package com.jordansimsmith.tcginventory.inventory.server;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class InventoryCreateUnitOperationItem {
  private String pk;
  private String sk;
  private String user;
  private String operationId;
  private String skuId;
  private int sequenceNumber;
  private String location;

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

  @DynamoDbAttribute("user")
  public String getUser() {
    return user;
  }

  public void setUser(String user) {
    this.user = user;
  }

  @DynamoDbAttribute("operation_id")
  public String getOperationId() {
    return operationId;
  }

  public void setOperationId(String operationId) {
    this.operationId = operationId;
  }

  @DynamoDbAttribute("sku_id")
  public String getSkuId() {
    return skuId;
  }

  public void setSkuId(String skuId) {
    this.skuId = skuId;
  }

  @DynamoDbAttribute("sequence_number")
  public int getSequenceNumber() {
    return sequenceNumber;
  }

  public void setSequenceNumber(int sequenceNumber) {
    this.sequenceNumber = sequenceNumber;
  }

  @DynamoDbAttribute("location")
  public String getLocation() {
    return location;
  }

  public void setLocation(String location) {
    this.location = location;
  }

  public static InventoryCreateUnitOperationItem create(
      String user, String operationId, String skuId, int sequenceNumber, String location) {
    var item = new InventoryCreateUnitOperationItem();
    item.setPk("USER#" + user + "#INVENTORY");
    item.setSk("OPERATION#CREATE_UNIT#" + operationId);
    item.setUser(user);
    item.setOperationId(operationId);
    item.setSkuId(skuId);
    item.setSequenceNumber(sequenceNumber);
    item.setLocation(location);
    return item;
  }
}
