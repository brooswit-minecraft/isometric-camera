package io.github.brooswitminecraft.isometriccamera;

/**
 * Pure logic for lowering the isometric camera's focus/look-at anchor (MINECRAFT-408), independent
 * of any Minecraft class so it can be unit tested without the game running.
 *
 * <p>Vanilla's detached (third-person) camera pivots around the player's interpolated eye position
 * -- see {@code Camera#setup}, which sets the camera's position to that eye anchor before moving it
 * back along the view direction by the (possibly isometric-widened, see {@link
 * IsometricCameraDistance}) detached distance, with the wall/terrain collision ray-cast run from
 * that same pivot. There is no NeoForge event that exposes or lets a mod replace that pivot before
 * the move/ray-cast happen, so {@link IsometricCameraClientEvents#placeCameraAroundLoweredAnchor}
 * instead takes over that entire computation itself and reflectively overwrites the camera's
 * position with the result, in the same {@code CalculateDetachedCameraDistanceEvent} handler and
 * before vanilla's own move/ray-cast step would otherwise run later in that same {@code
 * Camera#setup} call -- which is why the resulting position ends up measured from the lowered
 * anchor rather than the player's eye, exactly as the ticket requires, with no mixin needed. (The
 * collision ray-cast itself is still run from the eye, not this lowered anchor -- see {@link
 * IsometricCameraCollision} for why.)
 */
public final class IsometricCameraFocusAnchor {
    /** Y offset (blocks) applied to the eye anchor while isometric is active; no X/Z offset. */
    static final double FOCUS_Y_OFFSET_BLOCKS = -4.0;

    private IsometricCameraFocusAnchor() {}

    /**
     * @param isometricActive whether the isometric phase is currently active
     * @param anchorY the Y coordinate of the eye anchor vanilla's camera would otherwise pivot
     *     around
     * @return {@code anchorY} shifted by {@link #FOCUS_Y_OFFSET_BLOCKS} while isometric is active,
     *     unchanged otherwise
     */
    public static double resolveY(boolean isometricActive, double anchorY) {
        return isometricActive ? anchorY + FOCUS_Y_OFFSET_BLOCKS : anchorY;
    }
}
