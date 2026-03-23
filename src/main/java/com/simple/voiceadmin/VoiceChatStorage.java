package com.simple.voiceadmin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class VoiceChatStorage {
  private static final int CURRENT_SCHEMA_VERSION = 2;
  private final VoiceChatAdminPlugin plugin;
  private final File databaseFile;
  private volatile boolean closed;
  private volatile String lastError = "";
  private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor(r -> {
    Thread thread = new Thread(r, "SimpleVoiceChatAdmin-SQLite");
    thread.setDaemon(true);
    return thread;
  });

  public VoiceChatStorage(VoiceChatAdminPlugin plugin) {
    this.plugin = plugin;
    String configured = plugin.getConfig().getString("voicechat-admin.storage.file", "voicechat-admin.db");
    String fileName = configured == null || configured.isBlank() ? "voicechat-admin.db" : configured.trim();
    this.databaseFile = new File(plugin.getDataFolder(), fileName);
  }

  public boolean initialize() {
    if (!databaseFile.getParentFile().exists()) {
      databaseFile.getParentFile().mkdirs();
    }
    try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
      int currentVersion = getSchemaVersion(connection);
      for (int version = currentVersion + 1; version <= CURRENT_SCHEMA_VERSION; version++) {
        migrate(connection, version);
        setSchemaVersion(connection, version);
      }
      lastError = "";
      return true;
    } catch (Exception ex) {
      lastError = ex.getMessage();
      plugin.getLogger().warning("Failed to initialize SQLite storage: " + ex.getMessage());
      return false;
    }
  }

  public void shutdown() {
    closed = true;
    writeExecutor.shutdown();
    try {
      if (!writeExecutor.awaitTermination(5L, TimeUnit.SECONDS)) {
        writeExecutor.shutdownNow();
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      writeExecutor.shutdownNow();
    }
  }

  public long loadAuditSequence() {
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
      statement.setString(1, "audit_sequence");
      try (ResultSet resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          return Long.parseLong(resultSet.getString("value"));
        }
      }
    } catch (Exception ex) {
      plugin.getLogger().warning("Failed to load audit sequence: " + ex.getMessage());
    }
    return 0L;
  }

  public String getLastError() {
    return lastError;
  }

  public void saveAuditSequence(long value) {
    submitWrite("save meta value", connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "INSERT INTO meta(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
        statement.setString(1, "audit_sequence");
        statement.setString(2, String.valueOf(value));
        statement.executeUpdate();
      }
    });
  }

  public List<VoiceChatMuteStore.MuteEntry> loadMutes() {
    List<VoiceChatMuteStore.MuteEntry> entries = new ArrayList<>();
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT player_uuid, created_at, muted_until, moderator, reason FROM mutes")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          entries.add(new VoiceChatMuteStore.MuteEntry(
              UUID.fromString(resultSet.getString("player_uuid")),
              resultSet.getLong("created_at"),
              resultSet.getLong("muted_until"),
              resultSet.getString("moderator"),
              resultSet.getString("reason")));
        }
      }
    } catch (Exception ex) {
      plugin.getLogger().warning("Failed to load mutes: " + ex.getMessage());
    }
    return entries;
  }

  public void saveMute(VoiceChatMuteStore.MuteEntry entry) {
    if (entry == null || entry.getPlayerUuid() == null) {
      return;
    }
    submitWrite("save mute", connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "INSERT INTO mutes(player_uuid, created_at, muted_until, moderator, reason) VALUES (?, ?, ?, ?, ?) "
              + "ON CONFLICT(player_uuid) DO UPDATE SET created_at=excluded.created_at, "
              + "muted_until=excluded.muted_until, moderator=excluded.moderator, reason=excluded.reason")) {
        statement.setString(1, entry.getPlayerUuid().toString());
        statement.setLong(2, entry.getCreatedAtMs());
        statement.setLong(3, entry.getMutedUntilMs());
        statement.setString(4, entry.getModerator());
        statement.setString(5, entry.getReason());
        statement.executeUpdate();
      }
    });
  }

  public void deleteMute(UUID playerUuid) {
    executeDeleteByUuid("DELETE FROM mutes WHERE player_uuid = ?", playerUuid, "delete mute");
  }

  public Map<UUID, List<StoredAction>> loadHistory() {
    Map<UUID, List<StoredAction>> history = new HashMap<>();
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT log_id, target_uuid, created_at, action, actor, details FROM history ORDER BY created_at DESC")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          UUID uuid = UUID.fromString(resultSet.getString("target_uuid"));
          history.computeIfAbsent(uuid, key -> new ArrayList<>()).add(new StoredAction(
              resultSet.getString("log_id"),
              uuid,
              resultSet.getLong("created_at"),
              resultSet.getString("action"),
              resultSet.getString("actor"),
              resultSet.getString("details")));
        }
      }
    } catch (Exception ex) {
      plugin.getLogger().warning("Failed to load history: " + ex.getMessage());
    }
    return history;
  }

  public void appendHistory(StoredAction action) {
    if (action == null || action.targetUuid() == null || action.id() == null) {
      return;
    }
    submitWrite("append history", connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "INSERT OR REPLACE INTO history(log_id, target_uuid, created_at, action, actor, details) "
              + "VALUES (?, ?, ?, ?, ?, ?)")) {
        statement.setString(1, action.id());
        statement.setString(2, action.targetUuid().toString());
        statement.setLong(3, action.createdAtMs());
        statement.setString(4, action.action());
        statement.setString(5, action.actor());
        statement.setString(6, action.details());
        statement.executeUpdate();
      }
    });
  }

  public void deleteHistory(UUID playerUuid) {
    executeDeleteByUuid("DELETE FROM history WHERE target_uuid = ?", playerUuid, "delete history");
  }

  public Map<UUID, List<StoredNote>> loadNotes() {
    Map<UUID, List<StoredNote>> notes = new HashMap<>();
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT target_uuid, created_at, actor, text FROM notes ORDER BY created_at ASC")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          UUID uuid = UUID.fromString(resultSet.getString("target_uuid"));
          notes.computeIfAbsent(uuid, key -> new ArrayList<>()).add(new StoredNote(
              uuid,
              resultSet.getLong("created_at"),
              resultSet.getString("actor"),
              resultSet.getString("text")));
        }
      }
    } catch (Exception ex) {
      plugin.getLogger().warning("Failed to load notes: " + ex.getMessage());
    }
    return notes;
  }

  public void replaceNotes(UUID playerUuid, List<StoredNote> notes) {
    if (playerUuid == null) {
      return;
    }
    submitWrite("replace notes", connection -> {
      connection.setAutoCommit(false);
      try (PreparedStatement delete = connection.prepareStatement("DELETE FROM notes WHERE target_uuid = ?");
           PreparedStatement insert = connection.prepareStatement(
               "INSERT INTO notes(target_uuid, created_at, actor, text) VALUES (?, ?, ?, ?)")) {
        delete.setString(1, playerUuid.toString());
        delete.executeUpdate();
        if (notes != null) {
          for (StoredNote note : notes) {
            insert.setString(1, playerUuid.toString());
            insert.setLong(2, note.createdAtMs());
            insert.setString(3, note.actor());
            insert.setString(4, note.text());
            insert.addBatch();
          }
          insert.executeBatch();
        }
        connection.commit();
      } catch (Exception ex) {
        connection.rollback();
        throw ex;
      } finally {
        connection.setAutoCommit(true);
      }
    });
  }

  public Map<UUID, List<StoredWarning>> loadWarnings() {
    Map<UUID, List<StoredWarning>> warnings = new HashMap<>();
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT target_uuid, created_at, actor, reason FROM warnings ORDER BY created_at ASC")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          UUID uuid = UUID.fromString(resultSet.getString("target_uuid"));
          warnings.computeIfAbsent(uuid, key -> new ArrayList<>()).add(new StoredWarning(
              uuid,
              resultSet.getLong("created_at"),
              resultSet.getString("actor"),
              resultSet.getString("reason")));
        }
      }
    } catch (Exception ex) {
      plugin.getLogger().warning("Failed to load warnings: " + ex.getMessage());
    }
    return warnings;
  }

  public void appendWarning(StoredWarning warning) {
    if (warning == null || warning.targetUuid() == null) {
      return;
    }
    submitWrite("append warning", connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "INSERT INTO warnings(target_uuid, created_at, actor, reason) VALUES (?, ?, ?, ?)")) {
        statement.setString(1, warning.targetUuid().toString());
        statement.setLong(2, warning.createdAtMs());
        statement.setString(3, warning.actor());
        statement.setString(4, warning.reason());
        statement.executeUpdate();
      }
    });
  }

  public Map<String, GroupState> loadGroupStates() {
    Map<String, GroupState> states = new LinkedHashMap<>();
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT name, temp, locked FROM groups ORDER BY name ASC")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          states.put(resultSet.getString("name"), new GroupState(
              resultSet.getString("name"),
              resultSet.getInt("temp") == 1,
              resultSet.getInt("locked") == 1));
        }
      }
    } catch (Exception ex) {
      plugin.getLogger().warning("Failed to load group states: " + ex.getMessage());
    }
    return states;
  }

  public void saveGroupState(String groupName, boolean temporary, boolean locked) {
    if (groupName == null || groupName.isBlank()) {
      return;
    }
    submitWrite("save group state", connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "INSERT INTO groups(name, temp, locked) VALUES (?, ?, ?) "
              + "ON CONFLICT(name) DO UPDATE SET temp=excluded.temp, locked=excluded.locked")) {
        statement.setString(1, groupName);
        statement.setInt(2, temporary ? 1 : 0);
        statement.setInt(3, locked ? 1 : 0);
        statement.executeUpdate();
      }
    });
  }

  public void deleteGroupState(String groupName) {
    if (groupName == null || groupName.isBlank()) {
      return;
    }
    submitWrite("delete group state", connection -> {
      try (PreparedStatement statement = connection.prepareStatement("DELETE FROM groups WHERE name = ?")) {
        statement.setString(1, groupName);
        statement.executeUpdate();
      }
    });
  }

  public Map<UUID, String> loadLocalePreferences() {
    Map<UUID, String> values = new HashMap<>();
    try (Connection connection = openConnection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT player_uuid, locale FROM locale_preferences")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          values.put(UUID.fromString(resultSet.getString("player_uuid")), resultSet.getString("locale"));
        }
      }
    } catch (Exception ex) {
      plugin.getLogger().warning("Failed to load locale preferences: " + ex.getMessage());
    }
    return values;
  }

  public void saveLocalePreference(UUID playerUuid, String locale) {
    if (playerUuid == null || locale == null || locale.isBlank()) {
      return;
    }
    submitWrite("save locale preference", connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "INSERT INTO locale_preferences(player_uuid, locale) VALUES (?, ?) "
              + "ON CONFLICT(player_uuid) DO UPDATE SET locale=excluded.locale")) {
        statement.setString(1, playerUuid.toString());
        statement.setString(2, locale);
        statement.executeUpdate();
      }
    });
  }

  public void deleteLocalePreference(UUID playerUuid) {
    executeDeleteByUuid("DELETE FROM locale_preferences WHERE player_uuid = ?", playerUuid, "delete locale preference");
  }

  private void migrate(Connection connection, int version) throws Exception {
    try (Statement statement = connection.createStatement()) {
      switch (version) {
        case 1 -> {
          statement.executeUpdate(
              "CREATE TABLE IF NOT EXISTS mutes (player_uuid TEXT PRIMARY KEY, created_at INTEGER NOT NULL, "
                  + "muted_until INTEGER NOT NULL, moderator TEXT NOT NULL, reason TEXT NOT NULL)");
          statement.executeUpdate(
              "CREATE TABLE IF NOT EXISTS history (log_id TEXT PRIMARY KEY, target_uuid TEXT NOT NULL, "
                  + "created_at INTEGER NOT NULL, action TEXT NOT NULL, actor TEXT NOT NULL, details TEXT NOT NULL)");
          statement.executeUpdate(
              "CREATE TABLE IF NOT EXISTS notes (target_uuid TEXT NOT NULL, created_at INTEGER NOT NULL, "
                  + "actor TEXT NOT NULL, text TEXT NOT NULL)");
          statement.executeUpdate(
              "CREATE TABLE IF NOT EXISTS warnings (target_uuid TEXT NOT NULL, created_at INTEGER NOT NULL, "
                  + "actor TEXT NOT NULL, reason TEXT NOT NULL)");
          statement.executeUpdate(
              "CREATE TABLE IF NOT EXISTS groups (name TEXT PRIMARY KEY, temp INTEGER NOT NULL, locked INTEGER NOT NULL)");
          statement.executeUpdate(
              "CREATE TABLE IF NOT EXISTS locale_preferences (player_uuid TEXT PRIMARY KEY, locale TEXT NOT NULL)");
        }
        case 2 -> {
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_history_target_created ON history(target_uuid, created_at DESC)");
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_notes_target_created ON notes(target_uuid, created_at DESC)");
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_warnings_target_created ON warnings(target_uuid, created_at DESC)");
        }
        default -> {
        }
      }
    }
  }

  private int getSchemaVersion(Connection connection) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
      statement.setString(1, "schema_version");
      try (ResultSet resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          return Integer.parseInt(resultSet.getString("value"));
        }
      }
    }
    return 0;
  }

  private void setSchemaVersion(Connection connection, int version) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO meta(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
      statement.setString(1, "schema_version");
      statement.setString(2, String.valueOf(version));
      statement.executeUpdate();
    }
  }

  private void executeDeleteByUuid(String sql, UUID playerUuid, String action) {
    if (playerUuid == null) {
      return;
    }
    submitWrite(action, connection -> {
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, playerUuid.toString());
        statement.executeUpdate();
      }
    });
  }

  private void submitWrite(String action, SqlTask task) {
    if (closed) {
      return;
    }
    try {
      writeExecutor.execute(() -> {
        if (closed) {
          return;
        }
        try (Connection connection = openConnection()) {
          task.run(connection);
          lastError = "";
        } catch (Exception ex) {
          lastError = ex.getMessage();
          plugin.getLogger().warning("Failed to " + action + ": " + ex.getMessage());
        }
      });
    } catch (RejectedExecutionException ex) {
      lastError = ex.getMessage();
      plugin.getLogger().warning("Rejected async storage task for " + action + ": " + ex.getMessage());
    }
  }

  private Connection openConnection() throws Exception {
    return DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
  }

  @FunctionalInterface
  private interface SqlTask {
    void run(Connection connection) throws Exception;
  }

  public record StoredAction(String id, UUID targetUuid, long createdAtMs, String action, String actor, String details) {
  }

  public record StoredNote(UUID targetUuid, long createdAtMs, String actor, String text) {
  }

  public record StoredWarning(UUID targetUuid, long createdAtMs, String actor, String reason) {
  }

  public record GroupState(String name, boolean temporary, boolean locked) {
  }
}
