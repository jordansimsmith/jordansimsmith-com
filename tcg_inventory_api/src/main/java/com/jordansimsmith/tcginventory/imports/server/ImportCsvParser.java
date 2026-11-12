package com.jordansimsmith.tcginventory.imports.server;

import com.jordansimsmith.tcginventory.games.client.Game;
import com.jordansimsmith.tcginventory.imports.client.CreateImportRow;
import java.util.List;

public interface ImportCsvParser {
  List<CreateImportRow> parse(String csv, Game game);
}
