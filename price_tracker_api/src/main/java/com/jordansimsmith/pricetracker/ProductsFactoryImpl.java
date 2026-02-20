package com.jordansimsmith.pricetracker;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class ProductsFactoryImpl implements ProductsFactory {
  private record CatalogProduct(String pathAndQuery, String name) {}

  private final URI chemistWarehouseBaseUri;
  private final URI nzProteinBaseUri;
  private final URI nzMuscleBaseUri;

  private static final List<CatalogProduct> CHEMIST_WAREHOUSE_PRODUCTS =
      List.of(
          new CatalogProduct(
              "/buy/98676/inc-100-dynamic-whey-cookies-and-cream-flavour-2kg",
              "Chemist Warehouse - Dynamic Whey 2kg - Cookies and Cream"),
          new CatalogProduct(
              "/buy/74330/inc-100-dynamic-whey-vanilla-flavour-2kg",
              "Chemist Warehouse - Dynamic Whey 2kg - Vanilla"),
          new CatalogProduct(
              "/buy/74329/inc-100-dynamic-whey-chocolate-flavour-2kg",
              "Chemist Warehouse - Dynamic Whey 2kg - Chocolate"),
          new CatalogProduct(
              "/buy/98677/inc-100-dynamic-whey-choc-mint-flavour-2kg",
              "Chemist Warehouse - Dynamic Whey 2kg - Choc mint"),
          new CatalogProduct(
              "/buy/79763/inc-micellar-casein-vanilla-2kg", "INC Micellar Casein Vanilla 2kg"),
          new CatalogProduct(
              "/buy/79762/inc-micellar-casein-chocolate-2kg", "INC Micellar Casein Chocolate 2kg"),
          new CatalogProduct(
              "/buy/74332/inc-100-wpi-vanilla-flavour-2kg", "INC 100% WPI Vanilla Flavour 2kg"),
          new CatalogProduct(
              "/buy/74331/inc-100-wpi-chocolate-flavour-2kg", "INC 100% WPI Chocolate Flavour 2kg"),
          new CatalogProduct(
              "/buy/74350/inc-shred-max-pro-vanilla-flavour-2kg",
              "INC Shred Max Pro Vanilla Flavour 2kg"),
          new CatalogProduct(
              "/buy/74347/inc-shred-max-pro-chocolate-flavour-2kg",
              "INC Shred Max Pro Chocolate Flavour 2kg"),
          new CatalogProduct(
              "/buy/74336/inc-creatine-500g", "Chemist Warehouse - INC Creatine 500g"),
          new CatalogProduct(
              "/buy/91351/musashi-creatine-unflavoured-350g",
              "Chemist Warehouse - Musashi Creatine Unflavoured 350g"),
          new CatalogProduct(
              "/buy/111308/inc-high-protein-bar-choc-chip-cookie-dough-100g",
              "Chemist Warehouse - INC High Protein Bar Choc Chip Cookie Dough 100g"),
          new CatalogProduct(
              "/buy/111307/inc-high-protein-bar-choc-brownie-100g",
              "Chemist Warehouse - INC High Protein Bar Choc Brownie 100g"),
          new CatalogProduct(
              "/buy/111309/inc-high-protein-bar-peanut-butter-fudge-100g",
              "Chemist Warehouse - INC High Protein Bar Peanut Butter Fudge 100g"),
          new CatalogProduct(
              "/buy/111303/inc-shred-max-protein-bar-raspberry-coconut-60g",
              "INC Shred Max Protein Bar Raspberry Coconut 60g"),
          new CatalogProduct(
              "/buy/111301/inc-shred-max-protein-bar-choc-crunch-60g",
              "INC Shred Max Protein Bar Choc Crunch 60g"),
          new CatalogProduct(
              "/buy/111305/inc-low-carb-protein-bar-choc-chip-mint-60g",
              "INC Low Carb Protein Bar Choc Chip Mint 60g"),
          new CatalogProduct(
              "/buy/111302/inc-shred-max-protein-bar-peanut-caramel-60g",
              "INC Shred Max Protein Bar Peanut Caramel 60g"),
          new CatalogProduct(
              "/buy/111304/inc-low-carb-protein-bar-choc-caramel-60g",
              "INC Low Carb Protein Bar Choc Caramel 60g"),
          new CatalogProduct(
              "/buy/111306/inc-low-carb-protein-bar-cookies-cream-60g",
              "INC Low Carb Protein Bar Cookies & Cream 60g"),
          new CatalogProduct(
              "/buy/120088/musashi-electrolytes-blue-raspberry-300g",
              "Musashi Electrolytes Blue Raspberry 300g"),
          new CatalogProduct(
              "/buy/101969/musashi-electrolytes-watermelon-300g",
              "Musashi Electrolytes Watermelon 300g"),
          new CatalogProduct(
              "/buy/80063/quest-protein-bar-white-chocolate-raspberry-60g",
              "Quest Protein Bar White Chocolate Raspberry 60g"),
          new CatalogProduct(
              "/buy/82946/quest-protein-bar-smores-60g", "Quest Protein Bar Smores 60g"),
          new CatalogProduct(
              "/buy/136022/quest-protein-bar-dipped-cookies-cream-50g",
              "Quest Protein Bar Dipped Cookies & Cream 50g"),
          new CatalogProduct(
              "/buy/88817/quest-protein-bar-birthday-cake-60g",
              "Quest Protein Bar Birthday Cake 60g"),
          new CatalogProduct(
              "/buy/80060/quest-protein-bar-chocolate-chip-cookie-dough-60g",
              "Quest Protein Bar Chocolate Chip Cookie Dough 60g"),
          new CatalogProduct(
              "/buy/82940/quest-protein-bar-chocolate-brownie-60g",
              "Quest Protein Bar Chocolate Brownie 60g"),
          new CatalogProduct(
              "/buy/80061/quest-protein-bar-cookies-cream-60g",
              "Quest Protein Bar Cookies & Cream 60g"),
          new CatalogProduct(
              "/buy/136023/quest-protein-bar-dipped-choc-chip-cookie-dough-50g",
              "Quest Protein Bar Dipped Choc Chip Cookie Dough 50g"),
          new CatalogProduct(
              "/buy/63104/ego-qv-flare-up-cream-100g", "Ego QV Flare Up Cream 100g"));

  private static final List<CatalogProduct> NZ_PROTEIN_PRODUCTS =
      List.of(
          new CatalogProduct("/product/nz-whey-1kg-2-2lbs", "NZ Protein - NZ Whey 1kg (2.2lbs)"));

  private static final List<CatalogProduct> NZ_MUSCLE_PRODUCTS =
      List.of(
          new CatalogProduct(
              "/products/shotgun-whey-protein?variant=51471561195701",
              "Shotgun Whey Protein Chocolate 2kg"),
          new CatalogProduct(
              "/products/shotgun-whey-protein?variant=51471561162933",
              "Shotgun Whey Protein Vanilla 2kg"),
          new CatalogProduct(
              "/products/shotgun-whey-protein?variant=51471561228469",
              "Shotgun Whey Protein Strawberry 2kg"),
          new CatalogProduct(
              "/products/shotgun-whey-protein?variant=51471561261237",
              "Shotgun Whey Protein Banana 2kg"),
          new CatalogProduct(
              "/products/musashi-high-protein-bars?variant=51471487467701",
              "Musashi High Protein Bar Cookies N Cream"),
          new CatalogProduct(
              "/products/musashi-high-protein-bars?variant=51471487631541",
              "Musashi High Protein Bar Peanut Butter"),
          new CatalogProduct(
              "/products/musashi-high-protein-bars?variant=51471487664309",
              "Musashi High Protein Bar White Choc Caramel"),
          new CatalogProduct(
              "/products/musashi-high-protein-bars?variant=51471487500469",
              "Musashi High Protein Bar Milk Chocolate Brownie"),
          new CatalogProduct(
              "/products/musashi-high-protein-bars?variant=51471487533237",
              "Musashi High Protein Bar Dark Choc Salted Caramel"),
          new CatalogProduct(
              "/products/musashi-high-protein-bars?variant=51471487729845",
              "Musashi High Protein Bar White Choc Lemon Cheesecake"),
          new CatalogProduct(
              "/products/quest-nutrition-protein-bars?variant=51471548514485",
              "Quest Nutrition Protein Bar Choc Peanut Butter"),
          new CatalogProduct(
              "/products/quest-nutrition-protein-bars?variant=51471548383413",
              "Quest Nutrition Protein Bar Caramel Choc Chunk"),
          new CatalogProduct(
              "/products/quest-nutrition-protein-bars?variant=51471548481717",
              "Quest Nutrition Protein Bar Cookies & Cream"),
          new CatalogProduct(
              "/products/quest-nutrition-protein-bars?variant=51471548416181",
              "Quest Nutrition Protein Bar Choc Chip Cookie Dough"),
          new CatalogProduct(
              "/products/quest-nutrition-protein-bars?variant=51471548580021",
              "Quest Nutrition Protein Bar White Chocolate Raspberry"),
          new CatalogProduct(
              "/products/quest-nutrition-protein-bars?variant=51471548448949",
              "Quest Nutrition Protein Bar Chocolate Brownie"),
          new CatalogProduct(
              "/products/quest-nutrition-protein-bars?variant=51471548547253",
              "Quest Nutrition Protein Bar Smores"),
          new CatalogProduct(
              "/products/quest-nutrition-protein-bars?variant=51471548350645",
              "Quest Nutrition Protein Bar Birthday Cake"),
          new CatalogProduct(
              "/products/nz-muscle-protein-water?variant=51471524823221",
              "NZ Muscle Protein Water Tropical 375g"),
          new CatalogProduct(
              "/products/nz-muscle-protein-water?variant=51471524855989",
              "NZ Muscle Protein Water Pineapple Popsicle 375g"),
          new CatalogProduct(
              "/products/nz-muscle-protein-water?variant=51471524888757",
              "NZ Muscle Protein Water Raspberry Lemonade 375g"),
          new CatalogProduct(
              "/products/nz-muscle-protein-water?variant=51471524921525",
              "NZ Muscle Protein Water Passionfruit 375g"),
          new CatalogProduct(
              "/products/nz-muscle-protein-water?variant=51471524954293",
              "NZ Muscle Protein Water Watermelon Burst 375g"),
          new CatalogProduct(
              "/products/nz-muscle-protein-water?variant=51471524987061",
              "NZ Muscle Protein Water Peach Iced Tea 375g"));

  public ProductsFactoryImpl(
      URI chemistWarehouseBaseUri, URI nzProteinBaseUri, URI nzMuscleBaseUri) {
    this.chemistWarehouseBaseUri = chemistWarehouseBaseUri;
    this.nzProteinBaseUri = nzProteinBaseUri;
    this.nzMuscleBaseUri = nzMuscleBaseUri;
  }

  @Override
  public List<Product> findProducts() {
    var allProducts = new ArrayList<Product>();
    allProducts.addAll(buildProducts(chemistWarehouseBaseUri, CHEMIST_WAREHOUSE_PRODUCTS));
    allProducts.addAll(buildProducts(nzProteinBaseUri, NZ_PROTEIN_PRODUCTS));
    allProducts.addAll(buildProducts(nzMuscleBaseUri, NZ_MUSCLE_PRODUCTS));
    return allProducts;
  }

  private static List<Product> buildProducts(URI baseUri, List<CatalogProduct> catalogProducts) {
    return catalogProducts.stream()
        .map(product -> new Product(baseUri.resolve(product.pathAndQuery()), product.name()))
        .toList();
  }
}
