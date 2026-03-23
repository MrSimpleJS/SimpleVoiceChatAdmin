package com.simple.voiceadmin;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class BroadcastVoicechatPlugin implements VoicechatPlugin {
  private final VoiceChatAdminPlugin plugin;

  public BroadcastVoicechatPlugin(VoiceChatAdminPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public String getPluginId() {
    return "voicechat-admin-broadcast";
  }

  @Override
  public void initialize(VoicechatApi api) {
  }

  @Override
  public void registerEvents(EventRegistration registration) {
    registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
  }

  private void onMicrophone(MicrophonePacketEvent event) {
    if (event.getSenderConnection() == null || event.getSenderConnection().getPlayer() == null) {
      return;
    }
    Object rawPlayer = event.getSenderConnection().getPlayer().getPlayer();
    if (!(rawPlayer instanceof Player player)) {
      return;
    }
    String permission = plugin.getConfig().getString("voicechat-admin.permissions.broadcast", "svca.broadcast");
    if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
      return;
    }
    String groupName = plugin.getConfig().getString("voicechat-admin.broadcast.group-name", "broadcast");
    Group group = event.getSenderConnection().getGroup();
    if (group == null || group.getName() == null) {
      return;
    }
    if (groupName == null || groupName.isBlank()
        || !group.getName().trim().equalsIgnoreCase(groupName.trim())) {
      return;
    }
    event.cancel();
    VoicechatServerApi api = event.getVoicechat();
    for (Player onlinePlayer : Bukkit.getServer().getOnlinePlayers()) {
      if (onlinePlayer.getUniqueId().equals(player.getUniqueId())) {
        continue;
      }
      VoicechatConnection connection = api.getConnectionOf(onlinePlayer.getUniqueId());
      if (connection == null) {
        continue;
      }
      api.sendStaticSoundPacketTo(connection, ((MicrophonePacket) event.getPacket()).toStaticSoundPacket());
    }
  }
}
