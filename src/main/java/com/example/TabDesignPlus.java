package com.example;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import fr.mrmicky.fastboard.FastBoard;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;

public class TabDesignPlus extends JavaPlugin implements Listener {
    // Track players who have toggled off their scoreboard
    private final Set<UUID> scoreboardToggledOff = ConcurrentHashMap.newKeySet();
    private Map<String, String> customPlaceholders;
    private boolean tablistEnabled;
    private boolean debugMode;
    private final Map<UUID, Map<String, String>> scoreboardEntryMap = new ConcurrentHashMap<>();
    // Add FastBoard map
    private final Map<UUID, FastBoard> fastBoards = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> playerBossBars = new ConcurrentHashMap<>();
    private final Set<UUID> bossbarToggledOff = ConcurrentHashMap.newKeySet();
    private AnimationManager animationManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "animations.yml").exists()) {
            saveResource("animations.yml", false);
        }
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyTablist(player);
            if (!scoreboardToggledOff.contains(player.getUniqueId())) {
                applyScoreboard(player);
            } else {
                // Remove FastBoard if present
                FastBoard oldBoard = fastBoards.remove(player.getUniqueId());
                if (oldBoard != null) oldBoard.delete();
                player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
            }
            // Bossbar
            if (!bossbarToggledOff.contains(player.getUniqueId())) {
                applyBossBar(player);
            }
        }
        startScoreboardAutoRefresh();
        startBossBarAutoRefresh();
        loadTablistConfig();
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
                updateTablistName(event.getPlayer());
            }
        }, this);
        // Periodic refresh (every 3 seconds)
        Bukkit.getScheduler().runTaskTimer(this, this::updateAllTablistNames, 60L, 60L);
        animationManager = new AnimationManager(this);
        animationManager.addFrameChangeListener(() -> {
            Bukkit.getScheduler().runTask(this, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    applyTablist(player);
                    if (!scoreboardToggledOff.contains(player.getUniqueId())) {
                        applyScoreboard(player);
                    }
                }
            });
        });
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
            }
        });
    }

    @Override
    public void onDisable() {
        getLogger().info("[-] TabDesign+ has been disabled.");
        // Clean up FastBoard instances
        for (FastBoard board : fastBoards.values()) {
            board.delete();
        }
        fastBoards.clear();
        // Clean up bossbars
        for (BossBar bar : playerBossBars.values()) {
            bar.removeAll();
        }
        playerBossBars.clear();
        animationManager = null;
    }

    // Combine PlayerJoinEvent logic into a single listener
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        applyTablist(player);
        updateTablistName(player);
        if (!scoreboardToggledOff.contains(player.getUniqueId())) {
        applyScoreboard(player);
        } else {
            // Remove FastBoard if present
            FastBoard oldBoard = fastBoards.remove(player.getUniqueId());
            if (oldBoard != null) oldBoard.delete();
            player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
        }
        // Bossbar
        if (!bossbarToggledOff.contains(player.getUniqueId())) {
            applyBossBar(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // Remove scoreboard (FastBoard)
        FastBoard oldBoard = fastBoards.remove(player.getUniqueId());
        if (oldBoard != null) oldBoard.delete();
        removeBossBar(player);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        removeBossBar(player);
        if (!bossbarToggledOff.contains(player.getUniqueId())) {
            applyBossBar(player);
        }
    }

    // Converts all #RRGGBB and &#RRGGBB hex color codes to Minecraft legacy hex color codes (§x§R§R§G§G§B§B)
    public static String convertAllHexColors(String input) {
        if (input == null) return null;
        // Replace &#RRGGBB
        java.util.regex.Pattern hexPattern1 = java.util.regex.Pattern.compile("&#([A-Fa-f0-9]{6})");
        java.util.regex.Matcher matcher1 = hexPattern1.matcher(input);
        StringBuffer sb1 = new StringBuffer();
        while (matcher1.find()) {
            String hex = matcher1.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append('§').append(c);
            }
            matcher1.appendReplacement(sb1, replacement.toString());
        }
        matcher1.appendTail(sb1);
        String result = sb1.toString();
        // Replace #RRGGBB (not preceded by &)
        java.util.regex.Pattern hexPattern2 = java.util.regex.Pattern.compile("(?<!&)#([A-Fa-f0-9]{6})");
        java.util.regex.Matcher matcher2 = hexPattern2.matcher(result);
        StringBuffer sb2 = new StringBuffer();
        while (matcher2.find()) {
            String hex = matcher2.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append('§').append(c);
            }
            matcher2.appendReplacement(sb2, replacement.toString());
        }
        matcher2.appendTail(sb2);
        return sb2.toString();
    }

    // Utility method to parse legacy and hex color codes, then serialize with hex color support
    private String colorizeLine(String input) {
        if (input == null) return "";
        // Convert all #RRGGBB and &#RRGGBB to legacy hex codes
        String withLegacyHex = convertAllHexColors(input);
        // Parse & color codes (&c etc) as legacy ampersand color codes too
        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(withLegacyHex);
        // Serialize with hex support to ensure legacy and hex colors are correct
        return LegacyComponentSerializer.builder()
            .hexColors()
            .character('§')
            .build()
            .serialize(component);
    }

    // Helper to parse both legacy and MiniMessage color codes for tablist header/footer
    private Component parseTablistColor(String input) {
        if (input == null) return Component.empty();
        if (input.contains("&") || input.contains("§")) {
            return LegacyComponentSerializer.legacyAmpersand().deserialize(input.replace('§', '&'));
        }
        return MiniMessage.miniMessage().deserialize(input);
    }

    private void applyTablist(Player player) {
        // Support multi-line header/footer on tablist
        List<String> headerLines = getConfig().getStringList("tablist.header.lines");
        List<String> footerLines = getConfig().getStringList("tablist.footer.lines");
        String header;
        String footer;
        if (headerLines != null && !headerLines.isEmpty()) {
            header = String.join("\n", headerLines);
        } else {
            header = getConfig().getString("tablist.header", "<green>Welcome to the server!</green>");
        }
        if (footerLines != null && !footerLines.isEmpty()) {
            footer = String.join("\n", footerLines);
        } else {
            footer = getConfig().getString("tablist.footer", "<yellow>Enjoy your stay!</yellow>");
        }
        header = replaceAnimations(header);
        footer = replaceAnimations(footer);
        player.sendPlayerListHeaderAndFooter(
            parseTablistColor(header),
            parseTablistColor(footer)
        );
    }

    private void applyScoreboard(Player player) {
        if (!getConfig().getBoolean("scoreboard.enabled", true)) {
            // Remove FastBoard if present
            FastBoard oldBoard = fastBoards.remove(player.getUniqueId());
            if (oldBoard != null) oldBoard.delete();
            player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
            return;
        }
        String titleRaw = getConfig().getString("scoreboard.title", "<aqua>Server Info</aqua>");
        List<String> lines = new ArrayList<>(getConfig().getStringList("scoreboard.lines"));
        // Parse placeholders and colorize lines
        List<String> coloredLines = new ArrayList<>();
        for (String line : lines) {
            String displayLine = line;
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                displayLine = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, displayLine);
            }
            displayLine = replaceAnimations(displayLine);
            coloredLines.add(colorizeLine(displayLine));
        }
        // Remove old board if present
        FastBoard board = fastBoards.get(player.getUniqueId());
        if (board == null || board.isDeleted() || !board.getPlayer().equals(player)) {
            if (board != null) board.delete();
            board = new FastBoard(player);
            fastBoards.put(player.getUniqueId(), board);
        }
        board.updateTitle(colorizeLine(replaceAnimations(titleRaw)));
        board.updateLines(coloredLines);
    }

    private void applyBossBar(Player player) {
        String world = player.getWorld().getName();
        ConfigurationSection bossbarsSection = getConfig().getConfigurationSection("bossbars");
        if (bossbarsSection == null) return;
        ConfigurationSection barSection = bossbarsSection.getConfigurationSection(world);
        if (barSection == null || !barSection.getBoolean("enabled", false)) {
            removeBossBar(player);
            return;
        }
        String titleRaw = barSection.getString("title", "");
        String colorStr = barSection.getString("color", "PINK");
        String styleStr = barSection.getString("style", "SOLID");
        double progress = barSection.getDouble("progress", 1.0);
        // PlaceholderAPI
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            titleRaw = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, titleRaw);
        }
        titleRaw = replaceAnimations(titleRaw);
        // Only allow plain text or legacy color codes in bossbar titles
        String legacyTitle = titleRaw.replaceAll("<[^>]+>", ""); // Remove MiniMessage tags
        legacyTitle = legacyTitle.replaceAll("&#[A-Fa-f0-9]{6}", ""); // Remove hex codes
        legacyTitle = legacyTitle.replaceAll("§x(§[A-Fa-f0-9]){6}", ""); // Remove legacy hex codes
        // Allow & color codes, convert to §
        legacyTitle = legacyTitle.replace('&', '§');
        // Truncate to 64 chars (Minecraft bossbar limit)
        if (legacyTitle.length() > 64) legacyTitle = legacyTitle.substring(0, 64);
        // Warn if unsupported color codes are present
        if (titleRaw.contains("<") || titleRaw.contains("#")) {
            getLogger().warning("Bossbar title for world '" + world + "' contains unsupported color codes. Only plain text or legacy color codes (&a, &b, etc.) are supported.");
        }
        try {
            BarColor color = BarColor.valueOf(colorStr.toUpperCase());
            BarStyle style = BarStyle.valueOf(styleStr.toUpperCase());
            BossBar bar = playerBossBars.get(player.getUniqueId());
            if (bar == null) {
                bar = Bukkit.createBossBar(legacyTitle, color, style);
                playerBossBars.put(player.getUniqueId(), bar);
            } else {
                bar.setTitle(legacyTitle);
                bar.setColor(color);
                bar.setStyle(style);
            }
            bar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
            if (!bar.getPlayers().contains(player)) {
                bar.addPlayer(player);
            }
        } catch (Exception e) {
            getLogger().warning("Failed to create/update bossbar for player " + player.getName() + ": " + e.getMessage());
            removeBossBar(player);
        }
    }

    private void removeBossBar(Player player) {
        BossBar bar = playerBossBars.remove(player.getUniqueId());
        if (bar != null) {
            bar.removeAll();
        }
    }

    private void startBossBarAutoRefresh() {
        int refreshIntervalTicks = 20 * 3; // 3 seconds
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!bossbarToggledOff.contains(player.getUniqueId())) {
                    applyBossBar(player);
                } else {
                    removeBossBar(player);
                }
            }
        }, refreshIntervalTicks, refreshIntervalTicks);
    }

    // Helper: safe substring for legacy strings
    private String safeSubstring(String str, int start, int end) {
        if (str == null) return "";
        if (start >= str.length()) return "";
        return str.substring(start, Math.min(end, str.length()));
    }

    // Helper: get last color codes from a legacy string
    private String getLastColors(String input) {
        return org.bukkit.ChatColor.getLastColors(input);
    }

    // Helper: fix color codes at the start of a segment
    private String fixColors(String segment, String lastColors) {
        if (segment == null || segment.isEmpty()) return segment;
        // Only prepend if not already colored
        if (!segment.startsWith("§") && !lastColors.isEmpty()) {
            return lastColors + segment;
        }
        return segment;
    }

    // Replace %anim:animationname% with current frame, and reset color after each animation to prevent color bleed
    private String replaceAnimations(String input) {
        if (input == null) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("%anim:([a-zA-Z0-9_\\-]+)%");
        java.util.regex.Matcher matcher = pattern.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String anim = matcher.group(1);
            String frame = animationManager != null ? animationManager.getCurrentFrame(anim) : matcher.group(0);
            // Insert color reset after the animation frame to prevent color bleed
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(frame + "§r"));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("tdp")) {
            return false;
        }
        if (args.length == 0) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("★ <aqua>TabDesignPlus Commands:</aqua>"));
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<white>/tdp reload</white> - <gray>Reload the plugin configuration</gray>"));
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<white>/tdp toggle</white> - <gray>Toggle your scoreboard</gray>"));
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<white>/tdp bartoggle</white> - <gray>Toggle your bossbar</gray>"));
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<white>/tdp tabtoggle</white> - <gray>Toggle your tablist</gray>"));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("tabdesignplus.reload")) {
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>✖ You do not have permission to use this command! ✖</red>"));
                return true;
            }
            this.reloadConfig();
            loadTablistConfig();
            // Unregister and re-register the expansion to reflect new config
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                // tdpExpansion = new TDPExpansion(this); // This line is removed
            }
            if (animationManager != null) animationManager.loadAnimations();
            updateAllTablistNames();
            // Bossbar reload
            for (Player player : Bukkit.getOnlinePlayers()) {
                removeBossBar(player);
                if (!bossbarToggledOff.contains(player.getUniqueId())) {
                    applyBossBar(player);
                }
            }
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>TabDesignPlus config reloaded!</green>"));
            return true;
        }
        if (args[0].equalsIgnoreCase("toggle") && sender instanceof Player) {
            Player player = (Player) sender;
            UUID uuid = player.getUniqueId();
            if (scoreboardToggledOff.contains(uuid)) {
                scoreboardToggledOff.remove(uuid);
                applyScoreboard(player);
                player.sendMessage(MiniMessage.miniMessage().deserialize("<green>Scoreboard enabled! ✔</green>"));
            } else {
                scoreboardToggledOff.add(uuid);
                // Remove FastBoard if present
                FastBoard oldBoard = fastBoards.remove(uuid);
                if (oldBoard != null) oldBoard.delete();
                player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
                player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Scoreboard disabled! ✖</red>"));
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("bartoggle") && sender instanceof Player) {
            Player player = (Player) sender;
            UUID uuid = player.getUniqueId();
            if (bossbarToggledOff.contains(uuid)) {
                bossbarToggledOff.remove(uuid);
                applyBossBar(player);
                player.sendMessage(MiniMessage.miniMessage().deserialize("<green>Bossbar enabled! ✔</green>"));
            } else {
                bossbarToggledOff.add(uuid);
                removeBossBar(player);
                player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Bossbar disabled! ✖</red>"));
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("tabtoggle") && sender instanceof Player) {
            Player player = (Player) sender;
            UUID uuid = player.getUniqueId();
            if (tablistEnabled) {
                tablistEnabled = false;
                player.setPlayerListName(player.getName());
                player.setPlayerListHeaderFooter("", "");
                player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Tablist disabled! ✖</red>"));
            } else {
                tablistEnabled = true;
                applyTablist(player);
                updateTablistName(player);
                player.sendMessage(MiniMessage.miniMessage().deserialize("<green>Tablist enabled! ✔</green>"));
            }
            return true;
        }
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<lime>Usage: /tdp reload | /tdp toggle | /tdp bartoggle | /tdp tabtoggle</lime>"));
        return true;
    }

    private void startScoreboardAutoRefresh() {
        int refreshIntervalTicks = 20 * 3; // Scoreboard refreshes automatically after 3 seconds.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!scoreboardToggledOff.contains(player.getUniqueId())) {
                    applyScoreboard(player);
                } else {
                    // Remove FastBoard if present
                    FastBoard oldBoard = fastBoards.remove(player.getUniqueId());
                    if (oldBoard != null) oldBoard.delete();
                    player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
                }
                // Refresh tablist header/footer and name for all players
                applyTablist(player);
                updateTablistName(player);
            }
        }, refreshIntervalTicks, refreshIntervalTicks);
        // If scoreboard still doesn't show, check config (scoreboard.enabled) and /tabtoggle status.
    }

    private void loadTablistConfig() {
        FileConfiguration config = this.getConfig();
        tablistEnabled = config.getBoolean("tablist.enabled", true);
        debugMode = config.getBoolean("debug", false);
        // Remove tablistRankOrder and groupPlaceholder loading
        // Load custom placeholders
        customPlaceholders = new HashMap<>();
        ConfigurationSection phSection = config.getConfigurationSection("placeholders");
        if (phSection != null) {
            for (String key : phSection.getKeys(false)) {
                customPlaceholders.put(key, phSection.getString(key, ""));
            }
        }
    }

    // Debug log utility
    private void logDebug(String type, String details) {
        if (!debugMode) return;
        try {
            File debugDir = new File(getDataFolder(), "DebugLogs");
            if (!debugDir.exists()) debugDir.mkdirs();
            String date = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            File logFile = new File(debugDir, "Crash_Log_" + date + ".log");
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.write("[" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "] [" + type + "] " + details + "\n");
            }
        } catch (IOException e) {
            getLogger().warning("Failed to write debug log: " + e.getMessage());
        }
    }

    // Remove getPlayerRank and getPlayerRankInternal
    // Remove getSortedPlayers and updateAllTablistNames logic that sorts by rank
    // In updateAllTablistNames, just iterate Bukkit.getOnlinePlayers() and call updateTablistName
    private void updateAllTablistNames() {
        if (!tablistEnabled) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.setPlayerListName(player.getName());
                player.setPlayerListHeaderFooter("", "");
            }
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateTablistName(player);
        }
    }

    // Set tablist name for a single player
    private void updateTablistName(Player player) {
        String format = getConfig().getString("tablist.name-format", "%luckperms_prefix%");
        String display = format;
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            display = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, format);
        }
        display = replaceAnimations(display);
        String legacy = colorizeLine(display + player.getName());
        if (legacy.length() > 32) legacy = legacy.substring(0, 32);
        player.setPlayerListName(legacy);
    }

    // Remove updateAllNametags method and all calls to it

    // Example usage in config loading
    private void loadConfigKeyOrLog(ConfigurationSection section, String key) {
        if (section == null || !section.contains(key)) {
            logDebug("Config Error", "Missing config key: '" + key + "' in section '" + (section != null ? section.getName() : "null") + "'");
        }
    }

    // Remove updateSelfHologram method and all calls
} 