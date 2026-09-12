package eu.pb4.simpleimagerenderer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;


public class ModInit implements ClientModInitializer {
    public static final Logger LOGGER = LogManager.getLogger("SimpleImageRenderer");
    public static final String ID = "simple-image-renderer";
    public static final Path MAIN_PATH = FabricLoader.getInstance().getGameDir().relativize(FabricLoader.getInstance().getGameDir()).resolve("simple-image-renderer");
    public static boolean useIdAsName = false;

    @Override
    public void onInitializeClient() {
        try {
            Files.createDirectories(MAIN_PATH);
        } catch (Throwable e) {
        }
        ClientCommandRegistrationCallback.EVENT.register(ModCommands::registerCommands);
    }
}
