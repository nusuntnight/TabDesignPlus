package com.example;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class TDPExpansion extends PlaceholderExpansion {
    private final TabDesignPlus plugin;

    public TDPExpansion(TabDesignPlus plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "tdp";
    }

    @Override
    public @NotNull String getAuthor() {
        return "TabDesignPlus";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0";
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null) return "";
        if (params.equalsIgnoreCase("prefix")) {
            String rank = plugin.getPlayerRank(player);
            return plugin.getTablistPrefixes().getOrDefault(rank, "");
        }
        return null;
    }
}