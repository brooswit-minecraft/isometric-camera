package io.github.brooswitminecraft.isometriccamera;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Entry point for Isometric Camera. Registration-only; the F5 perspective and screen-relative
 * movement logic live client-side in {@link IsometricCameraClientEvents}.
 */
@Mod(IsometricCameraMod.MODID)
public class IsometricCameraMod {
    public static final String MODID = "isometriccamera";
    public static final Logger LOGGER = LogUtils.getLogger();

    public IsometricCameraMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Isometric Camera loaded");
    }
}
