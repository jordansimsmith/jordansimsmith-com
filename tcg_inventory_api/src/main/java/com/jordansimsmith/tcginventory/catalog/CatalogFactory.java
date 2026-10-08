package com.jordansimsmith.tcginventory.catalog;

import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.TcgInventoryModule;
import dagger.Component;
import javax.inject.Singleton;

@Singleton
@Component(modules = {TcgInventoryModule.class, CatalogModule.class})
public interface CatalogFactory extends TcgInventoryFactory {
  Catalogs catalogs();

  TcgCsvClient tcgCsvClient();

  static CatalogFactory create() {
    return DaggerCatalogFactory.create();
  }
}
