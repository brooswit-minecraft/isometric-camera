package io.github.brooswitminecraft.isometriccamera;

/**
 * Pure decision logic for inserting a 4th, isometric phase into vanilla's F5 cycle, independent of
 * any Minecraft class so it can be unit tested without the game running.
 *
 * <p>NeoForge 1.21.1's {@code InputEvent.Key} is explicitly not cancellable (confirmed against the
 * {@code neoforged/NeoForge} {@code 1.21.1} branch source), so vanilla's own F5 handling in
 * {@code Minecraft.handleKeybinds()} can't be suppressed before it runs. Instead this mod observes,
 * once per tick, how vanilla's own {@code CameraType} field changed since the previous tick and
 * reinterprets exactly one transition: the wrap from third-person-front back to first-person (the
 * tell-tale sign of a 4th F5 press, since nothing else produces that specific edge) becomes "enter
 * isometric" instead. A later F5 press exits the same way vanilla's own next cycle step would have
 * moved the camera type away from what this mod pinned it to.
 */
public final class IsometricPhaseTracker {
    private IsometricPhaseTracker() {}

    /** Mirrors vanilla {@code CameraType}'s three values without depending on the Minecraft class. */
    public enum VanillaPerspective {
        FIRST_PERSON,
        THIRD_PERSON_BACK,
        THIRD_PERSON_FRONT
    }

    /**
     * @param isometricActive whether the isometric phase should be active after this tick
     * @param cameraType the vanilla camera type that should be in effect after this tick (while
     *     isometric is active, this mod always pins it to {@code THIRD_PERSON_BACK} so the player
     *     model renders; the isometric look itself comes from the fixed camera angle, not from this
     *     field)
     */
    public record Decision(boolean isometricActive, VanillaPerspective cameraType) {}

    /**
     * @param wasIsometricActive whether isometric was active entering this tick
     * @param trackedType the camera type this mod itself set at the end of the previous tick (or
     *     the last value observed, if isometric was never entered)
     * @param observedType the camera type actually read from vanilla's options this tick, after
     *     vanilla's own {@code handleKeybinds()} has already run
     */
    public static Decision update(boolean wasIsometricActive, VanillaPerspective trackedType, VanillaPerspective observedType) {
        if (wasIsometricActive) {
            if (observedType != trackedType) {
                // Only this mod's own override leaves the vanilla field on THIRD_PERSON_BACK while
                // isometric is active; any other value here means vanilla's handleKeybinds changed
                // it, i.e. the player pressed F5 again to leave the isometric phase.
                return new Decision(false, VanillaPerspective.FIRST_PERSON);
            }
            return new Decision(true, trackedType);
        }
        if (trackedType == VanillaPerspective.THIRD_PERSON_FRONT && observedType == VanillaPerspective.FIRST_PERSON) {
            return new Decision(true, VanillaPerspective.THIRD_PERSON_BACK);
        }
        return new Decision(false, observedType);
    }
}
