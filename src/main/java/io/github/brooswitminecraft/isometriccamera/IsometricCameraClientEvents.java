package io.github.brooswitminecraft.isometriccamera;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

import io.github.brooswitminecraft.isometriccamera.IsometricPhaseTracker.Decision;
import io.github.brooswitminecraft.isometriccamera.IsometricPhaseTracker.VanillaPerspective;
import io.github.brooswitminecraft.isometriccamera.ScreenRelativeMovement.Impulse;

/**
 * Adds a fourth F5 perspective after vanilla's three: a fixed-angle, Dungeons-like isometric view
 * with screen-relative WASD.
 *
 * <p>Feasibility check (see MINECRAFT-376): a true orthographic projection needs a custom camera
 * projection matrix, which NeoForge does not expose an event for without a mixin. This mod stays
 * mixin-free (matching every sibling mod in this org) and instead reuses vanilla's existing
 * third-person-back camera at a fixed pitch/yaw -- "isometric-like" rather than literally
 * orthographic, which is good enough for this MVP.
 *
 * <p>The vanilla F5 keybind cycles only three {@code CameraType} values, and NeoForge 1.21.1's
 * {@code InputEvent.Key} is explicitly not cancellable (checked against the
 * {@code neoforged/NeoForge} {@code 1.21.1} branch source), so this mod cannot suppress vanilla's
 * own handling before it runs. Instead {@link #onClientTick} observes, once per tick after
 * vanilla's own {@code handleKeybinds()} already ran, how the vanilla camera type changed and
 * reinterprets one specific transition as "enter isometric instead of wrapping to first person" --
 * see {@link IsometricPhaseTracker} for the pure decision logic.
 *
 * <p><b>Camera and movement are kept frame/tick-accurate independently of the player's actual
 * yaw/pitch</b> -- an earlier revision instead overwrote the player's own yaw/pitch once per tick,
 * which a review correctly rejected: vanilla's mouse-look can still change that yaw at any other
 * point (including between render frames, since ticks run at a fixed 20 Hz but frames don't), so
 * resetting it only once per tick left both rendering and movement able to observe a mouse-skewed
 * yaw before the next reset. Neither hook below depends on the player's yaw being anything in
 * particular:
 * <ul>
 * <li>{@link #onComputeCameraAngles} fires every render frame (not just every tick) and overrides
 *     the camera's rendered yaw/pitch directly, so no frame can show a mouse-rotated camera while
 *     isometric is active.</li>
 * <li>{@link #onMovementInputUpdate} fires once per tick, before vanilla rotates the raw
 *     strafe/forward impulse by the player's current yaw. Rather than trying to keep that yaw fixed
 *     (racy), it pre-rotates the impulse by whatever the current yaw actually is right now, so
 *     vanilla's own later rotation reproduces the fixed-isometric-yaw result exactly, for any yaw --
 *     see {@link ScreenRelativeMovement} for the math and why it has no timing dependency at
 *     all.</li>
 * <li>{@link #onCalculateDetachedCameraDistance} (MINECRAFT-402) widens the default framing by
 *     scaling vanilla's own pre-collision camera distance request -- see
 *     {@link IsometricCameraDistance} for why scaling rather than replacing it keeps this
 *     compatible with the collision ray-cast NeoForge runs after this event, and with any future
 *     zoom/config feature.</li>
 * </ul>
 * The player's own entity yaw/pitch (and therefore third-person model facing) is left untouched by
 * design -- freely mouse-steerable, same as it would be in vanilla third person; the ticket asks for
 * a fixed camera and screen-relative movement, not character-facing changes.
 */
@EventBusSubscriber(modid = IsometricCameraMod.MODID, value = Dist.CLIENT)
public final class IsometricCameraClientEvents {
    /** Looking down from the north-east corner toward south-west, a common isometric orientation. */
    static final float ISOMETRIC_YAW = 225.0f;
    /** Steep enough to read as top-down, shallow enough to still see character/terrain silhouettes. */
    static final float ISOMETRIC_PITCH = 55.0f;

    private static boolean isometricActive;
    private static VanillaPerspective trackedType = VanillaPerspective.FIRST_PERSON;

    private IsometricCameraClientEvents() {}

    private static VanillaPerspective toPerspective(CameraType cameraType) {
        return switch (cameraType) {
            case FIRST_PERSON -> VanillaPerspective.FIRST_PERSON;
            case THIRD_PERSON_BACK -> VanillaPerspective.THIRD_PERSON_BACK;
            case THIRD_PERSON_FRONT -> VanillaPerspective.THIRD_PERSON_FRONT;
        };
    }

    private static CameraType toCameraType(VanillaPerspective perspective) {
        return switch (perspective) {
            case FIRST_PERSON -> CameraType.FIRST_PERSON;
            case THIRD_PERSON_BACK -> CameraType.THIRD_PERSON_BACK;
            case THIRD_PERSON_FRONT -> CameraType.THIRD_PERSON_FRONT;
        };
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            isometricActive = false;
            trackedType = VanillaPerspective.FIRST_PERSON;
            return;
        }

        VanillaPerspective observed = toPerspective(minecraft.options.getCameraType());
        Decision decision = IsometricPhaseTracker.update(isometricActive, trackedType, observed);
        isometricActive = decision.isometricActive();
        trackedType = decision.cameraType();
        minecraft.options.setCameraType(toCameraType(trackedType));
    }

    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!isometricActive) {
            return;
        }
        event.setYaw(ISOMETRIC_YAW);
        event.setPitch(ISOMETRIC_PITCH);
        event.setRoll(0.0f);
    }

    @SubscribeEvent
    public static void onMovementInputUpdate(MovementInputUpdateEvent event) {
        if (!isometricActive) {
            return;
        }
        Input input = event.getInput();
        float currentYaw = event.getEntity().getYRot();
        Impulse remapped = ScreenRelativeMovement.rotateToFixedYaw(input.leftImpulse, input.forwardImpulse, currentYaw, ISOMETRIC_YAW);
        input.leftImpulse = remapped.leftImpulse();
        input.forwardImpulse = remapped.forwardImpulse();
    }

    @SubscribeEvent
    public static void onCalculateDetachedCameraDistance(CalculateDetachedCameraDistanceEvent event) {
        event.setDistance(IsometricCameraDistance.resolve(isometricActive, event.getDistance()));
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // Don't carry an active isometric phase into the next world/session; the next F5 press
        // there should start a fresh, predictable cycle from vanilla's own first phase.
        if (isometricActive) {
            Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
        }
        isometricActive = false;
        trackedType = VanillaPerspective.FIRST_PERSON;
    }
}
