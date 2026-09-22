package dev.everview;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(value = Everview.MOD_ID, dist = Dist.CLIENT)
public final class Everview {
    public static final String MOD_ID = "everview";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Everview(IEventBus modBus, ModContainer container) {
        LOGGER.info("Everview {} bootstrapping", "0.0.1-alpha");
    }
}
