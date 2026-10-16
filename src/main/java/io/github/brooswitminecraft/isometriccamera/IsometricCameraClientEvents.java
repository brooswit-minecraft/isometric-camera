package io.github.brooswitminecraft.isometriccamera;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import io.github.brooswitminecraft.isometriccamera.IsometricPhaseTracker.Decision;
import io.github.brooswitminecraft.isometriccamera.IsometricPhaseTracker.VanillaPerspective;

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
 * see {@link IsometricPhaseTracker} for the pure decision logic and why that transition is
 * unambiguous.
 *
 * <p>Screen-relative movement needs no separate input remapping: while the isometric phase is
 * active, the player's own yaw/pitch is pinned to the same fixed angle the camera uses, overwriting
 * whatever the mouse did that tick. Vanilla's forward/strafe movement is always relative to the
 * player's yaw, and since that yaw never changes while isometric, W/A/S/D always move the same
 * fixed world directions -- which, because the camera is pinned to that same yaw, read on screen as
 * up/down/left/right exactly as the ticket asks for. A side effect documented here rather than
 * hidden: free mouse-look is unavailable while isometric (any in-tick mouse delta is overwritten
 * before the next frame renders), by design -- Minecraft Dungeons has no mouse-look either.
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

        if (isometricActive) {
            LocalPlayer player = minecraft.player;
            player.setYRot(ISOMETRIC_YAW);
            player.setYHeadRot(ISOMETRIC_YAW);
            player.setXRot(ISOMETRIC_PITCH);
        }
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
