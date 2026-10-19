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
    void scalesVanillaDefaultToExactlySixteenBlocksWhileIsometricIsActive() {
        float widened = IsometricCameraDistance.resolve(true, VANILLA_DEFAULT_DISTANCE);
        assertEquals(16.0f, widened, 0.0001f);
    }

    @Test
    void raisesThePreviousTwelveBlockActiveResultToTheNewSixteenBlockRequest() {
        // MINECRAFT-405's shipped active result at the vanilla default was 12.0f; MINECRAFT-408
        // requires exactly 16.0f at that same default, not another approximate increase.
        float previousActiveResult = 12.0f;
        float widened = IsometricCameraDistance.resolve(true, VANILLA_DEFAULT_DISTANCE);
        assertEquals(previousActiveResult + 4.0f, widened, 0.0001f);
    }

    @Test
    void scalesWhateverDistanceIsRequestedRatherThanReplacingIt() {
        // A future zoom/config feature changing the pre-isometric distance should still be widened
        // by the same multiplier, not clobbered by a hardcoded isometric-only constant.
        assertEquals(32.0f, IsometricCameraDistance.resolve(true, 8.0f), 0.0001f);
    }
}
