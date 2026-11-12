package com.jordansimsmith.tcginventory.imports.server;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class CreateImportOperationItem {
  private String pk;
  private String sk;
  private String user;
  private String operationId;
  private String importId;

  @DynamoDbPartitionKey
  @DynamoDbAttribute("pk")
  public String getPk() {
    return pk;
  }

  public void setPk(String value) {
    pk = value;
  }

  @DynamoDbSortKey
  @DynamoDbAttribute("sk")
  public String getSk() {
    return sk;
  }

  public void setSk(String value) {
    sk = value;
  }

  @DynamoDbAttribute("user")
  public String getUser() {
    return user;
  }

  public void setUser(String value) {
    user = value;
  }

  @DynamoDbAttribute("operation_id")
  public String getOperationId() {
    return operationId;
  }

  public void setOperationId(String value) {
    operationId = value;
  }

  @DynamoDbAttribute("import_id")
  public String getImportId() {
    return importId;
  }

  public void setImportId(String value) {
    importId = value;
  }

  public static CreateImportOperationItem create(String user, String operationId, String importId) {
    var item = new CreateImportOperationItem();
    item.setPk("USER#" + user + "#IMPORT_OPERATIONS");
    item.setSk("CREATE#" + operationId);
    item.setUser(user);
    item.setOperationId(operationId);
    item.setImportId(importId);
    return item;
  }
}
