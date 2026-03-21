package com.simple.voiceadmin;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import org.bukkit.entity.Player;

public class MuteVoicechatPlugin implements VoicechatPlugin {
  private final VoiceChatAdminPlugin plugin;
  private final VoiceChatMuteStore muteStore;

  public MuteVoicechatPlugin(VoiceChatAdminPlugin plugin, VoiceChatMuteStore muteStore) {
    this.plugin = plugin;
    this.muteStore = muteStore;
  }

  @Override
  public String getPluginId() {
    return "voicechat-admin-mute";
  }

  @Override
  public void initialize(VoicechatApi api) {
    if (plugin != null) {
      plugin.getLogger().info("VoiceChat mute plugin initialized.");
    }
  }

  @Override
  public void registerEvents(EventRegistration registration) {
    if (plugin != null) {
      plugin.getLogger().info("VoiceChat mute events registered.");
    }
    registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    registration.registerEvent(PlayerConnectedEvent.class, this::onPlayerConnected);
    registration.registerEvent(PlayerDisconnectedEvent.class, this::onPlayerDisconnected);
  }

  private void onMicrophone(MicrophonePacketEvent event) {
    if (event.getSenderConnection() == null || event.getSenderConnection().getPlayer() == null) {
      return;
    }
    Object rawPlayer = event.getSenderConnection().getPlayer().getPlayer();
    if (!(rawPlayer instanceof Player player)) {
      return;
    }
    if (muteStore != null && muteStore.isMuted(player.getUniqueId())) {
      if (plugin != null) {
        plugin.debugLog("VoiceChat mute: " + player.getName());
      }
      event.cancel();
    }
  }

  private void onPlayerConnected(PlayerConnectedEvent event) {
    if (event.getConnection() == null || event.getConnection().getPlayer() == null) {
      return;
    }
    Object rawPlayer = event.getConnection().getPlayer().getPlayer();
    if (!(rawPlayer instanceof Player player)) {
      return;
    }
    if (muteStore != null) {
      muteStore.setConnection(player.getUniqueId(), event.getConnection());
      if (plugin != null) {
        plugin.debugLog("VoiceChat connected: " + player.getName());
      }
      if (muteStore.isMuted(player.getUniqueId())) {
        event.getConnection().setDisabled(true);
      }
    }
  }

  private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
    if (muteStore != null) {
      muteStore.clearConnection(event.getPlayerUuid());
    }
  }
}
