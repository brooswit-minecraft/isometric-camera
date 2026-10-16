package io.github.brooswitminecraft.isometriccamera;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

import io.github.brooswitminecraft.isometriccamera.PerspectiveCycle.Phase;

/**
 * Adds a fourth F5 perspective after vanilla's three: a fixed-angle, Dungeons-like isometric view
 * with screen-relative WASD.
 *
 * <p>Feasibility check (see MINECRAFT-376): a true orthographic projection needs a custom camera
 * projection matrix, which NeoForge does not expose an event for without a mixin. This mod stays
 * mixin-free (matching every sibling mod in this org) and instead reuses vanilla's existing
 * third-person-back camera at a fixed pitch/yaw — "isometric-like" rather than literally
 * orthographic, which is good enough for this MVP.
 *
 * <p>The vanilla F5 keybind ({@code options.keyTogglePerspective}) only cycles three
 * {@code CameraType} values and does not know about this mod's extra phase, so every press of that
 * physical key is intercepted here ({@link #onKey}) and the vanilla handling is canceled; this
 * class manages the full four-phase cycle itself and applies vanilla {@code CameraType} values for
 * the first three phases, so leaving the isometric phase restores normal perspectives/movement
 * exactly as before this mod loaded.
 *
 * <p>Screen-relative movement needs no separate input remapping: while the isometric phase is
 * active, the player's own yaw/pitch is pinned to the same fixed angle the camera uses
 * ({@link #onClientTick}), overwriting whatever the mouse did that tick. Vanilla's forward/strafe
 * movement is always relative to the player's yaw, and since that yaw never changes while
 * isometric, W/A/S/D always move the same fixed world directions -- which, because the camera is
 * pinned to that same yaw, read on screen as up/down/left/right exactly as the ticket asks for.
 * A side effect documented here rather than hidden: free mouse-look is unavailable while isometric
 * (any in-tick mouse delta is overwritten before the next frame renders), by design -- Minecraft
 * Dungeons has no mouse-look either.
 */
@EventBusSubscriber(modid = IsometricCameraMod.MODID, value = Dist.CLIENT)
public final class IsometricCameraClientEvents {
    /** Looking down from the north-east corner toward south-west, a common isometric orientation. */
    static final float ISOMETRIC_YAW = 225.0f;
    /** Steep enough to read as top-down, shallow enough to still see character/terrain silhouettes. */
    static final float ISOMETRIC_PITCH = 55.0f;

    private static Phase phase = Phase.FIRST_PERSON;

    private IsometricCameraClientEvents() {}

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft minecraft = Minecraft.getInstance();
        int togglePerspectiveKey = minecraft.options.keyTogglePerspective.getKey().getValue();
        if (event.getKey() != togglePerspectiveKey || togglePerspectiveKey == GLFW.GLFW_KEY_UNKNOWN) {
            return;
        }
        // Always cancel: vanilla's own 3-phase cycle must never run alongside this mod's 4-phase
        // one, or a single F5 press would advance both and desync them.
        event.setCanceled(true);
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        phase = PerspectiveCycle.next(phase);
        applyPhase(minecraft, phase);
    }

    private static void applyPhase(Minecraft minecraft, Phase phase) {
        CameraType cameraType =
                switch (phase) {
                    case FIRST_PERSON -> CameraType.FIRST_PERSON;
                    case THIRD_PERSON_BACK -> CameraType.THIRD_PERSON_BACK;
                    case THIRD_PERSON_FRONT -> CameraType.THIRD_PERSON_FRONT;
                    // Isometric reuses the back camera's own positioning logic; what makes it
                    // isometric is the fixed yaw/pitch applied every tick in onClientTick below.
                    case ISOMETRIC -> CameraType.THIRD_PERSON_BACK;
                };
        minecraft.options.setCameraType(cameraType);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (phase != Phase.ISOMETRIC) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            phase = Phase.FIRST_PERSON;
            return;
        }
        player.setYRot(ISOMETRIC_YAW);
        player.setYHeadRot(ISOMETRIC_YAW);
        player.setXRot(ISOMETRIC_PITCH);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // Don't carry an active isometric phase into the next world/session; the next F5 press
        // there should start a fresh, predictable cycle from vanilla's own first phase.
        if (phase == Phase.ISOMETRIC) {
            Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
        }
        phase = Phase.FIRST_PERSON;
    }
}
