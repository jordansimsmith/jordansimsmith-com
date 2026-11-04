package com.jordansimsmith.tcginventory.inventory.server;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class InventoryReservationItem {
  private String pk;
  private String sk;
  private String user;
  private String reservationId;
  private String skuId;
  private int sequenceNumber;
  private String status;

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

  @DynamoDbAttribute("status")
  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public static InventoryReservationItem create(
      String user, String reservationId, String skuId, int sequenceNumber, String status) {
    var item = new InventoryReservationItem();
    item.setPk("USER#" + user + "#INVENTORY");
    item.setSk("RESERVATION#" + reservationId + "#SKU#" + skuId + "#UNIT#" + sequenceNumber);
    item.setUser(user);
    item.setReservationId(reservationId);
    item.setSkuId(skuId);
    item.setSequenceNumber(sequenceNumber);
    item.setStatus(status);
    return item;
  }
}
