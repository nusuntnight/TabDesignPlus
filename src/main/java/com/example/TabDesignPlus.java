package com.example;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scoreboard.Team;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;

public class TabDesignPlus extends JavaPlugin implements Listener {
    // Track players who have toggled off their scoreboard
    private final Set<UUID> scoreboardToggledOff = ConcurrentHashMap.newKeySet();
    private List<String> tablistRankOrder;
    private Map<String, String> tablistPrefixes;
    private Map<String, String> customPlaceholders;
    private String groupPlaceholder;
    private TDPExpansion tdpExpansion;
    private boolean tablistEnabled;
    private boolean debugMode;
    private ProtocolManager protocolManager;
    private final Map<UUID, Map<String, String>> scoreboardEntryMap = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyTablist(player);
            if (!scoreboardToggledOff.contains(player.getUniqueId())) {
                applyScoreboard(player);
            } else {
                player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
            }
        }
        startScoreboardAutoRefresh();
        loadTablistConfig();
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
                updateTablistName(event.getPlayer());
            }
        }, this);
        // Periodic refresh (every 3 seconds)
        Bukkit.getScheduler().runTaskTimer(this, this::updateAllTablistNames, 60L, 60L);
        // Register custom PlaceholderAPI expansion for %tdp_prefix%
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            tdpExpansion = new TDPExpansion(this);
            tdpExpansion.register();
        }
        protocolManager = ProtocolLibrary.getProtocolManager();
        // Register a single, safe ProtocolLib listener for SCOREBOARD_SCORE (no hide-numbers logic)
        // Remove ProtocolLib scoreboard line injection logic (not needed for this approach)
    }

    @Override
    public void onDisable() {
        getLogger().info("[-] TabDesign+ has been disabled.");
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
            player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
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
        // Support multi-line header/footer like scoreboard
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
        player.sendPlayerListHeaderAndFooter(
            parseTablistColor(header),
            parseTablistColor(footer)
        );
    }

    private void applyScoreboard(Player player) {
        if (!getConfig().getBoolean("scoreboard.enabled", true)) {
            player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
            return;
        }
        String titleRaw = getConfig().getString("scoreboard.title", "<aqua>Server Info</aqua>");
        List<String> lines = new ArrayList<>(getConfig().getStringList("scoreboard.lines"));
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        String legacyTitle = colorizeLine(titleRaw);
        Objective objective = scoreboard.registerNewObjective("tabdesignplus", "dummy", legacyTitle);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        int score = lines.size();
        int blankCount = 0;
        Set<String> usedEntries = new HashSet<>();
        int entryIndex = 1;
        for (String line : lines) {
            String displayLine = line;
            if (displayLine.trim().isEmpty()) {
                // Make each blank line unique using invisible color codes
                displayLine = " "; // Suffix will be a space, entry will be invisible
                blankCount++;
            }
            // Scoreboard lines max out at 15; skipping score 0 avoids scoreboard error
            if (score == 0) break;
            // Parse placeholders as before
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                displayLine = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, displayLine);
            }
            String colored = colorizeLine(displayLine);
            // Use a unique, invisible entry for every line (e.g., §r§0, §r§1, ...)
            String entry = "§r§" + Integer.toHexString(entryIndex);
            while (usedEntries.contains(entry)) {
                entryIndex++;
                entry = "§r§" + Integer.toHexString(entryIndex);
            }
            usedEntries.add(entry);
            // Register team for this entry
            String teamName = "tdp" + entryIndex;
            Team team = scoreboard.getTeam(teamName);
            if (team == null) team = scoreboard.registerNewTeam(teamName);
            team.getEntries().forEach(team::removeEntry); // Clear old entries
            team.addEntry(entry);
            // Set the colored text as the suffix (supports hex and legacy)
            team.setSuffix(colored.isEmpty() ? " " : colored);
            objective.getScore(entry).setScore(score);
            score--;
            entryIndex++;
        }
        player.setScoreboard(scoreboard);
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

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("tab") && args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            this.reloadConfig();
            loadTablistConfig();
            // Unregister and re-register the expansion to reflect new config
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                if (tdpExpansion != null) {
                    tdpExpansion.unregister();
                }
                tdpExpansion = new TDPExpansion(this);
                tdpExpansion.register();
            }
            updateAllTablistNames();
            sender.sendMessage(MiniMessage.miniMessage().deserialize("TabDesignPlus config reloaded!"));
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("tabtoggle") && sender instanceof Player) {
            Player player = (Player) sender;
            UUID uuid = player.getUniqueId();
            if (scoreboardToggledOff.contains(uuid)) {
                scoreboardToggledOff.remove(uuid);
                applyScoreboard(player);
                player.sendMessage(MiniMessage.miniMessage().deserialize("<green>Scoreboard enabled!</green>"));
            } else {
                scoreboardToggledOff.add(uuid);
                player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
                player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Scoreboard disabled!</red>"));
            }
            return true;
        }
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>Usage: /" + label + " reload | tabtoggle</yellow>"));
        return true;
    }

    private void startScoreboardAutoRefresh() {
        int refreshIntervalTicks = 20 * 3; // Scoreboard refreshes automatically after 3 seconds.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!scoreboardToggledOff.contains(player.getUniqueId())) {
                    applyScoreboard(player);
                }
                // Also refresh tablist for all players
                applyTablist(player);
            }
        }, refreshIntervalTicks, refreshIntervalTicks);
    }

    private void loadTablistConfig() {
        FileConfiguration config = this.getConfig();
        tablistEnabled = config.getBoolean("tablist.enabled", true);
        debugMode = config.getBoolean("debug", false);
        // Load tablist rank order and prefixes from config
        tablistRankOrder = config.getStringList("tablist.rank-order");
        tablistPrefixes = new HashMap<>();
        ConfigurationSection prefixSection = config.getConfigurationSection("tablist.prefixes");
        if (prefixSection != null) {
            for (String key : prefixSection.getKeys(false)) {
                tablistPrefixes.put(key, prefixSection.getString(key, ""));
            }
        }
        // Load custom placeholders
        customPlaceholders = new HashMap<>();
        ConfigurationSection phSection = config.getConfigurationSection("placeholders");
        if (phSection != null) {
            for (String key : phSection.getKeys(false)) {
                customPlaceholders.put(key, phSection.getString(key, ""));
            }
        }
        groupPlaceholder = this.getConfig().getString("tablist.group-placeholder", "%vault_group%");
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

    // Utility to get a player's rank using PlaceholderAPI and group-placeholder
    public String getPlayerRank(Player player) {
        String rank = getPlayerRankInternal(player);
        if (!tablistPrefixes.containsKey(rank)) {
            logDebug("Missing Prefix", "Prefix for rank '" + rank + "' not found in config for player " + player.getName());
        }
        return rank;
    }

    private String getPlayerRankInternal(Player player) {
        String group = groupPlaceholder;
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            group = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, groupPlaceholder);
        }
        if (group != null) {
            group = group.toLowerCase();
            for (String rank : tablistRankOrder) {
                if (rank.equalsIgnoreCase(group)) {
                    return rank;
                }
            }
        }
        return tablistRankOrder.isEmpty() ? "member" : tablistRankOrder.get(tablistRankOrder.size() - 1);
    }

    // Sort players by rank order, then by name
    private List<Player> getSortedPlayers() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort(Comparator.comparingInt((Player p) -> tablistRankOrder.indexOf(getPlayerRank(p))).thenComparing(Player::getName));
        return players;
    }

    // Set tablist name for all online players, sorted by rank
    private void updateAllTablistNames() {
        if (!tablistEnabled) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.setPlayerListName(player.getName());
                player.setPlayerListHeaderFooter("", "");
            }
            return;
        }
        List<Player> sorted = getSortedPlayers();
        for (Player player : sorted) {
            updateTablistName(player);
        }
    }

    // Set tablist name for a single player
    private void updateTablistName(Player player) {
        String format = "%tdp_prefix% " + player.getName();
        String display = format;
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            display = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, format);
        }
        String legacy = colorizeLine(display);
        if (legacy.length() > 32) legacy = legacy.substring(0, 32);
        player.setPlayerListName(legacy);
    }

    public List<String> getTablistRankOrder() { return tablistRankOrder; }
    public Map<String, String> getTablistPrefixes() { return tablistPrefixes; }

    // Example usage in config loading
    private void loadConfigKeyOrLog(ConfigurationSection section, String key) {
        if (section == null || !section.contains(key)) {
            logDebug("Config Error", "Missing config key: '" + key + "' in section '" + (section != null ? section.getName() : "null") + "'");
        }
    }
} 