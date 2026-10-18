package io.github.brooswitminecraft.isometriccamera;

/**
 * Pure logic for widening the isometric camera's default framing (MINECRAFT-402), independent of
 * any Minecraft class so it can be unit tested without the game running.
 *
 * <p>Vanilla's detached (third-person) camera distance request -- 4.0 blocks by default, before the
 * {@code NeoForge} {@code CalculateDetachedCameraDistanceEvent} hook and the subsequent
 * wall/terrain collision ray-cast both apply -- is scaled rather than replaced, so any user-facing
 * zoom/config feature added later still composes: this only widens whatever distance vanilla (or a
 * future zoom option) would otherwise have requested, and the existing collision ray-cast still runs
 * afterward exactly as it does for vanilla third person, so occlusion/clipping behavior near walls,
 * roofs and terrain is unchanged.
 */
public final class IsometricCameraDistance {
    /** Modest initial increase; John specified no exact block distance or multiplier. */
    static final float ISOMETRIC_DISTANCE_MULTIPLIER = 1.5f;

    private IsometricCameraDistance() {}

    /**
     * @param isometricActive whether the isometric phase is currently active
     * @param requestedDistance the pre-collision detached camera distance vanilla (or another mod)
     *     would otherwise use
     * @return {@code requestedDistance} scaled by {@link #ISOMETRIC_DISTANCE_MULTIPLIER} while
     *     isometric is active, unchanged otherwise
     */
    public static float resolve(boolean isometricActive, float requestedDistance) {
        return isometricActive ? requestedDistance * ISOMETRIC_DISTANCE_MULTIPLIER : requestedDistance;
    }
}
