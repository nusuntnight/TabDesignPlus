package com.example;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AnimationManager {
    private final Map<String, Animation> animations = new ConcurrentHashMap<>();
    private final Map<String, Integer> frameIndexes = new ConcurrentHashMap<>();
    private final Map<String, Long> lastUpdate = new ConcurrentHashMap<>();
    private final Plugin plugin;
    private final List<Runnable> frameChangeListeners = new ArrayList<>();

    public AnimationManager(Plugin plugin) {
        this.plugin = plugin;
        loadAnimations();
        startAnimationTask();
    }

    public void loadAnimations() {
        animations.clear();
        frameIndexes.clear();
        lastUpdate.clear();
        File file = new File(plugin.getDataFolder(), "animations.yml");
        if (!file.exists()) {
            plugin.getLogger().warning("animations.yml not found in plugin data folder!");
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            int interval = yaml.getInt(key + ".change-interval", 50);
            List<String> frames = yaml.getStringList(key + ".texts");
            if (!frames.isEmpty()) {
                animations.put(key.toLowerCase(), new Animation(key, interval, frames));
                frameIndexes.put(key.toLowerCase(), 0);
                lastUpdate.put(key.toLowerCase(), System.currentTimeMillis());
            }
        }
    }

    public String getCurrentFrame(String name) {
        Animation anim = animations.get(name.toLowerCase());
        if (anim == null) return "%anim:" + name + "%";
        int idx = frameIndexes.getOrDefault(name.toLowerCase(), 0);
        List<String> frames = anim.getFrames();
        if (frames.isEmpty()) return "";
        return frames.get(idx % frames.size());
    }

    public void addFrameChangeListener(Runnable listener) {
        frameChangeListeners.add(listener);
    }

    private void startAnimationTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            boolean anyFrameChanged = false;
            for (Map.Entry<String, Animation> entry : animations.entrySet()) {
                String name = entry.getKey();
                Animation anim = entry.getValue();
                long last = lastUpdate.getOrDefault(name, 0L);
                if (now - last >= anim.getInterval()) {
                    int idx = (frameIndexes.getOrDefault(name, 0) + 1) % anim.getFrames().size();
                    frameIndexes.put(name, idx);
                    lastUpdate.put(name, now);
                    anyFrameChanged = true;
                }
            }
            if (anyFrameChanged) {
                for (Runnable listener : frameChangeListeners) {
                    try {
                        listener.run();
                    } catch (Exception e) {
                        plugin.getLogger().warning("Animation frame change listener error: " + e.getMessage());
                    }
                }
            }
        }, 1L, 1L);
    }

    public static class Animation {
        private final String name;
        private final int interval;
        private final List<String> frames;
        public Animation(String name, int interval, List<String> frames) {
            this.name = name;
            this.interval = interval;
            this.frames = frames;
        }
        public int getInterval() { return interval; }
        public List<String> getFrames() { return frames; }
        public String getName() { return name; }
    }
} 