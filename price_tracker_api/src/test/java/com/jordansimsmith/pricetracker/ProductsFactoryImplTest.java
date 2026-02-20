package com.jordansimsmith.pricetracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

public class ProductsFactoryImplTest {
  @Test
  void findProductsShouldBuildUrlsFromConfiguredChemistWarehouseBaseUrl() {
    // arrange
    var productsFactory =
        new ProductsFactoryImpl(
            URI.create("http://chemist.example:8080"),
            URI.create("http://protein.example:8080"),
            URI.create("http://muscle.example:8080"));

    // act
    var remappedProduct =
        productsFactory.findProducts().stream()
            .filter(
                product ->
                    product.name().equals("Chemist Warehouse - Dynamic Whey 2kg - Chocolate"))
            .findFirst()
            .orElseThrow();

    // assert
    assertThat(remappedProduct.url().toString())
        .isEqualTo(
            "http://chemist.example:8080/buy/74329/inc-100-dynamic-whey-chocolate-flavour-2kg");
  }

  @Test
  void findProductsShouldPreservePathAndQueryWhenBuildingNzMuscleUrls() {
    // arrange
    var productsFactory =
        new ProductsFactoryImpl(
            URI.create("http://chemist.example:8080"),
            URI.create("http://protein.example:8080"),
            URI.create("http://muscle.example:8080"));

    // act
    var remappedProduct =
        productsFactory.findProducts().stream()
            .filter(product -> product.name().equals("Shotgun Whey Protein Chocolate 2kg"))
            .findFirst()
            .orElseThrow();

    // assert
    assertThat(remappedProduct.url().toString())
        .isEqualTo(
            "http://muscle.example:8080/products/shotgun-whey-protein?variant=51471561195701");
  }

  @Test
  void findProductsShouldBuildUrlsFromConfiguredNzProteinBaseUrl() {
    // arrange
    var productsFactory =
        new ProductsFactoryImpl(
            URI.create("http://chemist.example:8080"),
            URI.create("http://protein.example:8080"),
            URI.create("http://muscle.example:8080"));

    // act
    var remappedProduct =
        productsFactory.findProducts().stream()
            .filter(product -> product.name().equals("NZ Protein - NZ Whey 1kg (2.2lbs)"))
            .findFirst()
            .orElseThrow();

    // assert
    assertThat(remappedProduct.url().toString())
        .isEqualTo("http://protein.example:8080/product/nz-whey-1kg-2-2lbs");
  }
}
