package com.jordansimsmith.pricetracker;

import java.net.URI;
import java.util.List;

public class ProductsFactoryImpl implements ProductsFactory {
  @Override
  public List<Product> findChemistWarehouseProducts() {
    return List.of(
        new Product(
            URI.create(
                "https://www.chemistwarehouse.co.nz/buy/98676/inc-100-dynamic-whey-cookies-and-cream-flavour-2kg"),
            "Chemist Warehouse - Dynamic Whey 2kg - Cookies and Cream"),
        new Product(
            URI.create(
                "https://www.chemistwarehouse.co.nz/buy/74330/inc-100-dynamic-whey-vanilla-flavour-2kg"),
            "Chemist Warehouse - Dynamic Whey 2kg - Vanilla"),
        new Product(
            URI.create(
                "https://www.chemistwarehouse.co.nz/buy/74329/inc-100-dynamic-whey-chocolate-flavour-2kg"),
            "Chemist Warehouse - Dynamic Whey 2kg - Chocolate"),
        new Product(
            URI.create(
                "https://www.chemistwarehouse.co.nz/buy/98677/inc-100-dynamic-whey-choc-mint-flavour-2kg"),
            "Chemist Warehouse - Dynamic Whey 2kg - Choc mint"),
        new Product(
            URI.create("https://www.chemistwarehouse.co.nz/buy/74336/inc-creatine-500g"),
            "Chemist Warehouse - INC Creatine 500g"),
        new Product(
            URI.create(
                "https://www.chemistwarehouse.co.nz/buy/91351/musashi-creatine-unflavoured-350g"),
            "Chemist Warehouse - Musashi Creatine Unflavoured 350g"),
        new Product(
            URI.create(
                "https://www.chemistwarehouse.co.nz/buy/111308/inc-high-protein-bar-choc-chip-cookie-dough-100g"),
            "Chemist Warehouse - INC High Protein Bar Choc Chip Cookie Dough 100g"),
        new Product(
            URI.create(
                "https://www.chemistwarehouse.co.nz/buy/111307/inc-high-protein-bar-choc-brownie-100g"),
            "Chemist Warehouse - INC High Protein Bar Choc Brownie 100g"),
        new Product(
            URI.create(
                "https://www.chemistwarehouse.co.nz/buy/111309/inc-high-protein-bar-peanut-butter-fudge-100g"),
            "Chemist Warehouse - INC High Protein Bar Peanut Butter Fudge 100g"),
        new Product(
            URI.create(
                "https://www.chemistwarehouse.co.nz/buy/120088/musashi-electrolytes-blue-raspberry-300g"),
            "Musashi Electrolytes Blue Raspberry 300g"),
        new Product(
            URI.create(
                "https://www.chemistwarehouse.co.nz/buy/101969/musashi-electrolytes-watermelon-300g"),
            "Musashi Electrolytes Watermelon 300g"),
        new Product(
            URI.create(
                "https://www.chemistwarehouse.co.nz/buy/80063/quest-protein-bar-white-chocolate-raspberry-60g"),
            "Quest Protein Bar White Chocolate Raspberry 60g"),
        new Product(
            URI.create("https://www.chemistwarehouse.co.nz/buy/82946/quest-protein-bar-smores-60g"),
            "Quest Protein Bar Smores 60g"),
        new Product(
            URI.create(
                "https://www.chemistwarehouse.co.nz/buy/136022/quest-protein-bar-dipped-cookies-cream-50g"),
            "Quest Protein Bar Dipped Cookies & Cream 50g"),
        new Product(
            URI.create(
                "https://www.chemistwarehouse.co.nz/buy/88817/quest-protein-bar-birthday-cake-60g"),
            "Quest Protein Bar Birthday Cake 60g"),
        new Product(
            URI.create(
                "https://www.chemistwarehouse.co.nz/buy/80060/quest-protein-bar-chocolate-chip-cookie-dough-60g"),
            "Quest Protein Bar Chocolate Chip Cookie Dough 60g"),
        new Product(
            URI.create(
                "https://www.chemistwarehouse.co.nz/buy/82940/quest-protein-bar-chocolate-brownie-60g"),
            "Quest Protein Bar Chocolate Brownie 60g"),
        new Product(
            URI.create(
                "https://www.chemistwarehouse.co.nz/buy/80061/quest-protein-bar-cookies-cream-60g"),
            "Quest Protein Bar Cookies & Cream 60g"),
        new Product(
            URI.create(
                "https://www.chemistwarehouse.co.nz/buy/136023/quest-protein-bar-dipped-choc-chip-cookie-dough-50g"),
            "Quest Protein Bar Dipped Choc Chip Cookie Dough 50g"));
  }
}
