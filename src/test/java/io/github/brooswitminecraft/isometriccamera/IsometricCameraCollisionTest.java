package io.github.brooswitminecraft.isometriccamera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IsometricCameraCollisionTest {
    /** A flat world: solid ground at every Y strictly below this; open air at or above it. */
    private static IsometricCameraCollision.RayCast flatGroundAt(double groundY) {
        return (ox, oy, oz, ex, ey, ez) -> {
            if (oy < groundY) {
                // Mirrors real Minecraft: a ray-cast whose own origin is already inside a solid
                // block reports an immediate hit at that origin, before even considering the end
                // point.
                return new double[] {ox, oy, oz};
            }
            if (ey >= groundY) {
                return null;
            }
            double t = (oy - groundY) / (oy - ey);
            return new double[] {ox + (ex - ox) * t, groundY, oz + (ez - oz) * t};
        };
    }

    @Test
    void grantsTheFullRequestedDistanceWhenNothingIsInTheWay() {
        IsometricCameraCollision.RayCast clearSky = (ox, oy, oz, ex, ey, ez) -> null;
        float zoom = IsometricCameraCollision.resolveUnobstructedZoom(clearSky, 0, 65.62, 0, 0, -1, 0, 16.0f);
        assertEquals(16.0f, zoom, 0.0001f);
    }

    @Test
    void clampsToAnObstructionCloserThanTheRequestedDistance() {
        // A wall at world Z=5, straight ahead, should clamp a 16-block request down to ~5 (exactly
        // 5 at the unjittered center; the jittered corners land a hair over 5 since their hit still
        // lands on the X/Y-jittered ray but at the same wall plane).
        double wallZ = 5.0;
        IsometricCameraCollision.RayCast wallAtFive = (ox, oy, oz, ex, ey, ez) -> {
            if (oz <= wallZ && ez >= wallZ) {
                return new double[] {ox, oy, wallZ};
            }
            return null;
        };
        float zoom = IsometricCameraCollision.resolveUnobstructedZoom(wallAtFive, 0, 65.0, 0, 0, 0, -1, 16.0f);
        assertEquals(5.0f, zoom, 0.01f);
    }

    /**
     * Regression for the MINECRAFT-408 review finding: feet Y=64, eye Y=65.62 (vanilla's default
     * eye height), ground solid below Y=64. Calling this with the lowered focus anchor (eye - 4 =
     * 61.62, already inside solid ground) as the center collapses to near-zero, exactly reproducing
     * the reported ~0.166-block result; calling it with the eye itself (clear) instead grants the
     * full requested distance, since straight down from the eye never needs to cross the ground on
     * the way up and away from it.
     */
    @Test
    void loweredAnchorAsCenterCollapsesButEyeAsCenterDoesNotOnNormalGround() {
        double groundY = 64.0;
        double eyeY = 65.62;
        double loweredAnchorY = eyeY - 4.0;
        IsometricCameraCollision.RayCast groundAtPlayerFeet = flatGroundAt(groundY);

        float zoomFromLoweredAnchor =
                IsometricCameraCollision.resolveUnobstructedZoom(groundAtPlayerFeet, 0, loweredAnchorY, 0, 0, -1, 0, 16.0f);
        assertTrue(zoomFromLoweredAnchor < 1.0f, "expected the embedded-origin collapse this review reported");

        float zoomFromEye = IsometricCameraCollision.resolveUnobstructedZoom(groundAtPlayerFeet, 0, eyeY, 0, 0, -1, 0, 16.0f);
        assertEquals(16.0f, zoomFromEye, 0.0001f);
    }
}
