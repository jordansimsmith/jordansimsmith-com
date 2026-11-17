package com.jordansimsmith.tcginventory.fetchtcg.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.fetchtcg.client.CardSet;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindSetsRequest;
import org.junit.jupiter.api.Test;

public class FindSetsServiceTest {
  private final FindSetsService service = new FindSetsService();
  private final RequestContext ctx = new RequestContext("alice");

  @Test
  void findSetsShouldReturnMagicSetsWhenCodeMatches() throws BadRequestException {
    // act
    var masters = service.findSets(ctx, new FindSetsRequest("mtg", "a25")).sets();
    var theList = service.findSets(ctx, new FindSetsRequest("mtg", "plst")).sets();

    // assert
    assertThat(masters).isNotEmpty();
    assertThat(theList).hasSizeGreaterThan(1).contains(new CardSet(3075, "Mystery Booster"));
  }

  @Test
  void findSetsShouldReturnEmptyWhenMagicCodeCaseDiffers() throws BadRequestException {
    // act
    var sets = service.findSets(ctx, new FindSetsRequest("mtg", "A25")).sets();

    // assert
    assertThat(sets).isEmpty();
  }

  @Test
  void findSetsShouldFindPokemonSetsWhenCodeHasMixedCase() throws BadRequestException {
    // act
    var vividVoltage = service.findSets(ctx, new FindSetsRequest("poke", "sWsH04")).sets();
    var evolutions = service.findSets(ctx, new FindSetsRequest("poke", "evo")).sets();
    var pop = service.findSets(ctx, new FindSetsRequest("poke", "POP")).sets();

    // assert
    assertThat(vividVoltage).contains(new CardSet(90, "Vivid Voltage"));
    assertThat(evolutions).contains(new CardSet(112, "Evolutions"));
    assertThat(pop).extracting(CardSet::id).contains(195, 196).doesNotHaveDuplicates().isSorted();
  }

  @Test
  void findSetsShouldReturnEmptyWhenPokemonCodeIsUnmapped() throws BadRequestException {
    // act
    var sets = service.findSets(ctx, new FindSetsRequest("poke", "zzz_nonexistent_set")).sets();

    // assert
    assertThat(sets).isEmpty();
  }

  @Test
  void findSetsShouldFindJapanesePokemonSetsWhenCodeHasMixedCase() throws BadRequestException {
    // act
    var sv2a = service.findSets(ctx, new FindSetsRequest("pkjp", "sV2A")).sets();
    var s8b = service.findSets(ctx, new FindSetsRequest("pkjp", "S8B")).sets();

    // assert
    assertThat(sv2a).contains(new CardSet(3413, "SV2a: Pokemon Card 151"));
    assertThat(s8b).contains(new CardSet(3856, "S8b: VMAX Climax"));
  }

  @Test
  void findSetsShouldKeepJapanesePokemonMappingsSeparateFromEnglish() throws BadRequestException {
    // act
    var japaneseOnly = service.findSets(ctx, new FindSetsRequest("pkjp", "sv2a")).sets();
    var englishOnly = service.findSets(ctx, new FindSetsRequest("poke", "sv2a")).sets();

    // assert
    assertThat(japaneseOnly).contains(new CardSet(3413, "SV2a: Pokemon Card 151"));
    assertThat(englishOnly).isEmpty();
  }

  @Test
  void findSetsShouldReturnEmptyWhenJapanesePokemonCodeIsUnmapped() throws BadRequestException {
    // act
    var sets = service.findSets(ctx, new FindSetsRequest("pkjp", "zzz_nonexistent_set")).sets();

    // assert
    assertThat(sets).isEmpty();
  }

  @Test
  void findSetsShouldRejectUnsupportedGame() {
    // act & assert
    assertThatThrownBy(() -> service.findSets(ctx, new FindSetsRequest("unsupported", "a25")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("game_id is not supported for set mapping");
  }
}
