package io.github.brooswitminecraft.isometriccamera;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IsometricCameraFocusAnchorTest {
    @Test
    void leavesAnchorUnchangedWhenIsometricIsNotActive() {
        assertEquals(64.0, IsometricCameraFocusAnchor.resolveY(false, 64.0), 0.0001);
        assertEquals(1.62, IsometricCameraFocusAnchor.resolveY(false, 1.62), 0.0001);
    }

    @Test
    void lowersAnchorByExactlyFourBlocksWhileIsometricIsActive() {
        assertEquals(60.0, IsometricCameraFocusAnchor.resolveY(true, 64.0), 0.0001);
    }

    @Test
    void offsetTracksWhateverTheCurrentEyeAnchorIsRatherThanAFixedWorldHeight() {
        // The offset must follow the player's interpolated eye position (which moves every frame
        // the player moves), not snap to a fixed world Y, so the lowered anchor tracks smoothly.
        assertEquals(10.0, IsometricCameraFocusAnchor.resolveY(true, 14.0), 0.0001);
        assertEquals(96.5, IsometricCameraFocusAnchor.resolveY(true, 100.5), 0.0001);
    }

    @Test
    void offsetIsNegativeFourWithNoXZComponentImplied() {
        assertEquals(-4.0, IsometricCameraFocusAnchor.FOCUS_Y_OFFSET_BLOCKS, 0.0001);
    }
}
