"""Pinned CollectorVision source, catalog, and model metadata."""

COLLECTORVISION_COMMIT = "2a122d00d25c8d112a90e47bf235a021e0c53b0c"
COLLECTORVISION_SOURCE_URL = "https://github.com/HanClinto/CollectorVision"
COLLECTORVISION_LICENSE_URL = "https://raw.githubusercontent.com/HanClinto/CollectorVision/{}/LICENSE".format(COLLECTORVISION_COMMIT)
COLLECTORVISION_LICENSE_SHA256 = "0d96a4ff68ad6d4b6f1f30f713b18d5184912ba8dd389f86aa7710db079abcb0"

CATALOGS = {
    "scryfall/mtg": struct(
        public_name = "scryfall-mtg",
        version = 40,
        rows = 113031,
        source_updated_at = "2026-09-21T09:05:30.034Z",
        descriptor = {
            "game": "magic-the-gathering",
            "source": "scryfall",
            "profile": "default",
            "description": "English-first paper printings from Scryfall default cards.",
            "result_identifier": "scryfall_card",
            "recommended": True,
        },
        assets = {
            "records": struct(
                filename = "records.jsonl.gz",
                repository = "collectorvision_magic_the_gathering_records",
                url = "https://hanclinto.github.io/CollectorVisionCatalog/catalog-v2/scryfall-mtg/version/40/base/records.jsonl.gz",
                size = 9043401,
                sha256 = "cd566e2d103c532c9e79644e152496a53494a9b04b3cd1a43c5baf3e8589bff9",
            ),
            "embeddings": struct(
                filename = "embeddings.f16.gz",
                repository = "collectorvision_magic_the_gathering_embeddings",
                url = "https://hanclinto.github.io/CollectorVisionCatalog/catalog-v2/scryfall-mtg/version/40/base/embeddings.f16.gz",
                size = 26451329,
                sha256 = "bbe2eab30e2db5d1c2811c2429cecb9435a6d537319bd82c521733407becdd40",
            ),
        },
    ),
    "tcgplayer/pokemon": struct(
        public_name = "tcgplayer-pokemon",
        version = 60,
        rows = 27289,
        source_updated_at = "2026-11-03T20:06:12Z",
        descriptor = {
            "game": "pokemon",
            "source": "tcgplayer",
            "profile": "printings",
            "description": "TCGplayer marketplace printing catalog.",
            "result_identifier": "tcgplayer_product",
            "recommended": True,
        },
        assets = {
            "records": struct(
                filename = "records.jsonl.gz",
                repository = "collectorvision_pokemon_records",
                url = "https://hanclinto.github.io/CollectorVisionCatalog/catalog-v2/tcgplayer-pokemon/version/60/base/records.jsonl.gz",
                size = 434053,
                sha256 = "aba6b240d61ad48bdb458f9c5128fd22cf6aa1764575902430cb8eb85d9c5676",
            ),
            "embeddings": struct(
                filename = "embeddings.f16.gz",
                repository = "collectorvision_pokemon_embeddings",
                url = "https://hanclinto.github.io/CollectorVisionCatalog/catalog-v2/tcgplayer-pokemon/version/60/base/embeddings.f16.gz",
                size = 6406132,
                sha256 = "cac33b11bac50b57759ec0cdc5963167fe54ddced55b3011b4c23f152ac08736",
            ),
        },
    ),
    "tcgplayer/pokemon-japan": struct(
        public_name = "tcgplayer-pokemon-japan",
        version = 20,
        rows = 27638,
        source_updated_at = "2026-10-22T20:06:32Z",
        descriptor = {
            "game": "pokemon-japan",
            "source": "tcgplayer",
            "profile": "printings",
            "description": "Japanese TCGplayer marketplace printing catalog.",
            "result_identifier": "tcgplayer_product",
            "recommended": True,
        },
        assets = {
            "records": struct(
                filename = "records.jsonl.gz",
                repository = "collectorvision_pokemon_japan_records",
                url = "https://hanclinto.github.io/CollectorVisionCatalog/catalog-v2/tcgplayer-pokemon-japan/version/20/base/records.jsonl.gz",
                size = 401650,
                sha256 = "7da886358e3be5443937933b731247e87c4348a3c2d2dbf448d352eb703ca36e",
            ),
            "embeddings": struct(
                filename = "embeddings.f16.gz",
                repository = "collectorvision_pokemon_japan_embeddings",
                url = "https://hanclinto.github.io/CollectorVisionCatalog/catalog-v2/tcgplayer-pokemon-japan/version/20/base/embeddings.f16.gz",
                size = 6518790,
                sha256 = "c6e83b67ae06642f68509fd96655c13ac4e5040cccca63c29e9665f2b15e274d",
            ),
        },
    ),
}

MILO_URL = "https://raw.githubusercontent.com/HanClinto/CollectorVision/{}/collector_vision/weights/milo.onnx".format(COLLECTORVISION_COMMIT)
MILO_SHA256 = "bd13d8d60383c69da04dce261f32e93fdaeaa8fd618fbc991e7385f71b3d45df"
MILO_SIZE = 5191100

EMBEDDING = {
    "model": "collectorvision@9d45a37ebfe40f22ece70507015645de134dc3ec:milo-1.0.0@sha256:{}".format(MILO_SHA256),
    "dimensions": 128,
    "dtype": "float16",
    "byte_order": "little",
    "layout": "row-major",
}
