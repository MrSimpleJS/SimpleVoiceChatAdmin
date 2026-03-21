package com.simple.voiceadmin;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public class VoiceChatAdminPlugin extends JavaPlugin {
  private VoiceChatAdminModule adminModule;
  private final Map<String, FileConfiguration> localeConfigs = new HashMap<>();

  @Override
  public void onEnable() {
    saveDefaultConfig();
    loadLocales();
    setupAdminModule();
  }

  @Override
  public void onDisable() {
    if (adminModule != null) {
      adminModule.shutdown();
      HandlerList.unregisterAll(adminModule);
      adminModule = null;
    }
  }

  public void debugLog(String message) {
    if (getConfig().getBoolean("debug", false) && message != null && !message.isBlank()) {
      getLogger().info("[debug] " + message);
    }
  }

  public void reloadVoiceAdmin() {
    reloadConfig();
    loadLocales();
    setupAdminModule();
  }

  public boolean hasLocale(String localeKey) {
    return localeConfigs.containsKey(normalizeLocale(localeKey));
  }

  public Set<String> getAvailableLocales() {
    return Collections.unmodifiableSet(localeConfigs.keySet());
  }

  public String getLocaleMessage(String localeKey, String path) {
    FileConfiguration config = localeConfigs.get(normalizeLocale(localeKey));
    if (config == null) {
      return null;
    }
    String value = config.getString(path);
    return value == null || value.isBlank() ? null : value;
  }

  private void setupAdminModule() {
    if (adminModule != null) {
      adminModule.shutdown();
      HandlerList.unregisterAll(adminModule);
    }
    adminModule = new VoiceChatAdminModule(this);
    adminModule.enable();
    Bukkit.getPluginManager().registerEvents(adminModule, this);
    register("adminjoin", true);
    register("broadcastvoice", false);
    register("vcmute", true);
    register("vcunmute", true);
    register("vclist", false);
    register("vcinfo", true);
    register("vckick", true);
    register("vcmove", true);
    register("vcmutelist", false);
    register("vchistory", true);
    register("vcwarn", true);
    register("vcnotes", true);
    register("vcpurgehistory", true);
    register("vcexport", true);
    register("vcmuteedit", true);
    register("vcmuteall", false);
    register("vcunmuteall", false);
    register("vclock", true);
    register("vcunlock", true);
    register("vccreate", true);
    register("vcdelete", true);
    register("vcpull", true);
    register("vcdisconnect", true);
    register("vcreload", false);
    register("vclisten", true);
    register("vcunlisten", false);
    register("vcspy", true);
    register("vcunspy", false);
  }

  private void register(String name, boolean tabCompleter) {
    PluginCommand command = getCommand(name);
    if (command == null || adminModule == null) {
      return;
    }
    command.setExecutor(adminModule);
    if (tabCompleter) {
      command.setTabCompleter(adminModule);
    }
  }

  private void loadLocales() {
    localeConfigs.clear();
    File localeFolder = new File(getDataFolder(), getLocaleFolderName());
    if (!localeFolder.exists() && !localeFolder.mkdirs()) {
      getLogger().warning("Could not create locale folder: " + localeFolder.getAbsolutePath());
      return;
    }
    saveBundledLocale("en");
    saveBundledLocale("de");
    saveBundledLocale("fr");
    File[] files = localeFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
    if (files == null) {
      return;
    }
    for (File file : files) {
      String name = file.getName();
      int dot = name.lastIndexOf('.');
      String localeKey = dot > 0 ? name.substring(0, dot) : name;
      String normalized = normalizeLocale(localeKey);
      if (normalized == null) {
        continue;
      }
      localeConfigs.put(normalized, YamlConfiguration.loadConfiguration(file));
    }
  }

  private void saveBundledLocale(String localeKey) {
    String resourcePath = getLocaleFolderName() + "/" + localeKey + ".yml";
    File target = new File(getDataFolder(), resourcePath);
    if (!target.exists()) {
      saveResource(resourcePath, false);
    }
  }

  private String getLocaleFolderName() {
    String configured = getConfig().getString("voicechat-admin.localization.folder", "locales");
    return configured == null || configured.isBlank() ? "locales" : configured.trim();
  }

  private String normalizeLocale(String localeKey) {
    if (localeKey == null || localeKey.isBlank()) {
      return null;
    }
    return localeKey.trim().replace('-', '_').toLowerCase(Locale.ROOT);
  }
}
