package com.simple.voiceadmin;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class VoiceChatMuteStore {
  public static class MuteEntry {
    private final UUID playerUuid;
    private final long createdAtMs;
    private final long mutedUntilMs;
    private final String moderator;
    private final String reason;

    public MuteEntry(UUID playerUuid, long createdAtMs, long mutedUntilMs, String moderator, String reason) {
      this.playerUuid = playerUuid;
      this.createdAtMs = createdAtMs;
      this.mutedUntilMs = mutedUntilMs;
      this.moderator = moderator == null ? "" : moderator;
      this.reason = reason == null ? "" : reason;
    }

    public UUID getPlayerUuid() {
      return playerUuid;
    }

    public long getCreatedAtMs() {
      return createdAtMs;
    }

    public long getMutedUntilMs() {
      return mutedUntilMs;
    }

    public String getModerator() {
      return moderator;
    }

    public String getReason() {
      return reason;
    }
  }

  private final ConcurrentHashMap<UUID, MuteEntry> muteEntries = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, VoicechatConnection> connections = new ConcurrentHashMap<>();

  public void mute(UUID uuid, long durationMs) {
    mute(uuid, durationMs, "", "");
  }

  public void mute(UUID uuid, long durationMs, String moderator, String reason) {
    if (uuid == null) {
      return;
    }
    long now = System.currentTimeMillis();
    long until = durationMs <= 0L ? 0L : now + durationMs;
    muteEntries.put(uuid, new MuteEntry(uuid, now, until, moderator, reason));
  }

  public void unmute(UUID uuid) {
    if (uuid == null) {
      return;
    }
    muteEntries.remove(uuid);
  }

  public boolean isMuted(UUID uuid) {
    MuteEntry entry = getEntry(uuid);
    return entry != null;
  }

  public long getRemainingMs(UUID uuid) {
    MuteEntry entry = getEntry(uuid);
    if (entry == null) {
      return 0L;
    }
    if (entry.getMutedUntilMs() == 0L) {
      return -1L;
    }
    return Math.max(0L, entry.getMutedUntilMs() - System.currentTimeMillis());
  }

  public MuteEntry getEntry(UUID uuid) {
    if (uuid == null) {
      return null;
    }
    MuteEntry entry = muteEntries.get(uuid);
    if (entry == null) {
      return null;
    }
    if (entry.getMutedUntilMs() == 0L || System.currentTimeMillis() <= entry.getMutedUntilMs()) {
      return entry;
    }
    muteEntries.remove(uuid, entry);
    return null;
  }

  public Map<UUID, MuteEntry> getActiveMutes() {
    Map<UUID, MuteEntry> active = new HashMap<>();
    for (Map.Entry<UUID, MuteEntry> entry : muteEntries.entrySet()) {
      MuteEntry value = getEntry(entry.getKey());
      if (value != null) {
        active.put(entry.getKey(), value);
      }
    }
    return Collections.unmodifiableMap(active);
  }

  public List<MuteEntry> consumeExpiredEntries() {
    List<MuteEntry> expired = new ArrayList<>();
    long now = System.currentTimeMillis();
    for (Map.Entry<UUID, MuteEntry> entry : muteEntries.entrySet()) {
      MuteEntry value = entry.getValue();
      if (value == null || value.getMutedUntilMs() == 0L || now <= value.getMutedUntilMs()) {
        continue;
      }
      if (muteEntries.remove(entry.getKey(), value)) {
        expired.add(value);
      }
    }
    return expired;
  }

  public void setConnection(UUID uuid, VoicechatConnection connection) {
    if (uuid == null || connection == null) {
      return;
    }
    connections.put(uuid, connection);
  }

  public VoicechatConnection getConnection(UUID uuid) {
    if (uuid == null) {
      return null;
    }
    return connections.get(uuid);
  }

  public void clearConnection(UUID uuid) {
    if (uuid == null) {
      return;
    }
    connections.remove(uuid);
  }
}
