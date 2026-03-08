package city.zqdesigned.mc.stopmsgmod_forge;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod(StopMsgModForge.MODID)
public final class StopMsgModForge {

    public static final String MODID = "stopmsgmod_forge";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean DISCONNECT_SENT = new AtomicBoolean(false);
    private static final Component DEFAULT_REASON = Component.translatable("multiplayer.disconnect.server_shutdown");
    private static volatile Component lastReason = DEFAULT_REASON;

    public StopMsgModForge() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("stopmsg")
                .requires(source -> source.hasPermission(4))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                    .executes(context -> executeStopMessage(
                        context.getSource(),
                        StringArgumentType.getString(context, "message")
                    )))
        );
    }

    private int executeStopMessage(CommandSourceStack source, String rawMessage) {
        MinecraftServer server = source.getServer();
        String normalized = rawMessage.replace("\\n", "\n");
        Component reason = parseLegacyText(normalized);

        disconnectAllPlayers(server, reason);
        source.sendSuccess(() -> Component.literal("Stopping server..."), true);
        LOGGER.info("Server stop requested by {} with /stopmsg.", source.getDisplayName().getString());
        server.halt(false);
        return 1;
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        disconnectAllPlayers(event.getServer(), lastReason);
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        DISCONNECT_SENT.set(false);
        lastReason = DEFAULT_REASON;
    }

    private static void disconnectAllPlayers(MinecraftServer server, Component reason) {
        if (!DISCONNECT_SENT.compareAndSet(false, true)) {
            return;
        }
        lastReason = reason;

        List<ServerPlayer> players = List.copyOf(server.getPlayerList().getPlayers());
        for (ServerPlayer player : players) {
            player.connection.disconnect(reason);
        }
        LOGGER.info("Sent shutdown disconnect message to {} player(s).", players.size());
    }

    private static Component parseLegacyText(String input) {
        MutableComponent result = Component.empty();
        if (input.isEmpty()) {
            return result;
        }

        Style currentStyle = Style.EMPTY;
        StringBuilder plainText = new StringBuilder();

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if ((c == '&' || c == '\u00A7') && i + 1 < input.length()) {
                ChatFormatting formatting = ChatFormatting.getByCode(Character.toLowerCase(input.charAt(i + 1)));
                if (formatting != null) {
                    appendStyled(result, plainText, currentStyle);
                    currentStyle = applyFormatting(currentStyle, formatting);
                    i++;
                    continue;
                }
            }
            plainText.append(c);
        }

        appendStyled(result, plainText, currentStyle);
        return result;
    }

    private static void appendStyled(MutableComponent result, StringBuilder plainText, Style style) {
        if (plainText.length() == 0) {
            return;
        }
        result.append(Component.literal(plainText.toString()).setStyle(style));
        plainText.setLength(0);
    }

    private static Style applyFormatting(Style style, ChatFormatting formatting) {
        if (formatting == ChatFormatting.RESET) {
            return Style.EMPTY;
        }
        if (formatting.isColor()) {
            return Style.EMPTY.applyFormat(formatting);
        }
        return style.applyFormat(formatting);
    }
}
