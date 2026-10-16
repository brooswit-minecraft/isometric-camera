package io.github.brooswitminecraft.isometriccamera;

/**
 * Pure math for screen-relative WASD, independent of any Minecraft class so it can be unit tested
 * without the game running.
 *
 * <p>Vanilla rotates the raw strafe/forward impulse by the player's own current yaw once per tick
 * inside {@code LivingEntity}'s movement step (after this mod's {@code MovementInputUpdateEvent}
 * handler runs, same tick): {@code x = strafe*cos(yaw) - forward*sin(yaw)},
 * {@code z = forward*cos(yaw) + strafe*sin(yaw)}. Mouse-look can change that yaw at any point
 * before vanilla consumes it, including within the same tick this mod runs in — a correctness
 * issue a prior revision of this mod got wrong by instead trying to reset the player's yaw once per
 * tick in {@code ClientTickEvent.Post}, which runs too late to affect that tick's own movement and
 * leaves frames between ticks showing the un-reset yaw.
 *
 * <p>This fixes it without depending on timing at all: given whatever the player's current yaw
 * happens to be right now, it computes the strafe/forward pair that — once vanilla applies its own
 * rotation by that same current yaw — reproduces exactly the pair vanilla would have applied at the
 * fixed isometric yaw instead. Pre-rotating by {@code fixedYaw - currentYaw} and letting vanilla's
 * own {@code +currentYaw} rotation cancel it out is algebraically exact for any currentYaw, so there
 * is no race to get right: {@code R(currentYaw) * rotateToFixedYaw(v, currentYaw, fixedYaw) ==
 * R(fixedYaw) * v} for the same rotation {@code R} vanilla itself uses.
 */
public final class ScreenRelativeMovement {
    private ScreenRelativeMovement() {}

    public record Impulse(float leftImpulse, float forwardImpulse) {}

    public static Impulse rotateToFixedYaw(float leftImpulse, float forwardImpulse, float currentYawDegrees, float fixedYawDegrees) {
        double delta = Math.toRadians(fixedYawDegrees - currentYawDegrees);
        double sin = Math.sin(delta);
        double cos = Math.cos(delta);
        float newLeft = (float) (leftImpulse * cos - forwardImpulse * sin);
        float newForward = (float) (forwardImpulse * cos + leftImpulse * sin);
        return new Impulse(newLeft, newForward);
    }
}
