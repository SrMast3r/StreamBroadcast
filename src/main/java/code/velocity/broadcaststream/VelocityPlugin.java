package code.velocity.broadcaststream;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.*;
import com.velocitypowered.api.proxy.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Plugin(
        id = "streambroadcast",
        name = "StreamBroadcast",
        description = "Live broadcast link",
        version = "1.0.0",
        authors = {"SrMast3r_"}
)
public class VelocityPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Map<Player, Instant> lastUsage = new HashMap<>();
    private Duration cooldownDuration;
    private Map<String, String> messages;
    private List<String> commandAliases;

    @Inject
    public VelocityPlugin(ProxyServer server, Logger logger, PluginContainer pluginContainer) {
        this.server = server;
        this.logger = logger;

        // Define a more explicit configuration directory path
        Path configDirectory = Paths.get("plugins", "StreamBroadcast");
        try {
            Files.createDirectories(configDirectory); // Ensure the directory exists
        } catch (IOException e) {
            logger.error("Could not create configuration directory", e);
        }

        // Load messages and command aliases from the configuration file
        File configFile = configDirectory.resolve("config.yml").toFile();
        if (!configFile.exists()) {
            createDefaultConfig(configFile);
        }
        Map<String, Object> config = loadConfig(configFile);
        if (config == null) {
            logger.warn("Could not load configuration from config.yml.");
            messages = getDefaultMessages();
            commandAliases = List.of("directo", "live", "stream");
            cooldownDuration = Duration.ofMinutes(10);
        } else {
            messages = (Map<String, String>) config.getOrDefault("messages", getDefaultMessages());
            commandAliases = (List<String>) ((Map<String, Object>) config.get("commands")).getOrDefault("aliases", List.of("directo", "live", "stream"));
            int cooldownSeconds = (int) config.getOrDefault("cooldown", 600);
            cooldownDuration = Duration.ofSeconds(cooldownSeconds);
        }
    }

    private void createDefaultConfig(File configFile) {
        Yaml yaml = new Yaml();
        Map<String, Object> defaultConfig = new HashMap<>();
        defaultConfig.put("messages", getDefaultMessages());
        defaultConfig.put("commands", Map.of("aliases", List.of("directo", "live", "stream")));
        defaultConfig.put("cooldown", 600); // 10 minutos por defecto
        try (FileWriter writer = new FileWriter(configFile)) {
            yaml.dump(defaultConfig, writer);
        } catch (IOException e) {
            logger.error("Error creating the default configuration file.", e);
        }
    }

    private Map<String, Object> loadConfig(File configFile) {
        Yaml yaml = new Yaml();
        try {
            return yaml.load(Files.newBufferedReader(configFile.toPath()));
        } catch (IOException e) {
            logger.error("Error loading configuration file.", e);
            return null;
        }
    }

    private Map<String, String> getDefaultMessages() {
        Map<String, String> defaultMessages = new HashMap<>();
        defaultMessages.put("invalidCommand", "<red>Incorrect command usage. You must specify a valid link.");
        defaultMessages.put("cooldownMessage", "<red>You must wait 10 minutes before using this command again.");
        defaultMessages.put("invalidLink", "<red>The link provided is not valid. Please use a link from Twitch, YouTube, Facebook, TikTok or other streaming platform.");
        defaultMessages.put("announcementFormat", "<#8bf723> ☄ %s <white>is now live");
        defaultMessages.put("space", "");
        defaultMessages.put("linkPrefix", "<reset><white>%s");
        return defaultMessages;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        SimpleCommand broadcastCommand = new SimpleCommand() {
            @Override
            public void execute(Invocation invocation) {
                CommandSource source = invocation.source();
                if (!(source instanceof Player)) {
                    source.sendMessage(MiniMessage.miniMessage().deserialize(centerText(messages.get("invalidCommand"))));
                    return;
                }

                Player player = (Player) source;
                Instant now = Instant.now();
                Instant lastUsed = lastUsage.getOrDefault(player, Instant.EPOCH);

                if (Duration.between(lastUsed, now).compareTo(cooldownDuration) < 0) {
                    player.sendMessage(MiniMessage.miniMessage().deserialize(centerText(messages.get("cooldownMessage"))));
                    return;
                }

                lastUsage.put(player, now);

                String[] args = invocation.arguments();
                if (args.length != 1) {
                    player.sendMessage(MiniMessage.miniMessage().deserialize(centerText(messages.get("invalidCommand"))));
                    return;
                }

                String streamLink = args[0];
                if (!isValidStreamLink(streamLink)) {
                    player.sendMessage(MiniMessage.miniMessage().deserialize(centerText(messages.get("invalidLink"))));
                    return;
                }

                String announcement = String.format(messages.get("announcementFormat"), player.getUsername());
                String centeredLink = String.format(messages.get("linkPrefix"), streamLink);

                Component spaceComponent = MiniMessage.miniMessage().deserialize(centerText(messages.get("space")));
                Component announcementComponent = MiniMessage.miniMessage().deserialize(centerText(announcement));
                Component linkComponent = MiniMessage.miniMessage().deserialize(centerText(centeredLink))
                        .clickEvent(ClickEvent.openUrl(streamLink));

                for (Player p : server.getAllPlayers()) {
                    p.sendMessage(spaceComponent);
                    p.sendMessage(announcementComponent);
                    p.sendMessage(linkComponent);
                    p.sendMessage(spaceComponent);
                }
            }

            @Override
            public boolean hasPermission(Invocation invocation) {
                return invocation.source().hasPermission("livebroadcast.use");
            }
        };

        for (String alias : commandAliases) {
            server.getCommandManager().register(alias, broadcastCommand);
        }
    }

    private boolean isValidStreamLink(String link) {
        try {
            URL url = new URL(link);
            String host = url.getHost().toLowerCase();
            return host.endsWith("twitch.tv") || host.endsWith("youtube.com")
                    || host.endsWith("facebook.com") || host.endsWith("kick.com");
        } catch (MalformedURLException e) {
            return false;
        }
    }

    private String centerText(String message) {
        int chatWidth = 70; // Ancho aproximado del chat
        int messageWidth = message.length();
        int padding = (chatWidth - messageWidth) / 2;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < padding; i++) {
            sb.append(" ");
        }
        sb.append(message);
        return sb.toString();
    }
}
