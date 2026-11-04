package com.jordansimsmith.tcginventory.inventory.server;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class InventoryReserveUnitOperationItem {
  private String pk;
  private String sk;
  private String user;
  private String operationId;
  private String reservationId;
  private String skuId;
  private int sequenceNumber;

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

  @DynamoDbAttribute("reservation_id")
  public String getReservationId() {
    return reservationId;
  }

  public void setReservationId(String reservationId) {
    this.reservationId = reservationId;
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

  public static InventoryReserveUnitOperationItem create(
      String user, String operationId, String reservationId, String skuId, int sequenceNumber) {
    var item = new InventoryReserveUnitOperationItem();
    item.setPk("USER#" + user + "#INVENTORY");
    item.setSk("OPERATION#RESERVE_UNIT#" + operationId);
    item.setUser(user);
    item.setOperationId(operationId);
    item.setReservationId(reservationId);
    item.setSkuId(skuId);
    item.setSequenceNumber(sequenceNumber);
    return item;
  }
}
