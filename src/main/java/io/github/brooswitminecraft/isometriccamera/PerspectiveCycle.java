package io.github.brooswitminecraft.isometriccamera;

/**
 * Pure F5 cycle state machine, independent of any Minecraft class so it can be unit tested without
 * the game running. FIRST_PERSON/THIRD_PERSON_BACK/THIRD_PERSON_FRONT mirror vanilla's own three
 * {@code CameraType} values and order; ISOMETRIC is the extra perspective this mod adds after them,
 * wrapping back to FIRST_PERSON same as vanilla's own cycle wraps.
 */
public final class PerspectiveCycle {
    private PerspectiveCycle() {}

    public enum Phase {
        FIRST_PERSON,
        THIRD_PERSON_BACK,
        THIRD_PERSON_FRONT,
        ISOMETRIC
    }

    public static Phase next(Phase current) {
        return switch (current) {
            case FIRST_PERSON -> Phase.THIRD_PERSON_BACK;
            case THIRD_PERSON_BACK -> Phase.THIRD_PERSON_FRONT;
            case THIRD_PERSON_FRONT -> Phase.ISOMETRIC;
            case ISOMETRIC -> Phase.FIRST_PERSON;
        };
    }
}
