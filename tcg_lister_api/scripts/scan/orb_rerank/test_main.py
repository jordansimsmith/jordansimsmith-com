import cv2
import numpy as np
from PIL import Image, ImageDraw

from main import crop_fixture_card, orb_match_score, perceptual_hash, phash_shortlist


def testCropFixtureCardShouldExtractCardFromFixture():
    # arrange
    image = Image.new("RGB", (300, 400), "white")
    draw = ImageDraw.Draw(image)
    draw.rectangle((70, 175, 230, 385), fill="black")
    draw.rectangle((78, 183, 222, 377), fill="gray")

    # act
    crop = crop_fixture_card(image)

    # assert
    assert crop.size == (600, 840)


def testPhashShortlistShouldRankClosestHashFirst():
    # arrange
    references = [
        {"scryfall_id": "id-a", "hash": 0b0001},
        {"scryfall_id": "id-b", "hash": 0b1111},
    ]

    # act
    shortlisted = phash_shortlist(0, references, limit=2)

    # assert
    assert [candidate["scryfall_id"] for candidate in shortlisted] == [
        "id-a",
        "id-b",
    ]


def testOrbMatchScoreShouldPreferSameLocalFeatures():
    # arrange
    query = np.zeros((240, 360), dtype=np.uint8)
    cv2.putText(query, "MAGIC", (30, 110), cv2.FONT_HERSHEY_SIMPLEX, 2, 255, 4)
    cv2.circle(query, (270, 150), 45, 255, 4)
    similar = np.roll(query, 4, axis=1)
    unrelated = np.zeros_like(query)
    cv2.rectangle(unrelated, (40, 40), (320, 200), 255, -1)

    # act
    similar_score = orb_match_score(query, similar)
    unrelated_score = orb_match_score(query, unrelated)

    # assert
    assert similar_score > unrelated_score
    assert perceptual_hash(Image.fromarray(query)) == perceptual_hash(
        Image.fromarray(query.copy())
    )
