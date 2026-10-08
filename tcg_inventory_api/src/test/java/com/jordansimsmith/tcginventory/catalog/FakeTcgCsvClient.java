package com.jordansimsmith.tcginventory.catalog;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeTcgCsvClient implements TcgCsvClient {
  private List<UpdateMarker> updateMarkers =
      List.of(new UpdateMarker("2026-10-07T20:06:09+0000", Instant.parse("2026-10-07T20:06:09Z")));
  private List<Group> groups = List.of(new Group(3170, 3, "Silver Tempest", "SWSH12"));
  private final Map<Integer, List<Product>> productsByGroup = new HashMap<>();
  private final Map<Integer, List<Price>> pricesByGroup = new HashMap<>();
  private IOException productFailure;
  private int updateMarkerCalls;
  private int groupCalls;
  private int productCalls;
  private int priceCalls;

  public FakeTcgCsvClient() {
    productsByGroup.put(
        3170,
        List.of(
            new Product(
                451396,
                3,
                3170,
                "Lugia VSTAR",
                List.of(
                    new ExtendedData("Card Type", "Pokémon"), new ExtendedData("Number", "139")))));
    pricesByGroup.put(3170, List.of(new Price(451396, "Holofoil")));
  }

  @Override
  public UpdateMarker getUpdateMarker() {
    var index = Math.min(updateMarkerCalls, updateMarkers.size() - 1);
    updateMarkerCalls++;
    return updateMarkers.get(index);
  }

  @Override
  public List<Group> findGroups(int categoryId) {
    groupCalls++;
    return groups;
  }

  @Override
  public List<Product> findProducts(int categoryId, int groupId) throws IOException {
    productCalls++;
    if (productFailure != null) {
      throw productFailure;
    }
    return productsByGroup.get(groupId);
  }

  @Override
  public List<Price> findPrices(int categoryId, int groupId) {
    priceCalls++;
    return pricesByGroup.get(groupId);
  }

  public void setUpdateMarkers(UpdateMarker... updateMarkers) {
    this.updateMarkers = List.of(updateMarkers);
    this.updateMarkerCalls = 0;
  }

  public void setGroups(List<Group> groups) {
    this.groups = List.copyOf(groups);
  }

  public void setProducts(int groupId, List<Product> products) {
    productsByGroup.put(groupId, List.copyOf(products));
  }

  public void setPrices(int groupId, List<Price> prices) {
    pricesByGroup.put(groupId, List.copyOf(prices));
  }

  public void setProductFailure(IOException productFailure) {
    this.productFailure = productFailure;
  }

  public int updateMarkerCalls() {
    return updateMarkerCalls;
  }

  public int groupCalls() {
    return groupCalls;
  }

  public int productCalls() {
    return productCalls;
  }

  public int priceCalls() {
    return priceCalls;
  }
}
