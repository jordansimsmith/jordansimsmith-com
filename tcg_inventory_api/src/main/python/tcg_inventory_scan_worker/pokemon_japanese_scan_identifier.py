"""Scan identification for Japanese Pokémon."""

from .pokemon_scan_identifier import PokemonScanIdentifier


class PokemonJapaneseScanIdentifier(PokemonScanIdentifier):
    game = "pokemon_jp"
    _catalog_game = "pokemon-japan"
    _pinned_version = 20
