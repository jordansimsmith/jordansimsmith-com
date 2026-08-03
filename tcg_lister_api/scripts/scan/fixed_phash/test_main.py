from PIL import Image, ImageDraw

from main import crop_fixture_card, perceptual_hash, rank_candidates


def testCropFixtureCardShouldExtractDarkCardFromWhiteTray():
    # arrange
    image = Image.new("RGB", (300, 400), "white")
    draw = ImageDraw.Draw(image)
    draw.rectangle((70, 175, 230, 385), fill="black")
    draw.rectangle((78, 183, 222, 377), fill="gray")

    # act
    crop = crop_fixture_card(image)

    # assert
    assert crop.size == (146, 204)
    assert crop.convert("L").getextrema()[0] < 100


def testPerceptualHashShouldBeStableForEquivalentImages():
    # arrange
    image = Image.linear_gradient("L").resize((146, 204))
    equivalent = image.resize((292, 408)).resize((146, 204))

    # act
    first_hash = perceptual_hash(image)
    second_hash = perceptual_hash(equivalent)

    # assert
    assert (first_hash ^ second_hash).bit_count() <= 2


def testRankCandidatesShouldUseDistanceAndDeduplicatePrintings():
    # arrange
    references = [
        {"scryfall_id": "id-a", "hash": 0b0001},
        {"scryfall_id": "id-b", "hash": 0b0111},
        {"scryfall_id": "id-a", "hash": 0b0011},
    ]

    # act
    ranked = rank_candidates(0, references, top_k=2)

    # assert
    assert [candidate["scryfall_id"] for candidate in ranked] == ["id-a", "id-b"]
    assert ranked[0]["distance"] == 1
    assert ranked[0]["score"] == 1 - 1 / 64
