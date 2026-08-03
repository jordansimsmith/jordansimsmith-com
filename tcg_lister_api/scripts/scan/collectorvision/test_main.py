import numpy as np

from main import choose_best_hits, orient_portrait


def testChooseBestHitsShouldUseOrientationWithStrongestTopResult():
    # arrange
    upright = [(0.72, "upright-card"), (0.60, "other-card")]
    rotated = [(0.91, "rotated-card"), (0.65, "other-card")]

    # act
    selected = choose_best_hits([upright, rotated])

    # assert
    assert selected == rotated


def testOrientPortraitShouldRotateLandscapeImageClockwise():
    # arrange
    image = np.zeros((100, 200, 3), dtype=np.uint8)

    # act
    oriented = orient_portrait(image)

    # assert
    assert oriented.shape == (200, 100, 3)
