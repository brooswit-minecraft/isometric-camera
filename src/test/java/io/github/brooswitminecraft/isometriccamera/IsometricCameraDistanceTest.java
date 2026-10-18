package io.github.brooswitminecraft.isometriccamera;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IsometricCameraDistanceTest {
    private static final float VANILLA_DEFAULT_DISTANCE = 4.0f;

    @Test
    void leavesDistanceUnchangedWhenIsometricIsNotActive() {
        assertEquals(VANILLA_DEFAULT_DISTANCE, IsometricCameraDistance.resolve(false, VANILLA_DEFAULT_DISTANCE));
        assertEquals(10.0f, IsometricCameraDistance.resolve(false, 10.0f));
    }

    @Test
    void scalesVanillaDefaultByApproximatelyOneAndAHalfWhileIsometricIsActive() {
        float widened = IsometricCameraDistance.resolve(true, VANILLA_DEFAULT_DISTANCE);
        assertEquals(6.0f, widened, 0.0001f);
    }

    @Test
    void scalesWhateverDistanceIsRequestedRatherThanReplacingIt() {
        // A future zoom/config feature changing the pre-isometric distance should still be widened
        // by the same multiplier, not clobbered by a hardcoded isometric-only constant.
        assertEquals(12.0f, IsometricCameraDistance.resolve(true, 8.0f), 0.0001f);
    }
}
