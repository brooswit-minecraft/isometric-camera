package io.github.brooswitminecraft.isometriccamera;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ScreenRelativeMovementTest {
    private static final float EPSILON = 1.0e-4f;

    /** Vanilla's own rotation, mirrored here so a test can simulate "what travel() would do". */
    private static float[] vanillaRotate(float leftImpulse, float forwardImpulse, float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        float sin = (float) Math.sin(yaw);
        float cos = (float) Math.cos(yaw);
        float x = leftImpulse * cos - forwardImpulse * sin;
        float z = forwardImpulse * cos + leftImpulse * sin;
        return new float[] {x, z};
    }

    @Test
    void identityWhenCurrentYawAlreadyMatchesFixedYaw() {
        var result = ScreenRelativeMovement.rotateToFixedYaw(1.0f, 0.5f, 225.0f, 225.0f);
        assertEquals(1.0f, result.leftImpulse(), EPSILON);
        assertEquals(0.5f, result.forwardImpulse(), EPSILON);
    }

    @Test
    void wCancelsOutAnyCurrentYawToReproduceTheFixedYawWorldDirection() {
        float fixedYaw = 225.0f;
        for (float currentYaw : new float[] {0.0f, 37.0f, 90.0f, 181.0f, 310.0f, -45.0f}) {
            var remapped = ScreenRelativeMovement.rotateToFixedYaw(0.0f, 1.0f, currentYaw, fixedYaw);
            float[] appliedByVanilla = vanillaRotate(remapped.leftImpulse(), remapped.forwardImpulse(), currentYaw);
            float[] expectedAtFixedYaw = vanillaRotate(0.0f, 1.0f, fixedYaw);
            assertEquals(expectedAtFixedYaw[0], appliedByVanilla[0], EPSILON, "currentYaw=" + currentYaw);
            assertEquals(expectedAtFixedYaw[1], appliedByVanilla[1], EPSILON, "currentYaw=" + currentYaw);
        }
    }

    @Test
    void everyWasdDirectionReproducesItsFixedYawWorldDirectionUnderAnArbitraryCurrentYaw() {
        float fixedYaw = 225.0f;
        float currentYaw = 73.0f; // arbitrary, simulating mouse-look having moved the player's yaw
        // (leftImpulse, forwardImpulse) for S, A, D, W as vanilla's own Input.tick() would set them.
        float[][] wasd = {{0.0f, -1.0f}, {-1.0f, 0.0f}, {1.0f, 0.0f}, {0.0f, 1.0f}};
        for (float[] raw : wasd) {
            var remapped = ScreenRelativeMovement.rotateToFixedYaw(raw[0], raw[1], currentYaw, fixedYaw);
            float[] appliedByVanilla = vanillaRotate(remapped.leftImpulse(), remapped.forwardImpulse(), currentYaw);
            float[] expectedAtFixedYaw = vanillaRotate(raw[0], raw[1], fixedYaw);
            assertEquals(expectedAtFixedYaw[0], appliedByVanilla[0], EPSILON);
            assertEquals(expectedAtFixedYaw[1], appliedByVanilla[1], EPSILON);
        }
    }
}
