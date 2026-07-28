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
            URI.create("http://sportsfuel.example:8080"),
            URI.create("http://vivobarefoot.example:8080"));

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
  void findProductsShouldBuildUrlsFromConfiguredNzProteinBaseUrl() {
    // arrange
    var productsFactory =
        new ProductsFactoryImpl(
            URI.create("http://chemist.example:8080"),
            URI.create("http://protein.example:8080"),
            URI.create("http://sportsfuel.example:8080"),
            URI.create("http://vivobarefoot.example:8080"));

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

  @Test
  void findProductsShouldBuildSingleUrlFromConfiguredSportsfuelBaseUrl() {
    // arrange
    var productsFactory =
        new ProductsFactoryImpl(
            URI.create("http://chemist.example:8080"),
            URI.create("http://protein.example:8080"),
            URI.create("http://sportsfuel.example:8080"),
            URI.create("http://vivobarefoot.example:8080"));

    // act
    var sportsfuelProducts =
        productsFactory.findProducts().stream()
            .filter(product -> product.url().getHost().equals("sportsfuel.example"))
            .toList();

    // assert
    assertThat(sportsfuelProducts).hasSize(1);
    assertThat(sportsfuelProducts.get(0).name())
        .isEqualTo("Sportsfuel - Clean Nutrition Whey Protein 1kg - Vanilla");
    assertThat(sportsfuelProducts.get(0).url().toString())
        .isEqualTo(
            "http://sportsfuel.example:8080/products/clean-nutrition-whey-protein-1kg?variant=14788899504195");
  }

  @Test
  void findProductsShouldBuildSingleUrlFromConfiguredVivobarefootBaseUrl() {
    // arrange
    var productsFactory =
        new ProductsFactoryImpl(
            URI.create("http://chemist.example:8080"),
            URI.create("http://protein.example:8080"),
            URI.create("http://sportsfuel.example:8080"),
            URI.create("http://vivobarefoot.example:8080"));

    // act
    var vivobarefootProducts =
        productsFactory.findProducts().stream()
            .filter(product -> product.url().getHost().equals("vivobarefoot.example"))
            .toList();

    // assert
    assertThat(vivobarefootProducts).hasSize(1);
    assertThat(vivobarefootProducts.get(0).name())
        .isEqualTo("Vivobarefoot - Tracker Forest ESC Men's - Bracken");
    assertThat(vivobarefootProducts.get(0).url().toString())
        .isEqualTo("http://vivobarefoot.example:8080/products/tracker-forest-esc-mens-bracken");
  }
}
