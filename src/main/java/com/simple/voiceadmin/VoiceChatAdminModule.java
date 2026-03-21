package com.simple.voiceadmin;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;

public class VoiceChatAdminModule implements CommandExecutor, Listener, TabCompleter {
  private static final String BASE_PATH = "voicechat-admin";
  private static final int MAX_HISTORY_PER_PLAYER = 15;
  private static final String LOCALIZATION_PATH = BASE_PATH + ".localization";
  private static final String LOCALES_PATH = BASE_PATH + ".locales";
  private final VoiceChatAdminPlugin plugin;
  private final AtomicLong auditSequence = new AtomicLong();
  private final Map<UUID, Deque<VoiceActionRecord>> actionHistory = new HashMap<>();
  private final Map<UUID, List<VoiceNote>> voiceNotes = new HashMap<>();
  private final Map<UUID, List<VoiceWarning>> voiceWarnings = new HashMap<>();
  private final Map<String, Long> actionCooldowns = new HashMap<>();
  private final Map<String, Long> pendingConfirmations = new HashMap<>();
  private final Set<String> lockedGroups = new HashSet<>();
  private final Set<String> temporaryGroups = new HashSet<>();
  private final Map<UUID, String> activeListenSessions = new HashMap<>();
  private final Map<UUID, String> activeSpySessions = new HashMap<>();
  private final ThreadLocal<String> localeContext = new ThreadLocal<>();
  private BukkitVoicechatService service;
  private VoiceChatApiPlugin apiPlugin;
  private BroadcastVoicechatPlugin broadcastPlugin;
  private MuteVoicechatPlugin mutePlugin;
  private VoiceChatMuteStore muteStore;
  private final Map<UUID, PermissionAttachment> mutePermissions = new HashMap<>();
  private boolean registered;
  private boolean apiRetryQueued;
  private int autoUnmuteTaskId = -1;

  public VoiceChatAdminModule(VoiceChatAdminPlugin plugin) {
    this.plugin = plugin;
  }

  public void enable() {
    if (!plugin.getConfig().getBoolean(BASE_PATH + ".enabled", true)) {
      return;
    }
    ensureServiceLoaded();
    startAutoUnmuteTask();
  }

  public void shutdown() {
    stopAutoUnmuteTask();
    if (service != null) {
      unregisterPlugins();
    }
    clearPermissionMutes();
    service = null;
    apiPlugin = null;
    broadcastPlugin = null;
    mutePlugin = null;
    muteStore = null;
    registered = false;
    apiRetryQueued = false;
    actionHistory.clear();
    voiceNotes.clear();
    voiceWarnings.clear();
    actionCooldowns.clear();
    pendingConfirmations.clear();
    lockedGroups.clear();
    temporaryGroups.clear();
    activeListenSessions.clear();
    activeSpySessions.clear();
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    localeContext.set(resolveLocale(sender));
    try {
      String name = command.getName().toLowerCase(Locale.ROOT);
      if (name.equals("broadcastvoice")) {
        return handleBroadcastCommand(sender);
      }
      if (name.equals("vcmute")) {
        return handleMuteCommand(sender, args);
      }
      if (name.equals("vcunmute")) {
        return handleUnmuteCommand(sender, args);
      }
      if (name.equals("vclist")) {
        return handleListCommand(sender);
      }
      if (name.equals("vcinfo")) {
        return handleInfoCommand(sender, args);
      }
      if (name.equals("vckick")) {
        return handleKickCommand(sender, args);
      }
      if (name.equals("vcmove")) {
        return handleMoveCommand(sender, args);
      }
      if (name.equals("vcmutelist")) {
        return handleMuteListCommand(sender);
      }
      if (name.equals("vchistory")) {
        return handleHistoryCommand(sender, args);
      }
      if (name.equals("vcwarn")) {
        return handleWarnCommand(sender, args);
      }
      if (name.equals("vcnotes")) {
        return handleNotesCommand(sender, args);
      }
      if (name.equals("vcpurgehistory")) {
        return handlePurgeHistoryCommand(sender, args);
      }
      if (name.equals("vcexport")) {
        return handleExportCommand(sender, args);
      }
      if (name.equals("vcmuteedit")) {
        return handleMuteEditCommand(sender, args);
      }
      if (name.equals("vcmuteall")) {
        return handleMuteAllCommand(sender, args);
      }
      if (name.equals("vcunmuteall")) {
        return handleUnmuteAllCommand(sender);
      }
      if (name.equals("vclock")) {
        return handleLockCommand(sender, args, true);
      }
      if (name.equals("vcunlock")) {
        return handleLockCommand(sender, args, false);
      }
      if (name.equals("vccreate")) {
        return handleCreateGroupCommand(sender, args);
      }
      if (name.equals("vcdelete")) {
        return handleDeleteGroupCommand(sender, args);
      }
      if (name.equals("vcpull")) {
        return handlePullCommand(sender, args);
      }
      if (name.equals("vcdisconnect")) {
        return handleDisconnectCommand(sender, args);
      }
      if (name.equals("vcreload")) {
        return handleReloadCommand(sender);
      }
      if (name.equals("vclisten")) {
        return handleListenCommand(sender, args);
      }
      if (name.equals("vcunlisten")) {
        return handleUnlistenCommand(sender);
      }
      if (name.equals("vcspy")) {
        return handleSpyCommand(sender, args);
      }
      if (name.equals("vcunspy")) {
        return handleUnspyCommand(sender);
      }
      return handleJoinCommand(sender, args);
    } finally {
      localeContext.remove();
    }
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    String name = command.getName().toLowerCase(Locale.ROOT);
    if (name.equals("adminjoin") || name.equals("vcmove") || name.equals("vclock")
        || name.equals("vcunlock") || name.equals("vccreate") || name.equals("vcdelete")
        || name.equals("vcpull")) {
      if ((name.equals("adminjoin") && args.length == 1) || (name.equals("vcmove") && args.length >= 2)) {
        String prefix = args[args.length - 1];
        return filterByPrefix(getKnownGroupNames(), prefix);
      }
      if (!name.equals("vcmove") && args.length == 1) {
        return filterByPrefix(getKnownGroupNames(), args[0]);
      }
    }
    if (name.equals("vcmute") || name.equals("vcunmute") || name.equals("vcinfo")
        || name.equals("vckick") || name.equals("vcmove") || name.equals("vchistory")
        || name.equals("vcwarn") || name.equals("vcnotes") || name.equals("vcexport")
        || name.equals("vcmuteedit") || name.equals("vcdisconnect") || name.equals("vcpurgehistory")) {
      if (args.length == 1) {
        return filterByPrefix(getKnownPlayerNames(), args[0]);
      }
    }
    if ((name.equals("vclisten") || name.equals("vcspy")) && args.length == 1) {
      return filterByPrefix(getKnownGroupNames(), args[0]);
    }
    if (name.equals("vcnotes") && args.length == 2) {
      return filterByPrefix(List.of("add", "remove", "list"), args[1]);
    }
    return Collections.emptyList();
  }

  private boolean handleJoinCommand(CommandSender sender, String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(msg("messages.only-player", "Dieser Befehl ist nur fuer Spieler."));
      return true;
    }
    ensureServiceLoaded();
    if (!player.hasPermission(getJoinPermission())) {
      player.sendMessage(msg("messages.no-permission", "&cDu hast keine Rechte."));
      return true;
    }
    if (args.length == 0) {
      player.sendMessage(msg("messages.need-group", "Bitte gib einen Gruppennamen an."));
      return true;
    }
    VoicechatServerApi voicechatApi = getVoicechatApiOrRetry(player);
    if (voicechatApi == null) {
      return true;
    }
    VoicechatConnection connection = voicechatApi.getConnectionOf(player.getUniqueId());
    if (connection == null) {
      player.sendMessage(msg("messages.connection-error", "Verbindung nicht gefunden."));
      return true;
    }
    Group group = findGroupByName(String.join(" ", args).trim());
    if (group == null) {
      player.sendMessage(msg("messages.group-not-found", "&rGruppe &a%group% &rnicht gefunden.")
          .replace("%group%", String.join(" ", args).trim()));
      return true;
    }
    if (isGroupLocked(safeGroupName(group))) {
      player.sendMessage(msg("messages.group-locked", "&cGruppe %group% ist gesperrt.")
          .replace("%group%", safeGroupName(group)));
      return true;
    }
    connection.setGroup(group);
    player.sendMessage(msg("messages.joined", "&rDu bist der Gruppe &a%group% &rbeigetreten.")
        .replace("%group%", safeGroupName(group)));
    return true;
  }

  private boolean handleMuteCommand(CommandSender sender, String[] args) {
    ensureServiceLoaded();
    if (getVoicechatApiOrRetry(sender) == null) {
      return true;
    }
    if (!sender.hasPermission(getMutePermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage(msg("messages.mute-usage", "&cBenutzung: /vcmute <spieler> [minuten] [grund]"));
      return true;
    }
    OfflinePlayer target = findOfflinePlayer(args[0]);
    if (target == null) {
      sender.sendMessage(msg("messages.player-not-found", "&cSpieler nicht gefunden."));
      return true;
    }
    ParsedMuteInput muteInput = parseMuteInput(args);
    if (!muteInput.valid()) {
      sender.sendMessage(msg("messages.mute-usage", "&cBenutzung: /vcmute <spieler> [minuten] [grund]"));
      return true;
    }
    ensureMuteStore();
    muteStore.mute(target.getUniqueId(), muteInput.durationMs(), sender.getName(), muteInput.reason());
    boolean permApplied = applyPermissionMute(target);
    boolean vcApplied = applyMuteState(target, true);
    boolean applied = permApplied || vcApplied;
    String base = msg("messages.mute-success", "&a%player% wurde gemutet.");
    sender.sendMessage(base.replace("%player%", playerName(target, args[0])));
    if (target.isOnline() && target.getPlayer() != null) {
      target.getPlayer().sendMessage(msg("messages.muted-you", "&cDu wurdest im VoiceChat gemutet."));
    }
    if (!applied) {
      sender.sendMessage(msg("messages.player-offline", "&eSpieler ist offline. Mute wird beim Join aktiv."));
    }
    String details = "duration=" + formatMuteDuration(muteInput.durationMs());
    if (!muteInput.reason().isBlank()) {
      details += ", reason=" + muteInput.reason();
    }
    String logId = recordAction(target.getUniqueId(), "mute", sender.getName(), details);
    sendAuditLog(logId, "mute", sender, target, details);
    return true;
  }

  private boolean handleUnmuteCommand(CommandSender sender, String[] args) {
    ensureServiceLoaded();
    if (getVoicechatApiOrRetry(sender) == null) {
      return true;
    }
    if (!sender.hasPermission(getMutePermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage(msg("messages.unmute-usage", "&cBenutzung: /vcunmute <spieler>"));
      return true;
    }
    OfflinePlayer target = findOfflinePlayer(args[0]);
    if (target == null) {
      sender.sendMessage(msg("messages.player-not-found", "&cSpieler nicht gefunden."));
      return true;
    }
    if (muteStore != null) {
      muteStore.unmute(target.getUniqueId());
    }
    boolean permApplied = clearPermissionMute(target);
    boolean vcApplied = applyMuteState(target, false);
    boolean applied = permApplied || vcApplied;
    String base = msg("messages.unmute-success", "&a%player% wurde entmutet.");
    sender.sendMessage(base.replace("%player%", playerName(target, args[0])));
    if (target.isOnline() && target.getPlayer() != null) {
      target.getPlayer().sendMessage(msg("messages.unmuted-you", "&aDu bist im VoiceChat wieder entmutet."));
    }
    if (!applied) {
      sender.sendMessage(msg("messages.player-offline-unmute", "&eSpieler ist offline. Entmute wird beim Join aktiv."));
    }
    String logId = recordAction(target.getUniqueId(), "unmute", sender.getName(), "");
    sendAuditLog(logId, "unmute", sender, target, "");
    return true;
  }

  private boolean handleBroadcastCommand(CommandSender sender) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(msg("messages.only-player", "Dieser Befehl ist nur fuer Spieler."));
      return true;
    }
    if (!player.hasPermission(getBroadcastPermission())) {
      player.sendMessage(msg("messages.no-permission", "&cDu hast keine Rechte."));
      return true;
    }
    VoicechatServerApi voicechatApi = getVoicechatApiOrRetry(player);
    if (voicechatApi == null) {
      return true;
    }
    VoicechatConnection connection = voicechatApi.getConnectionOf(player.getUniqueId());
    if (connection == null) {
      player.sendMessage(msg("messages.connection-error", "Verbindung nicht gefunden."));
      return true;
    }
    String groupName = getBroadcastGroupName();
    String password = getBroadcastPassword();
    Group group = voicechatApi.groupBuilder()
        .setPersistent(false)
        .setName(groupName)
        .setPassword(password)
        .setType(Group.Type.OPEN)
        .build();
    connection.setGroup(group);
    player.sendMessage(msg("messages.broadcast-joined", "&rBroadcast-Gruppe betreten.")
        .replace("%group%", groupName));
    return true;
  }

  private boolean handleListCommand(CommandSender sender) {
    ensureServiceLoaded();
    if (!sender.hasPermission(getListPermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    VoicechatServerApi api = getVoicechatApiOrRetry(sender);
    if (api == null) {
      return true;
    }
    Map<String, Integer> groupCounts = new HashMap<>();
    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
      VoicechatConnection connection = api.getConnectionOf(onlinePlayer.getUniqueId());
      if (connection == null || connection.getGroup() == null) {
        continue;
      }
      String name = safeGroupName(connection.getGroup());
      groupCounts.put(name, groupCounts.getOrDefault(name, 0) + 1);
    }
    sender.sendMessage(msg("messages.list-header", "&6Aktive VoiceChat-Gruppen:"));
    if (groupCounts.isEmpty()) {
      sender.sendMessage(msg("messages.list-empty", "&7Keine aktiven Gruppen gefunden."));
      return true;
    }
    List<String> names = new ArrayList<>(groupCounts.keySet());
    names.sort(String.CASE_INSENSITIVE_ORDER);
    String lineFormat = msg("messages.list-line", "&e- %group% &7(%count%)");
    for (String groupName : names) {
      sender.sendMessage(lineFormat
          .replace("%group%", groupName)
          .replace("%count%", String.valueOf(groupCounts.getOrDefault(groupName, 0))));
    }
    return true;
  }

  private boolean handleInfoCommand(CommandSender sender, String[] args) {
    ensureServiceLoaded();
    if (!sender.hasPermission(getInfoPermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage(msg("messages.info-usage", "&cBenutzung: /vcinfo <spieler>"));
      return true;
    }
    VoicechatServerApi api = getVoicechatApiOrRetry(sender);
    if (api == null) {
      return true;
    }
    OfflinePlayer target = findOfflinePlayer(args[0]);
    if (target == null) {
      sender.sendMessage(msg("messages.player-not-found", "&cSpieler nicht gefunden."));
      return true;
    }
    VoicechatConnection connection = getConnection(target);
    boolean connected = connection != null;
    boolean muted = muteStore != null && muteStore.isMuted(target.getUniqueId());
    String groupName = connected && connection.getGroup() != null
        ? safeGroupName(connection.getGroup())
        : msg("messages.no-group", "&7Keine");
    String remaining = muted && muteStore != null
        ? formatRemainingMute(muteStore.getRemainingMs(target.getUniqueId()))
        : msg("messages.not-muted", "&aNein");
    VoiceChatMuteStore.MuteEntry muteEntry = muteStore == null ? null : muteStore.getEntry(target.getUniqueId());

    sender.sendMessage(msg("messages.info-header", "&6VoiceChat-Info fuer &e%player%")
        .replace("%player%", playerName(target, args[0])));
    sender.sendMessage(msg("messages.info-connected", "&7Verbunden: %value%")
        .replace("%value%", connected ? msg("messages.state-yes", "&aJa") : msg("messages.state-no", "&cNein")));
    sender.sendMessage(msg("messages.info-group", "&7Gruppe: %value%").replace("%value%", groupName));
    sender.sendMessage(msg("messages.info-muted", "&7Gemutet: %value%")
        .replace("%value%", muted ? msg("messages.state-yes", "&aJa") : msg("messages.state-no", "&cNein")));
    sender.sendMessage(msg("messages.info-mute-remaining", "&7Mute-Restzeit: %value%")
        .replace("%value%", remaining));
    sender.sendMessage(msg("messages.info-moderator", "&7Moderator: %value%")
        .replace("%value%", muteEntry == null || muteEntry.getModerator().isBlank()
            ? msg("messages.unknown-value", "&7Unbekannt")
            : muteEntry.getModerator()));
    sender.sendMessage(msg("messages.info-reason", "&7Grund: %value%")
        .replace("%value%", muteEntry == null || muteEntry.getReason().isBlank()
            ? msg("messages.no-reason", "&7Kein Grund")
            : muteEntry.getReason()));
    return true;
  }

  private boolean handleKickCommand(CommandSender sender, String[] args) {
    ensureServiceLoaded();
    if (getVoicechatApiOrRetry(sender) == null) {
      return true;
    }
    if (!sender.hasPermission(getKickPermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage(msg("messages.kick-usage", "&cBenutzung: /vckick <spieler>"));
      return true;
    }
    OfflinePlayer target = findOfflinePlayer(args[0]);
    if (target == null) {
      sender.sendMessage(msg("messages.player-not-found", "&cSpieler nicht gefunden."));
      return true;
    }
    VoicechatConnection connection = getConnection(target);
    if (connection == null) {
      sender.sendMessage(msg("messages.player-not-connected", "&eSpieler ist nicht im VoiceChat verbunden."));
      return true;
    }
    String oldGroup = connection.getGroup() == null
        ? msg("messages.no-group", "&7Keine")
        : safeGroupName(connection.getGroup());
    connection.setGroup(null);
    sender.sendMessage(msg("messages.kick-success", "&a%player% wurde aus dem VoiceChat getrennt.")
        .replace("%player%", playerName(target, args[0])));
    if (target.isOnline() && target.getPlayer() != null) {
      target.getPlayer().sendMessage(msg("messages.kicked-you", "&cDu wurdest aus deiner VoiceChat-Gruppe entfernt."));
    }
    String details = "from=" + oldGroup;
    String logId = recordAction(target.getUniqueId(), "kick", sender.getName(), details);
    sendAuditLog(logId, "kick", sender, target, details);
    return true;
  }

  private boolean handleMoveCommand(CommandSender sender, String[] args) {
    ensureServiceLoaded();
    if (getVoicechatApiOrRetry(sender) == null) {
      return true;
    }
    if (!sender.hasPermission(getMovePermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (args.length < 2) {
      sender.sendMessage(msg("messages.move-usage", "&cBenutzung: /vcmove <spieler> <gruppe>"));
      return true;
    }
    OfflinePlayer target = findOfflinePlayer(args[0]);
    if (target == null) {
      sender.sendMessage(msg("messages.player-not-found", "&cSpieler nicht gefunden."));
      return true;
    }
    VoicechatConnection connection = getConnection(target);
    if (connection == null) {
      sender.sendMessage(msg("messages.player-not-connected", "&eSpieler ist nicht im VoiceChat verbunden."));
      return true;
    }
    String groupQuery = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
    Group group = findGroupByName(groupQuery);
    if (group == null) {
      sender.sendMessage(msg("messages.group-not-found", "&cGruppe %group% nicht gefunden.")
          .replace("%group%", groupQuery));
      return true;
    }
    String fromGroup = connection.getGroup() == null
        ? msg("messages.no-group", "&7Keine")
        : safeGroupName(connection.getGroup());
    connection.setGroup(group);
    sender.sendMessage(msg("messages.move-success", "&a%player% wurde in %group% verschoben.")
        .replace("%player%", playerName(target, args[0]))
        .replace("%group%", safeGroupName(group)));
    if (target.isOnline() && target.getPlayer() != null) {
      target.getPlayer().sendMessage(msg("messages.moved-you", "&eDu wurdest in die Gruppe %group% verschoben.")
          .replace("%group%", safeGroupName(group)));
    }
    String details = "from=" + fromGroup + ", to=" + safeGroupName(group);
    String logId = recordAction(target.getUniqueId(), "move", sender.getName(), details);
    sendAuditLog(logId, "move", sender, target, details);
    return true;
  }

  private boolean handleMuteListCommand(CommandSender sender) {
    ensureServiceLoaded();
    if (!sender.hasPermission(getMutePermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    ensureMuteStore();
    Map<UUID, VoiceChatMuteStore.MuteEntry> activeMutes = muteStore.getActiveMutes();
    sender.sendMessage(msg("messages.mutelist-header", "&6Aktive VoiceChat-Mutes:"));
    if (activeMutes.isEmpty()) {
      sender.sendMessage(msg("messages.mutelist-empty", "&7Keine aktiven Voice-Mutes."));
      return true;
    }
    List<VoiceChatMuteStore.MuteEntry> entries = new ArrayList<>(activeMutes.values());
    entries.sort(Comparator.comparingLong(VoiceChatMuteStore.MuteEntry::getCreatedAtMs).reversed());
    String lineFormat = msg("messages.mutelist-line", "&e- %player% &7| %remaining% &7| %moderator% &7| %reason%");
    for (VoiceChatMuteStore.MuteEntry entry : entries) {
      OfflinePlayer player = Bukkit.getOfflinePlayer(entry.getPlayerUuid());
      sender.sendMessage(lineFormat
          .replace("%player%", playerName(player, entry.getPlayerUuid().toString()))
          .replace("%remaining%", formatRemainingMute(muteStore.getRemainingMs(entry.getPlayerUuid())))
          .replace("%moderator%", entry.getModerator().isBlank() ? msg("messages.unknown-value", "&7Unbekannt") : entry.getModerator())
          .replace("%reason%", entry.getReason().isBlank() ? msg("messages.no-reason", "&7Kein Grund") : entry.getReason()));
    }
    return true;
  }

  private boolean handleHistoryCommand(CommandSender sender, String[] args) {
    if (!sender.hasPermission(getInfoPermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage(msg("messages.history-usage", "&cBenutzung: /vchistory <spieler>"));
      return true;
    }
    OfflinePlayer target = findOfflinePlayer(args[0]);
    if (target == null) {
      sender.sendMessage(msg("messages.player-not-found", "&cSpieler nicht gefunden."));
      return true;
    }
    Deque<VoiceActionRecord> records = actionHistory.get(target.getUniqueId());
    sender.sendMessage(msg("messages.history-header", "&6VoiceChat-Historie fuer &e%player%")
        .replace("%player%", playerName(target, args[0])));
    if (records == null || records.isEmpty()) {
      sender.sendMessage(msg("messages.history-empty", "&7Keine VoiceChat-Aktionen gespeichert."));
      return true;
    }
    String lineFormat = msg("messages.history-line", "&e%id% &7| &8%time% &7| %action% &7| %actor% &7| %details%");
    for (VoiceActionRecord record : records) {
      sender.sendMessage(lineFormat
          .replace("%id%", record.id())
          .replace("%time%", formatTimestamp(record.createdAtMs()))
          .replace("%action%", record.action())
          .replace("%actor%", record.actor())
          .replace("%details%", record.details().isBlank() ? "-" : record.details()));
    }
    return true;
  }

  private boolean handleWarnCommand(CommandSender sender, String[] args) {
    if (!sender.hasPermission(getMutePermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (args.length < 2) {
      sender.sendMessage(msg("messages.warn-usage", "&cBenutzung: /vcwarn <spieler> <grund>"));
      return true;
    }
    if (!checkCooldown(sender, "warn")) {
      return true;
    }
    OfflinePlayer target = findOfflinePlayer(args[0]);
    if (target == null) {
      sender.sendMessage(msg("messages.player-not-found", "&cSpieler nicht gefunden."));
      return true;
    }
    String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
    VoiceWarning warning = new VoiceWarning(System.currentTimeMillis(), sender.getName(), reason);
    voiceWarnings.computeIfAbsent(target.getUniqueId(), key -> new ArrayList<>()).add(warning);
    voiceNotes.computeIfAbsent(target.getUniqueId(), key -> new ArrayList<>())
        .add(new VoiceNote(System.currentTimeMillis(), sender.getName(), "[Warn] " + reason));
    sender.sendMessage(msg("messages.warn-success", "&aVoice-Warnung fuer %player% gespeichert.")
        .replace("%player%", playerName(target, args[0])));
    String logId = recordAction(target.getUniqueId(), "warn", sender.getName(), "reason=" + reason);
    sendAuditLog(logId, "warn", sender, target, "reason=" + reason);
    return true;
  }

  private boolean handleNotesCommand(CommandSender sender, String[] args) {
    if (!sender.hasPermission(getInfoPermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage(msg("messages.notes-usage", "&cBenutzung: /vcnotes <spieler> [add|remove|list] [text/index]"));
      return true;
    }
    OfflinePlayer target = findOfflinePlayer(args[0]);
    if (target == null) {
      sender.sendMessage(msg("messages.player-not-found", "&cSpieler nicht gefunden."));
      return true;
    }
    List<VoiceNote> notes = voiceNotes.computeIfAbsent(target.getUniqueId(), key -> new ArrayList<>());
    if (args.length == 1 || (args.length >= 2 && args[1].equalsIgnoreCase("list"))) {
      sender.sendMessage(msg("messages.notes-header", "&6Voice-Notizen fuer &e%player%")
          .replace("%player%", playerName(target, args[0])));
      if (notes.isEmpty()) {
        sender.sendMessage(msg("messages.notes-empty", "&7Keine Voice-Notizen vorhanden."));
        return true;
      }
      String lineFormat = msg("messages.notes-line", "&e#%index% &7| &8%time% &7| &b%actor% &7| %text%");
      for (int i = 0; i < notes.size(); i++) {
        VoiceNote note = notes.get(i);
        sender.sendMessage(lineFormat
            .replace("%index%", String.valueOf(i + 1))
            .replace("%time%", formatTimestamp(note.createdAtMs()))
            .replace("%actor%", note.actor())
            .replace("%text%", note.text()));
      }
      return true;
    }
    if (args[1].equalsIgnoreCase("add")) {
      if (args.length < 3) {
        sender.sendMessage(msg("messages.notes-usage", "&cBenutzung: /vcnotes <spieler> [add|remove|list] [text/index]"));
        return true;
      }
      String text = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim();
      notes.add(new VoiceNote(System.currentTimeMillis(), sender.getName(), text));
      sender.sendMessage(msg("messages.notes-add-success", "&aNotiz fuer %player% gespeichert.")
          .replace("%player%", playerName(target, args[0])));
      String logId = recordAction(target.getUniqueId(), "note", sender.getName(), "note=" + text);
      sendAuditLog(logId, "note", sender, target, "note=" + text);
      return true;
    }
    if (args[1].equalsIgnoreCase("remove")) {
      if (args.length < 3) {
        sender.sendMessage(msg("messages.notes-usage", "&cBenutzung: /vcnotes <spieler> [add|remove|list] [text/index]"));
        return true;
      }
      try {
        int index = Integer.parseInt(args[2]) - 1;
        if (index < 0 || index >= notes.size()) {
          sender.sendMessage(msg("messages.notes-invalid-index", "&cUngueltiger Notiz-Index."));
          return true;
        }
        VoiceNote removed = notes.remove(index);
        sender.sendMessage(msg("messages.notes-remove-success", "&aNotiz fuer %player% entfernt.")
            .replace("%player%", playerName(target, args[0])));
        String logId = recordAction(target.getUniqueId(), "note-remove", sender.getName(), "note=" + removed.text());
        sendAuditLog(logId, "note-remove", sender, target, "note=" + removed.text());
        return true;
      } catch (NumberFormatException ignored) {
        sender.sendMessage(msg("messages.notes-invalid-index", "&cUngueltiger Notiz-Index."));
        return true;
      }
    }
    sender.sendMessage(msg("messages.notes-usage", "&cBenutzung: /vcnotes <spieler> [add|remove|list] [text/index]"));
    return true;
  }

  private boolean handlePurgeHistoryCommand(CommandSender sender, String[] args) {
    if (!sender.hasPermission(getInfoPermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage(msg("messages.purge-usage", "&cBenutzung: /vcpurgehistory <spieler>"));
      return true;
    }
    OfflinePlayer target = findOfflinePlayer(args[0]);
    if (target == null) {
      sender.sendMessage(msg("messages.player-not-found", "&cSpieler nicht gefunden."));
      return true;
    }
    String confirmKey = "purgehistory:" + target.getUniqueId();
    if (!requireConfirmation(sender, confirmKey,
        msg("messages.confirm-purge", "&eWiederhole den Befehl, um die History von %player% zu loeschen.")
            .replace("%player%", playerName(target, args[0])))) {
      return true;
    }
    actionHistory.remove(target.getUniqueId());
    sender.sendMessage(msg("messages.purge-success", "&aVoiceChat-History fuer %player% geloescht.")
        .replace("%player%", playerName(target, args[0])));
    String logId = recordAction(target.getUniqueId(), "purge-history", sender.getName(), "");
    sendAuditLog(logId, "purge-history", sender, target, "");
    return true;
  }

  private boolean handleExportCommand(CommandSender sender, String[] args) {
    if (!sender.hasPermission(getInfoPermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage(msg("messages.export-usage", "&cBenutzung: /vcexport <spieler>"));
      return true;
    }
    OfflinePlayer target = findOfflinePlayer(args[0]);
    if (target == null) {
      sender.sendMessage(msg("messages.player-not-found", "&cSpieler nicht gefunden."));
      return true;
    }
    Deque<VoiceActionRecord> records = actionHistory.get(target.getUniqueId());
    sender.sendMessage(msg("messages.export-header", "&6VoiceChat-Export fuer &e%player%")
        .replace("%player%", playerName(target, args[0])));
    if (records == null || records.isEmpty()) {
      sender.sendMessage(msg("messages.history-empty", "&7Keine VoiceChat-Aktionen gespeichert."));
      return true;
    }
    StringBuilder export = new StringBuilder();
    export.append("VoiceChat Export | ").append(playerName(target, args[0])).append('\n');
    for (VoiceActionRecord record : records) {
      String line = record.id() + " | " + formatTimestamp(record.createdAtMs()) + " | "
          + record.action() + " | " + record.actor() + " | "
          + (record.details().isBlank() ? "-" : record.details());
      sender.sendMessage(ChatColor.GRAY + line);
      export.append(line).append('\n');
    }
    sendExportWebhook("export", sender, target, export.toString().trim());
    return true;
  }

  private boolean handleMuteEditCommand(CommandSender sender, String[] args) {
    ensureMuteStore();
    if (!sender.hasPermission(getMutePermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (args.length < 2) {
      sender.sendMessage(msg("messages.muteedit-usage", "&cBenutzung: /vcmuteedit <spieler> <minuten|keep> [grund]"));
      return true;
    }
    OfflinePlayer target = findOfflinePlayer(args[0]);
    if (target == null) {
      sender.sendMessage(msg("messages.player-not-found", "&cSpieler nicht gefunden."));
      return true;
    }
    VoiceChatMuteStore.MuteEntry entry = muteStore.getEntry(target.getUniqueId());
    if (entry == null) {
      sender.sendMessage(msg("messages.muteedit-not-muted", "&eSpieler ist aktuell nicht gemutet."));
      return true;
    }
    long newDurationMs;
    if (args[1].equalsIgnoreCase("keep")) {
      long remaining = muteStore.getRemainingMs(target.getUniqueId());
      newDurationMs = remaining < 0L ? 0L : remaining;
    } else {
      try {
        long minutes = Long.parseLong(args[1]);
        if (minutes < 0L) {
          sender.sendMessage(msg("messages.muteedit-usage", "&cBenutzung: /vcmuteedit <spieler> <minuten|keep> [grund]"));
          return true;
        }
        newDurationMs = minutes <= 0L ? 0L : minutes * 60_000L;
      } catch (NumberFormatException ignored) {
        sender.sendMessage(msg("messages.muteedit-usage", "&cBenutzung: /vcmuteedit <spieler> <minuten|keep> [grund]"));
        return true;
      }
    }
    String newReason = args.length >= 3
        ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim()
        : entry.getReason();
    muteStore.mute(target.getUniqueId(), newDurationMs, sender.getName(), newReason);
    applyMuteState(target, true);
    applyPermissionMute(target);
    sender.sendMessage(msg("messages.muteedit-success", "&aMute von %player% wurde aktualisiert.")
        .replace("%player%", playerName(target, args[0])));
    String details = "duration=" + formatMuteDuration(newDurationMs) + ", reason=" + newReason;
    String logId = recordAction(target.getUniqueId(), "mute-edit", sender.getName(), details);
    sendAuditLog(logId, "mute-edit", sender, target, details);
    return true;
  }

  private boolean handleMuteAllCommand(CommandSender sender, String[] args) {
    ensureServiceLoaded();
    if (getVoicechatApiOrRetry(sender) == null) {
      return true;
    }
    if (!sender.hasPermission(getMutePermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (!checkCooldown(sender, "muteall")) {
      return true;
    }
    ParsedMuteInput muteInput = parseMuteInput(args == null ? new String[0] : prependDummy(args));
    if (!muteInput.valid()) {
      sender.sendMessage(msg("messages.muteall-usage", "&cBenutzung: /vcmuteall [minuten] [grund]"));
      return true;
    }
    if (!requireConfirmation(sender, "muteall",
        msg("messages.confirm-muteall", "&eWiederhole den Befehl, um alle verbundenen Voice-Spieler zu muten."))) {
      return true;
    }
    ensureMuteStore();
    int count = 0;
    for (Player online : Bukkit.getOnlinePlayers()) {
      if (sender instanceof Player self && self.getUniqueId().equals(online.getUniqueId())) {
        continue;
      }
      VoicechatConnection connection = getConnection(online);
      if (connection == null) {
        continue;
      }
      muteStore.mute(online.getUniqueId(), muteInput.durationMs(), sender.getName(), muteInput.reason());
      applyPermissionMute(online);
      applyMuteState(online, true);
      count++;
    }
    sender.sendMessage(msg("messages.muteall-success", "&a%count% Spieler im VoiceChat wurden gemutet.")
        .replace("%count%", String.valueOf(count)));
    sendAuditLog(recordAction(null, "muteall", sender.getName(),
        "count=" + count + ", duration=" + formatMuteDuration(muteInput.durationMs())
            + (muteInput.reason().isBlank() ? "" : ", reason=" + muteInput.reason())),
        "muteall", sender, null,
        "count=" + count + ", duration=" + formatMuteDuration(muteInput.durationMs())
            + (muteInput.reason().isBlank() ? "" : ", reason=" + muteInput.reason()));
    return true;
  }

  private boolean handleUnmuteAllCommand(CommandSender sender) {
    ensureServiceLoaded();
    if (!sender.hasPermission(getMutePermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (!requireConfirmation(sender, "unmuteall",
        msg("messages.confirm-unmuteall", "&eWiederhole den Befehl, um alle Voice-Mutes zu entfernen."))) {
      return true;
    }
    ensureMuteStore();
    int count = 0;
    for (UUID uuid : new ArrayList<>(muteStore.getActiveMutes().keySet())) {
      OfflinePlayer target = Bukkit.getOfflinePlayer(uuid);
      muteStore.unmute(uuid);
      clearPermissionMute(target);
      applyMuteState(target, false);
      count++;
    }
    sender.sendMessage(msg("messages.unmuteall-success", "&a%count% Voice-Mutes wurden entfernt.")
        .replace("%count%", String.valueOf(count)));
    sendAuditLog(recordAction(null, "unmuteall", sender.getName(), "count=" + count),
        "unmuteall", sender, null, "count=" + count);
    return true;
  }

  private boolean handleLockCommand(CommandSender sender, String[] args, boolean lock) {
    if (!sender.hasPermission(getMovePermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage(msg("messages.lock-usage", "&cBenutzung: /%command% <gruppe>")
          .replace("%command%", lock ? "vclock" : "vcunlock"));
      return true;
    }
    String groupName = String.join(" ", args).trim();
    if (groupName.isBlank()) {
      sender.sendMessage(msg("messages.lock-usage", "&cBenutzung: /%command% <gruppe>")
          .replace("%command%", lock ? "vclock" : "vcunlock"));
      return true;
    }
    if (lock) {
      lockedGroups.add(groupName.toLowerCase(Locale.ROOT));
    } else {
      lockedGroups.remove(groupName.toLowerCase(Locale.ROOT));
    }
    sender.sendMessage(msg(lock ? "messages.lock-success" : "messages.unlock-success",
        lock ? "&aGruppe %group% wurde gesperrt." : "&aGruppe %group% wurde entsperrt.")
        .replace("%group%", groupName));
    sendAuditLog(recordAction(null, lock ? "lock" : "unlock", sender.getName(), "group=" + groupName),
        lock ? "lock" : "unlock", sender, null, "group=" + groupName);
    return true;
  }

  private boolean handleCreateGroupCommand(CommandSender sender, String[] args) {
    ensureServiceLoaded();
    if (getVoicechatApiOrRetry(sender) == null) {
      return true;
    }
    if (!sender.hasPermission(getMovePermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage(msg("messages.create-usage", "&cBenutzung: /vccreate <gruppe>"));
      return true;
    }
    String groupName = String.join(" ", args).trim();
    if (findGroupByName(groupName) != null || getTemporaryGroupName(groupName) != null) {
      sender.sendMessage(msg("messages.create-exists", "&eGruppe %group% existiert bereits.")
          .replace("%group%", groupName));
      return true;
    }
    temporaryGroups.add(groupName);
    sender.sendMessage(msg("messages.create-success", "&aTemporare Gruppe %group% erstellt.")
        .replace("%group%", groupName));
    sendAuditLog(recordAction(null, "create-group", sender.getName(), "group=" + groupName),
        "create-group", sender, null, "group=" + groupName);
    return true;
  }

  private boolean handleDeleteGroupCommand(CommandSender sender, String[] args) {
    ensureServiceLoaded();
    if (getVoicechatApiOrRetry(sender) == null) {
      return true;
    }
    if (!sender.hasPermission(getMovePermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage(msg("messages.delete-usage", "&cBenutzung: /vcdelete <gruppe>"));
      return true;
    }
    String groupName = String.join(" ", args).trim();
    String storedName = getTemporaryGroupName(groupName);
    if (storedName == null) {
      sender.sendMessage(msg("messages.delete-missing", "&eTemporare Gruppe %group% nicht gefunden.")
          .replace("%group%", groupName));
      return true;
    }
    if (!requireConfirmation(sender, "deletegroup:" + storedName.toLowerCase(Locale.ROOT),
        msg("messages.confirm-deletegroup", "&eWiederhole den Befehl, um %group% zu loeschen.")
            .replace("%group%", storedName))) {
      return true;
    }
    temporaryGroups.remove(storedName);
    lockedGroups.remove(storedName.toLowerCase(Locale.ROOT));
    for (Player online : Bukkit.getOnlinePlayers()) {
      VoicechatConnection connection = getConnection(online);
      if (connection == null || connection.getGroup() == null) {
        continue;
      }
      if (safeGroupName(connection.getGroup()).equalsIgnoreCase(storedName)) {
        connection.setGroup(null);
      }
    }
    sender.sendMessage(msg("messages.delete-success", "&aTemporare Gruppe %group% geloescht.")
        .replace("%group%", storedName));
    sendAuditLog(recordAction(null, "delete-group", sender.getName(), "group=" + storedName),
        "delete-group", sender, null, "group=" + storedName);
    return true;
  }

  private boolean handlePullCommand(CommandSender sender, String[] args) {
    ensureServiceLoaded();
    if (getVoicechatApiOrRetry(sender) == null) {
      return true;
    }
    if (!sender.hasPermission(getMovePermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage(msg("messages.pull-usage", "&cBenutzung: /vcpull <gruppe>"));
      return true;
    }
    String groupQuery = String.join(" ", args).trim();
    Group group = findGroupByName(groupQuery);
    if (group == null) {
      sender.sendMessage(msg("messages.group-not-found", "&cGruppe %group% nicht gefunden.")
          .replace("%group%", groupQuery));
      return true;
    }
    if (!requireConfirmation(sender, "pull:" + safeGroupName(group).toLowerCase(Locale.ROOT),
        msg("messages.confirm-pull", "&eWiederhole den Befehl, um alle Voice-Spieler in %group% zu ziehen.")
            .replace("%group%", safeGroupName(group)))) {
      return true;
    }
    int count = 0;
    for (Player online : Bukkit.getOnlinePlayers()) {
      VoicechatConnection connection = getConnection(online);
      if (connection == null) {
        continue;
      }
      connection.setGroup(group);
      count++;
    }
    sender.sendMessage(msg("messages.pull-success", "&a%count% Spieler wurden in %group% verschoben.")
        .replace("%count%", String.valueOf(count))
        .replace("%group%", safeGroupName(group)));
    sendAuditLog(recordAction(null, "pull", sender.getName(), "group=" + safeGroupName(group) + ", count=" + count),
        "pull", sender, null, "group=" + safeGroupName(group) + ", count=" + count);
    return true;
  }

  private boolean handleDisconnectCommand(CommandSender sender, String[] args) {
    ensureServiceLoaded();
    if (getVoicechatApiOrRetry(sender) == null) {
      return true;
    }
    if (!sender.hasPermission(getKickPermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage(msg("messages.disconnect-usage", "&cBenutzung: /vcdisconnect <spieler>"));
      return true;
    }
    OfflinePlayer target = findOfflinePlayer(args[0]);
    if (target == null) {
      sender.sendMessage(msg("messages.player-not-found", "&cSpieler nicht gefunden."));
      return true;
    }
    VoicechatConnection connection = getConnection(target);
    if (connection == null) {
      sender.sendMessage(msg("messages.player-not-connected", "&eSpieler ist nicht im VoiceChat verbunden."));
      return true;
    }
    connection.setGroup(null);
    connection.setDisabled(true);
    Bukkit.getScheduler().runTaskLater(plugin, () -> {
      VoicechatConnection refreshed = getConnection(target);
      if (refreshed != null && (muteStore == null || !muteStore.isMuted(target.getUniqueId()))) {
        refreshed.setDisabled(false);
      }
    }, 60L);
    sender.sendMessage(msg("messages.disconnect-success", "&a%player% wurde aus dem VoiceChat getrennt.")
        .replace("%player%", playerName(target, args[0])));
    if (target.isOnline() && target.getPlayer() != null) {
      target.getPlayer().sendMessage(msg("messages.disconnected-you", "&cDu wurdest vorübergehend aus dem VoiceChat getrennt."));
    }
    String logId = recordAction(target.getUniqueId(), "disconnect", sender.getName(), "");
    sendAuditLog(logId, "disconnect", sender, target, "");
    return true;
  }

  private boolean handleReloadCommand(CommandSender sender) {
    if (!sender.hasPermission(getMovePermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    sender.sendMessage(msg("messages.reload-success", "&aVoiceChat admin module reloaded."));
    sendAuditLog(recordAction(null, "reload", sender.getName(), ""), "reload", sender, null, "");
    plugin.reloadVoiceAdmin();
    return true;
  }

  private boolean handleListenCommand(CommandSender sender, String[] args) {
    ensureServiceLoaded();
    if (!(sender instanceof Player player)) {
      sender.sendMessage(msg("messages.only-player", "&cNur Spieler."));
      return true;
    }
    if (getVoicechatApiOrRetry(sender) == null) {
      return true;
    }
    if (!sender.hasPermission(getListenPermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage(msg("messages.listen-usage", "&cBenutzung: /vclisten <gruppe>"));
      return true;
    }
    String groupQuery = String.join(" ", args).trim();
    Group group = findGroupByName(groupQuery);
    if (group == null) {
      sender.sendMessage(msg("messages.group-not-found", "&cGruppe %group% nicht gefunden.")
          .replace("%group%", groupQuery));
      return true;
    }
    VoicechatConnection connection = getConnection(player);
    if (connection == null) {
      sender.sendMessage(msg("messages.connection-error", "&cVerbindung nicht gefunden."));
      return true;
    }
    connection.setGroup(group);
    activeListenSessions.put(player.getUniqueId(), safeGroupName(group));
    sender.sendMessage(msg("messages.listen-success", "&aDu hoerst jetzt in %group% mit.")
        .replace("%group%", safeGroupName(group)));
    if (plugin.getConfig().getBoolean(BASE_PATH + ".listen.notify-group-members", true)) {
      notifyGroupMembers(safeGroupName(group),
          msg("messages.listen-notify-group", "&eEin Teammitglied hoert jetzt in %group% mit.")
              .replace("%group%", safeGroupName(group)),
          player.getUniqueId());
    }
    String details = "group=" + safeGroupName(group);
    String logId = recordAction(player.getUniqueId(), "listen", sender.getName(), details);
    sendAuditLog(logId, "listen", sender, player, details);
    return true;
  }

  private boolean handleUnlistenCommand(CommandSender sender) {
    ensureServiceLoaded();
    if (!(sender instanceof Player player)) {
      sender.sendMessage(msg("messages.only-player", "&cNur Spieler."));
      return true;
    }
    if (!sender.hasPermission(getListenPermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    String oldGroup = activeListenSessions.remove(player.getUniqueId());
    if (oldGroup == null) {
      sender.sendMessage(msg("messages.unlisten-empty", "&eDu befindest dich aktuell nicht im Listen-Modus."));
      return true;
    }
    sender.sendMessage(msg("messages.unlisten-success", "&aListen-Modus beendet."));
    String logId = recordAction(player.getUniqueId(), "unlisten", sender.getName(), "group=" + oldGroup);
    sendAuditLog(logId, "unlisten", sender, player, "group=" + oldGroup);
    return true;
  }

  private boolean handleSpyCommand(CommandSender sender, String[] args) {
    ensureServiceLoaded();
    if (!(sender instanceof Player player)) {
      sender.sendMessage(msg("messages.only-player", "&cNur Spieler."));
      return true;
    }
    if (getVoicechatApiOrRetry(sender) == null) {
      return true;
    }
    if (!sender.hasPermission(getSpyPermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage(msg("messages.spy-usage", "&cBenutzung: /vcspy <gruppe>"));
      return true;
    }
    String groupQuery = String.join(" ", args).trim();
    Group group = findGroupByName(groupQuery);
    if (group == null) {
      sender.sendMessage(msg("messages.group-not-found", "&cGruppe %group% nicht gefunden.")
          .replace("%group%", groupQuery));
      return true;
    }
    VoicechatConnection connection = getConnection(player);
    if (connection == null) {
      sender.sendMessage(msg("messages.connection-error", "&cVerbindung nicht gefunden."));
      return true;
    }
    connection.setGroup(group);
    activeSpySessions.put(player.getUniqueId(), safeGroupName(group));
    sender.sendMessage(msg("messages.spy-success", "&aSpy-Modus aktiv fuer %group%.")
        .replace("%group%", safeGroupName(group)));
    String details = "group=" + safeGroupName(group);
    String logId = recordAction(player.getUniqueId(), "spy", sender.getName(), details);
    sendAuditLog(logId, "spy", sender, player, details);
    return true;
  }

  private boolean handleUnspyCommand(CommandSender sender) {
    ensureServiceLoaded();
    if (!(sender instanceof Player player)) {
      sender.sendMessage(msg("messages.only-player", "&cNur Spieler."));
      return true;
    }
    if (!sender.hasPermission(getSpyPermission())) {
      sender.sendMessage(msg("messages.no-permission", "&cKeine Rechte."));
      return true;
    }
    String oldGroup = activeSpySessions.remove(player.getUniqueId());
    if (oldGroup == null) {
      sender.sendMessage(msg("messages.unspy-empty", "&eDu befindest dich aktuell nicht im Spy-Modus."));
      return true;
    }
    sender.sendMessage(msg("messages.unspy-success", "&aSpy-Modus beendet."));
    String logId = recordAction(player.getUniqueId(), "unspy", sender.getName(), "group=" + oldGroup);
    sendAuditLog(logId, "unspy", sender, player, "group=" + oldGroup);
    return true;
  }

  private VoicechatServerApi getVoicechatApi() {
    return apiPlugin == null ? null : apiPlugin.getVoicechatApi();
  }

  private VoicechatServerApi getVoicechatApiOrRetry(CommandSender sender) {
    VoicechatServerApi api = getVoicechatApi();
    if (api != null) {
      apiRetryQueued = false;
      return api;
    }
    ensureServiceLoaded();
    api = getVoicechatApi();
    if (api != null) {
      apiRetryQueued = false;
      return api;
    }
    if (!apiRetryQueued) {
      apiRetryQueued = true;
      Bukkit.getScheduler().runTaskLater(plugin, () -> {
        ensureServiceLoaded();
        if (getVoicechatApi() != null) {
          apiRetryQueued = false;
          return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> apiRetryQueued = false, 100L);
      }, 40L);
    }
    sender.sendMessage(msg("messages.api-retrying",
        "&eVoiceChat API wird noch geladen. Bitte versuche es gleich erneut."));
    return null;
  }

  private void ensureServiceLoaded() {
    if (registered) {
      return;
    }
    if (service == null) {
      service = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
    }
    if (service == null) {
      return;
    }
    apiPlugin = new VoiceChatApiPlugin(plugin);
    broadcastPlugin = new BroadcastVoicechatPlugin(plugin, getBroadcastPermission(), getBroadcastGroupName());
    muteStore = new VoiceChatMuteStore();
    mutePlugin = new MuteVoicechatPlugin(plugin, muteStore);
    service.registerPlugin((VoicechatPlugin) apiPlugin);
    service.registerPlugin((VoicechatPlugin) broadcastPlugin);
    service.registerPlugin((VoicechatPlugin) mutePlugin);
    registered = true;
    plugin.getLogger().info("VoiceChat admin module enabled.");
  }

  private void unregisterPlugins() {
    if (!registered) {
      return;
    }
    if (mutePlugin != null) {
      plugin.getServer().getServicesManager().unregister(mutePlugin);
    }
    if (broadcastPlugin != null) {
      plugin.getServer().getServicesManager().unregister(broadcastPlugin);
    }
    if (apiPlugin != null) {
      plugin.getServer().getServicesManager().unregister(apiPlugin);
    }
    registered = false;
  }

  private void ensureMuteStore() {
    if (muteStore == null) {
      muteStore = new VoiceChatMuteStore();
    }
  }

  private OfflinePlayer findOfflinePlayer(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    OfflinePlayer target = plugin.getServer().getOfflinePlayer(name);
    if (target == null || (!target.isOnline() && !target.hasPlayedBefore())) {
      return null;
    }
    return target;
  }

  private VoicechatConnection getConnection(OfflinePlayer target) {
    if (target == null) {
      return null;
    }
    VoicechatConnection connection = muteStore == null ? null : muteStore.getConnection(target.getUniqueId());
    if (connection != null) {
      return connection;
    }
    VoicechatServerApi api = getVoicechatApi();
    if (api == null) {
      return null;
    }
    if (target.isOnline() && target.getPlayer() != null) {
      try {
        Object serverPlayer = api.fromServerPlayer(target.getPlayer());
        if (serverPlayer instanceof de.maxhenkel.voicechat.api.ServerPlayer sp) {
          connection = api.getConnectionOf(sp);
        }
      } catch (Exception ignored) {
      }
    }
    if (connection == null) {
      connection = api.getConnectionOf(target.getUniqueId());
    }
    if (connection != null && muteStore != null) {
      muteStore.setConnection(target.getUniqueId(), connection);
    }
    return connection;
  }

  private Group findGroupByName(String groupName) {
    VoicechatServerApi api = getVoicechatApi();
    if (api == null || groupName == null || groupName.isBlank()) {
      return null;
    }
    for (Group group : api.getGroups()) {
      if (group != null && safeGroupName(group).equalsIgnoreCase(groupName.trim())) {
        return group;
      }
    }
    String tempName = getTemporaryGroupName(groupName.trim());
    if (tempName != null) {
      return api.groupBuilder()
          .setPersistent(false)
          .setName(tempName)
          .setType(Group.Type.OPEN)
          .build();
    }
    return null;
  }

  private boolean isGroupLocked(String groupName) {
    return groupName != null && lockedGroups.contains(groupName.toLowerCase(Locale.ROOT));
  }

  private String getJoinPermission() {
    return plugin.getConfig().getString(BASE_PATH + ".permissions.join", "svca.join");
  }

  private String getBroadcastPermission() {
    return plugin.getConfig().getString(BASE_PATH + ".permissions.broadcast", "svca.broadcast");
  }

  private String getMutePermission() {
    return plugin.getConfig().getString(BASE_PATH + ".permissions.mute", "svca.mute");
  }

  private String getListPermission() {
    return plugin.getConfig().getString(BASE_PATH + ".permissions.list", getMutePermission());
  }

  private String getInfoPermission() {
    return plugin.getConfig().getString(BASE_PATH + ".permissions.info", getMutePermission());
  }

  private String getKickPermission() {
    return plugin.getConfig().getString(BASE_PATH + ".permissions.kick", getMutePermission());
  }

  private String getMovePermission() {
    return plugin.getConfig().getString(BASE_PATH + ".permissions.move", getMutePermission());
  }

  private String getListenPermission() {
    return plugin.getConfig().getString(BASE_PATH + ".permissions.listen", getMovePermission());
  }

  private String getSpyPermission() {
    return plugin.getConfig().getString(BASE_PATH + ".permissions.spy", getListenPermission());
  }

  private void startAutoUnmuteTask() {
    if (autoUnmuteTaskId != -1) {
      return;
    }
    long intervalTicks = Math.max(20L, plugin.getConfig().getLong(BASE_PATH + ".auto-unmute.check-interval-ticks", 20L * 15L));
    autoUnmuteTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::processExpiredMutes,
        intervalTicks, intervalTicks);
  }

  private void stopAutoUnmuteTask() {
    if (autoUnmuteTaskId != -1) {
      Bukkit.getScheduler().cancelTask(autoUnmuteTaskId);
      autoUnmuteTaskId = -1;
    }
  }

  private void processExpiredMutes() {
    if (muteStore == null) {
      return;
    }
    for (VoiceChatMuteStore.MuteEntry entry : muteStore.consumeExpiredEntries()) {
      OfflinePlayer target = Bukkit.getOfflinePlayer(entry.getPlayerUuid());
      clearPermissionMute(target);
      applyMuteState(target, false);
      if (target.isOnline() && target.getPlayer() != null) {
        target.getPlayer().sendMessage(msg("messages.auto-unmuted-you", "&aYour voice chat mute has expired."));
      }
      String details = "duration=expired";
      String logId = recordAction(entry.getPlayerUuid(), "auto-unmute", "System", details);
      sendAuditLog(logId, "auto-unmute", Bukkit.getConsoleSender(), target, details);
      plugin.getLogger().info("VoiceChat auto-unmute: " + playerName(target, entry.getPlayerUuid().toString()));
    }
  }

  private void notifyGroupMembers(String groupName, String message, UUID excludedPlayer) {
    if (groupName == null || groupName.isBlank() || message == null || message.isBlank()) {
      return;
    }
    for (Player online : Bukkit.getOnlinePlayers()) {
      if (excludedPlayer != null && excludedPlayer.equals(online.getUniqueId())) {
        continue;
      }
      VoicechatConnection connection = getConnection(online);
      if (connection == null || connection.getGroup() == null) {
        continue;
      }
      if (safeGroupName(connection.getGroup()).equalsIgnoreCase(groupName)) {
        online.sendMessage(message);
      }
    }
  }

  private boolean checkCooldown(CommandSender sender, String action) {
    if (!(sender instanceof Player player)) {
      return true;
    }
    long cooldownMs = plugin.getConfig().getLong(BASE_PATH + ".cooldown-ms", 1500L);
    if (cooldownMs <= 0L || player.hasPermission("svca.bypasscooldown")) {
      return true;
    }
    String key = player.getUniqueId() + ":" + action;
    long now = System.currentTimeMillis();
    Long last = actionCooldowns.get(key);
    if (last != null && now - last < cooldownMs) {
      long remaining = Math.max(1L, (cooldownMs - (now - last) + 999L) / 1000L);
      sender.sendMessage(msg("messages.cooldown", "&eBitte warte %seconds% Sekunden.")
          .replace("%seconds%", String.valueOf(remaining)));
      return false;
    }
    actionCooldowns.put(key, now);
    return true;
  }

  private boolean requireConfirmation(CommandSender sender, String key, String prompt) {
    if (!(sender instanceof Player player)) {
      return true;
    }
    long windowMs = plugin.getConfig().getLong(BASE_PATH + ".confirm-window-ms", 7000L);
    if (windowMs <= 0L) {
      return true;
    }
    String fullKey = player.getUniqueId() + ":" + key;
    long now = System.currentTimeMillis();
    Long last = pendingConfirmations.get(fullKey);
    if (last != null && now - last <= windowMs) {
      pendingConfirmations.remove(fullKey);
      return true;
    }
    pendingConfirmations.put(fullKey, now);
    sender.sendMessage(prompt);
    return false;
  }

  private String[] prependDummy(String[] args) {
    String[] merged = new String[(args == null ? 0 : args.length) + 1];
    merged[0] = "_";
    if (args != null && args.length > 0) {
      System.arraycopy(args, 0, merged, 1, args.length);
    }
    return merged;
  }

  private String getTemporaryGroupName(String groupName) {
    if (groupName == null || groupName.isBlank()) {
      return null;
    }
    for (String temp : temporaryGroups) {
      if (temp.equalsIgnoreCase(groupName.trim())) {
        return temp;
      }
    }
    return null;
  }

  private boolean applyMuteState(OfflinePlayer target, boolean muted) {
    if (target == null) {
      return false;
    }
    VoicechatConnection connection = getConnection(target);
    if (connection == null) {
      return false;
    }
    connection.setDisabled(muted);
    return true;
  }

  private boolean applyPermissionMute(OfflinePlayer target) {
    if (target == null || !target.isOnline() || target.getPlayer() == null) {
      return false;
    }
    Player player = target.getPlayer();
    PermissionAttachment attachment = mutePermissions.get(player.getUniqueId());
    if (attachment == null) {
      attachment = player.addAttachment(plugin);
      mutePermissions.put(player.getUniqueId(), attachment);
    }
    attachment.setPermission("voicechat.speak", false);
    return true;
  }

  private boolean clearPermissionMute(OfflinePlayer target) {
    if (target == null || !target.isOnline() || target.getPlayer() == null) {
      return false;
    }
    Player player = target.getPlayer();
    PermissionAttachment attachment = mutePermissions.remove(player.getUniqueId());
    if (attachment != null) {
      try {
        attachment.remove();
      } catch (Exception ignored) {
      }
    }
    return true;
  }

  private void clearPermissionMutes() {
    for (PermissionAttachment attachment : mutePermissions.values()) {
      try {
        attachment.remove();
      } catch (Exception ignored) {
      }
    }
    mutePermissions.clear();
  }

  @EventHandler
  public void onPlayerJoin(PlayerJoinEvent event) {
    if (muteStore != null && muteStore.isMuted(event.getPlayer().getUniqueId())) {
      applyPermissionMute(event.getPlayer());
    }
  }

  @EventHandler
  public void onPlayerQuit(PlayerQuitEvent event) {
    activeListenSessions.remove(event.getPlayer().getUniqueId());
    activeSpySessions.remove(event.getPlayer().getUniqueId());
    PermissionAttachment attachment = mutePermissions.remove(event.getPlayer().getUniqueId());
    if (attachment != null) {
      try {
        attachment.remove();
      } catch (Exception ignored) {
      }
    }
  }

  private String getBroadcastGroupName() {
    return plugin.getConfig().getString(BASE_PATH + ".broadcast.group-name", "broadcast");
  }

  private String getBroadcastPassword() {
    String value = plugin.getConfig().getString(BASE_PATH + ".broadcast.password", "server-ip");
    if (value == null || value.isBlank()) {
      return "";
    }
    if ("server-ip".equalsIgnoreCase(value)) {
      return plugin.getServer().getIp();
    }
    return value;
  }

  private void sendAuditLog(String logId, String action, CommandSender sender, OfflinePlayer target, String details) {
    if (!plugin.getConfig().getBoolean(BASE_PATH + ".audit-webhook.enabled", false)) {
      return;
    }
    String url = plugin.getConfig().getString(BASE_PATH + ".audit-webhook.url", "");
    if (url == null || url.isBlank()) {
      return;
    }
    String actorName = sender == null ? "Unknown" : sender.getName();
    String targetName = target == null ? "All" : playerName(target, "Unknown");
    String actionLabel = prettifyAuditAction(action);
    String detailsPretty = prettifyAuditDetails(details);
    String template = plugin.getConfig().getString(
        BASE_PATH + ".audit-webhook.content",
        "[VoiceChat] %actor% -> %action% -> %target% (%details%)");
    String content = renderAuditTemplate(template, logId, actorName, action, actionLabel, targetName, target,
        details, detailsPretty);

    String username = plugin.getConfig().getString(BASE_PATH + ".audit-webhook.username", "VoiceChat Audit");
    String avatarUrl = plugin.getConfig().getString(BASE_PATH + ".audit-webhook.avatar-url", "");
    boolean useEmbed = plugin.getConfig().getBoolean(BASE_PATH + ".audit-webhook.embed.enabled", true);
    String title = renderAuditTemplate(
        plugin.getConfig().getString(BASE_PATH + ".audit-webhook.embed.title", "%action_label% | %target%"),
        logId, actorName, action, actionLabel, targetName, target, details, detailsPretty);
    String description = renderAuditTemplate(
        plugin.getConfig().getString(BASE_PATH + ".audit-webhook.embed.description",
            "Moderator: **%actor%**\nLog-ID: `%id%`\n\n%details_pretty%"),
        logId, actorName, action, actionLabel, targetName, target, details, detailsPretty);
    String footer = renderAuditTemplate(
        plugin.getConfig().getString(BASE_PATH + ".audit-webhook.embed.footer", "VoiceChat Admin"),
        logId, actorName, action, actionLabel, targetName, target, details, detailsPretty);
    int color = getAuditColor(action);

    sendWebhookAsync(url, username, avatarUrl, content, useEmbed, title, description, color, footer);
  }

  private int getAuditColor(String action) {
    String base = BASE_PATH + ".audit-webhook.embed.colors.";
    String normalized = action == null ? "" : action.toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "mute", "kick", "disconnect", "warn" ->
          plugin.getConfig().getInt(base + "red", 0xED4245);
      case "unmute", "unmuteall" ->
          plugin.getConfig().getInt(base + "green", 0x57F287);
      case "move", "pull", "create-group", "lock", "unlock", "reload", "listen", "unlisten", "spy", "unspy", "auto-unmute" ->
          plugin.getConfig().getInt(base + "blue", 0x3498DB);
      default -> plugin.getConfig().getInt(BASE_PATH + ".audit-webhook.embed.color", 3447003);
    };
  }

  private String renderAuditTemplate(String template, String logId, String actorName, String action, String actionLabel,
      String targetName, OfflinePlayer target, String details, String detailsPretty) {
    if (template == null) {
      return "";
    }
    return template
        .replace("\\n", "\n")
        .replace("%id%", logId == null ? "-" : logId)
        .replace("%actor%", actorName == null ? "" : actorName)
        .replace("%action%", action == null ? "" : action)
        .replace("%action_label%", actionLabel == null ? "" : actionLabel)
        .replace("%target%", targetName == null ? "" : targetName)
        .replace("%details%", details == null || details.isBlank() ? "-" : details)
        .replace("%details_pretty%", detailsPretty == null || detailsPretty.isBlank() ? "-" : detailsPretty)
        .replace("%target_uuid%", target == null ? "" : String.valueOf(target.getUniqueId()));
  }

  private String prettifyAuditAction(String action) {
    if (action == null || action.isBlank()) {
      return "Action";
    }
    return switch (action.toLowerCase(Locale.ROOT)) {
      case "mute" -> "Mute";
      case "unmute" -> "Unmute";
      case "move" -> "Move";
      case "kick" -> "Kick";
      case "warn" -> "Warning";
      case "note" -> "Note";
      case "note-remove" -> "Note Removed";
      case "purge-history" -> "History Purged";
      case "mute-edit" -> "Mute Edited";
      case "muteall" -> "Mass Mute";
      case "unmuteall" -> "Mass Unmute";
      case "lock" -> "Group Locked";
      case "unlock" -> "Group Unlocked";
      case "create-group" -> "Group Created";
      case "delete-group" -> "Group Deleted";
      case "pull" -> "Players Pulled";
      case "disconnect" -> "Disconnect";
      case "reload" -> "Voice Reload";
      case "listen" -> "Listen Mode";
      case "unlisten" -> "Listen Mode Disabled";
      case "spy" -> "Spy Mode";
      case "unspy" -> "Spy Mode Disabled";
      case "auto-unmute" -> "Auto-Unmute";
      default -> action.substring(0, 1).toUpperCase(Locale.ROOT) + action.substring(1);
    };
  }

  private String prettifyAuditDetails(String details) {
    if (details == null || details.isBlank()) {
      return "-";
    }
    String[] parts = details.split(",");
    List<String> formatted = new ArrayList<>();
    for (String part : parts) {
      String trimmed = part.trim();
      if (trimmed.isBlank()) {
        continue;
      }
      int idx = trimmed.indexOf('=');
      if (idx < 0) {
        formatted.add(trimmed);
        continue;
      }
      String key = trimmed.substring(0, idx).trim();
      String value = trimmed.substring(idx + 1).trim();
      formatted.add(prettifyAuditKey(key) + ": " + value);
    }
    return formatted.isEmpty() ? details : String.join("\n", formatted);
  }

  private String prettifyAuditKey(String key) {
    if (key == null || key.isBlank()) {
      return "Info";
    }
    return switch (key.toLowerCase(Locale.ROOT)) {
      case "duration" -> "Duration";
      case "reason" -> "Reason";
      case "from" -> "From";
      case "to" -> "To";
      case "group" -> "Group";
      case "count" -> "Count";
      case "note" -> "Note";
      default -> key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1);
    };
  }

  private void sendWebhookAsync(String url, String username, String avatarUrl, String content,
      boolean embed, String title, String description, int color, String footer) {
    String payload = buildWebhookPayload(username, avatarUrl, content, embed, title, description, color, footer);
    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
      try {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", "VoiceChatAdmin-Audit");
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(data.length);
        try (OutputStream out = connection.getOutputStream()) {
          out.write(data);
        }
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
          String body = readResponseBody(connection);
          if (body == null || body.isBlank()) {
            plugin.getLogger().warning("VoiceChat Discord webhook failed (HTTP " + code + ").");
          } else {
            plugin.getLogger().warning("VoiceChat Discord webhook failed (HTTP " + code + "): " + body);
          }
          if (plugin.getConfig().getBoolean("debug-discord", false)) {
            plugin.getLogger().warning("VoiceChat Discord webhook payload: " + payload);
          }
        }
      } catch (Exception ex) {
        plugin.getLogger().warning("VoiceChat Discord webhook failed: " + ex.getMessage());
      }
    });
  }

  private void sendExportWebhook(String action, CommandSender sender, OfflinePlayer target, String export) {
    if (!plugin.getConfig().getBoolean(BASE_PATH + ".audit-webhook.enabled", false)) {
      return;
    }
    String url = plugin.getConfig().getString(BASE_PATH + ".audit-webhook.url", "");
    if (url == null || url.isBlank()) {
      return;
    }
    String username = plugin.getConfig().getString(BASE_PATH + ".audit-webhook.username", "VoiceChat Admin");
    String avatarUrl = plugin.getConfig().getString(BASE_PATH + ".audit-webhook.avatar-url", "");
    String title = "Export | " + (target == null ? "Unknown" : playerName(target, "Unknown"));
    String header = "Erstellt von: " + (sender == null ? "Unknown" : sender.getName());
    sendWebhookAsync(url, username, avatarUrl, "", true, title,
        header + "\n\n```" + trimForDiscord(export, 1500) + "```", getAuditColor(action), "VoiceChat Admin");
  }

  private String trimForDiscord(String text, int limit) {
    if (text == null) {
      return "";
    }
    if (text.length() <= limit) {
      return text;
    }
    return text.substring(0, Math.max(0, limit - 3)) + "...";
  }

  private String readResponseBody(HttpURLConnection connection) {
    if (connection == null) {
      return "";
    }
    InputStream stream = null;
    try {
      stream = connection.getErrorStream();
      if (stream == null) {
        stream = connection.getInputStream();
      }
      if (stream == null) {
        return "";
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[1024];
      int read;
      while ((read = stream.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return out.toString(StandardCharsets.UTF_8.name());
    } catch (Exception ignored) {
      return "";
    } finally {
      if (stream != null) {
        try {
          stream.close();
        } catch (Exception ignored) {
        }
      }
    }
  }

  private String buildWebhookPayload(String username, String avatarUrl, String content,
      boolean embed, String title, String description, int color, String footer) {
    StringBuilder payload = new StringBuilder();
    payload.append('{');
    boolean wrote = false;
    if (embed) {
      payload.append("\"embeds\":[{");
      boolean embedWrote = false;
      if (title != null && !title.isBlank()) {
        payload.append("\"title\":\"").append(escapeJson(title)).append("\"");
        embedWrote = true;
      }
      if (description != null && !description.isBlank()) {
        if (embedWrote) {
          payload.append(',');
        }
        payload.append("\"description\":\"").append(escapeJson(description)).append("\"");
        embedWrote = true;
      }
      if (embedWrote) {
        payload.append(',');
      }
      payload.append("\"color\":").append(color);
      if (footer != null && !footer.isBlank()) {
        payload.append(",\"footer\":{\"text\":\"").append(escapeJson(footer)).append("\"}");
      }
      String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      payload.append(",\"timestamp\":\"").append(escapeJson(timestamp)).append("\"");
      payload.append("}]");
      wrote = true;
    } else {
      payload.append("\"content\":\"").append(escapeJson(content == null ? "" : content)).append("\"");
      wrote = true;
    }
    if (username != null && !username.isBlank()) {
      if (wrote) {
        payload.append(',');
      }
      payload.append("\"username\":\"").append(escapeJson(username)).append("\"");
      wrote = true;
    }
    if (avatarUrl != null && !avatarUrl.isBlank()) {
      if (wrote) {
        payload.append(',');
      }
      payload.append("\"avatar_url\":\"").append(escapeJson(avatarUrl)).append("\"");
    }
    payload.append('}');
    return payload.toString();
  }

  private String escapeJson(String input) {
    if (input == null || input.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder(input.length() + 16);
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      switch (c) {
        case '\\':
          out.append("\\\\");
          break;
        case '"':
          out.append("\\\"");
          break;
        case '\b':
          out.append("\\b");
          break;
        case '\f':
          out.append("\\f");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
      }
    }
    return out.toString();
  }

  private String safeGroupName(Group group) {
    if (group == null || group.getName() == null || group.getName().isBlank()) {
      return msg("messages.no-group", "&7Keine");
    }
    return group.getName();
  }

  private String formatRemainingMute(long remainingMs) {
    if (remainingMs < 0L) {
      return msg("messages.mute-permanent", "&cPermanent");
    }
    if (remainingMs == 0L) {
      return msg("messages.not-muted", "&aNein");
    }
    long totalSeconds = Math.max(1L, remainingMs / 1000L);
    long hours = totalSeconds / 3600L;
    long minutes = (totalSeconds % 3600L) / 60L;
    long seconds = totalSeconds % 60L;
    if (hours > 0L) {
      return hours + "h " + minutes + "m";
    }
    if (minutes > 0L) {
      return minutes + "m " + seconds + "s";
    }
    return seconds + "s";
  }

  private String formatMuteDuration(long durationMs) {
    if (durationMs <= 0L) {
      return "permanent";
    }
    long totalMinutes = durationMs / 60_000L;
    if (totalMinutes >= 60L) {
      long hours = totalMinutes / 60L;
      long minutes = totalMinutes % 60L;
      return minutes == 0L ? hours + "h" : hours + "h " + minutes + "m";
    }
    return totalMinutes + "m";
  }

  private ParsedMuteInput parseMuteInput(String[] args) {
    if (args == null || args.length <= 1) {
      return new ParsedMuteInput(true, 0L, "");
    }
    long durationMs = 0L;
    int reasonStart = 1;
    try {
      long minutes = Long.parseLong(args[1]);
      if (minutes < 0L) {
        return new ParsedMuteInput(false, 0L, "");
      }
      durationMs = minutes <= 0L ? 0L : minutes * 60_000L;
      reasonStart = 2;
    } catch (NumberFormatException ignored) {
      durationMs = 0L;
      reasonStart = 1;
    }
    String reason = args.length > reasonStart
        ? String.join(" ", java.util.Arrays.copyOfRange(args, reasonStart, args.length)).trim()
        : "";
    return new ParsedMuteInput(true, durationMs, reason);
  }

  private String recordAction(UUID targetUuid, String action, String actor, String details) {
    String id = "VC-" + String.format("%06d", auditSequence.incrementAndGet());
    if (targetUuid == null) {
      return id;
    }
    Deque<VoiceActionRecord> records = actionHistory.computeIfAbsent(targetUuid, key -> new ArrayDeque<>());
    records.addFirst(new VoiceActionRecord(id, System.currentTimeMillis(), action,
        actor == null ? "" : actor, details == null ? "" : details));
    while (records.size() > MAX_HISTORY_PER_PLAYER) {
      records.removeLast();
    }
    return id;
  }

  private List<String> getKnownPlayerNames() {
    List<String> names = new ArrayList<>();
    for (Player player : Bukkit.getOnlinePlayers()) {
      names.add(player.getName());
    }
    for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
      String name = player.getName();
      if (name != null && !name.isBlank()) {
        names.add(name);
      }
    }
    names.sort(String.CASE_INSENSITIVE_ORDER);
    List<String> unique = new ArrayList<>();
    String last = null;
    for (String name : names) {
      if (last == null || !last.equalsIgnoreCase(name)) {
        unique.add(name);
        last = name;
      }
    }
    return unique;
  }

  private List<String> getKnownGroupNames() {
    List<String> names = new ArrayList<>();
    VoicechatServerApi api = getVoicechatApi();
    if (api != null) {
      for (Group group : api.getGroups()) {
        if (group != null && group.getName() != null && !group.getName().isBlank()) {
          names.add(group.getName());
        }
      }
    }
    for (String temp : temporaryGroups) {
      names.add(temp);
    }
    names.sort(String.CASE_INSENSITIVE_ORDER);
    return names;
  }

  private List<String> filterByPrefix(List<String> values, String prefix) {
    if (values.isEmpty()) {
      return Collections.emptyList();
    }
    String normalized = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
    List<String> matches = new ArrayList<>();
    for (String value : values) {
      if (normalized.isEmpty() || value.toLowerCase(Locale.ROOT).startsWith(normalized)) {
        matches.add(value);
      }
    }
    return matches;
  }

  private String formatTimestamp(long timestampMs) {
    return DateTimeFormatter.ofPattern("dd.MM HH:mm").format(
        ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestampMs), java.time.ZoneId.systemDefault()));
  }

  private String playerName(OfflinePlayer player, String fallback) {
    if (player == null || player.getName() == null || player.getName().isBlank()) {
      return fallback;
    }
    return player.getName();
  }

  private String msg(String path, String fallback) {
    return color(resolveMessage(localeContext.get(), path, fallback));
  }

  private String resolveMessage(String localeKey, String path, String fallback) {
    String localized = getLocalizedMessage(localeKey, path);
    if (localized != null && !localized.isBlank()) {
      return localized;
    }
    String legacy = plugin.getConfig().getString(BASE_PATH + "." + path);
    return legacy == null || legacy.isBlank() ? fallback : legacy;
  }

  private String getLocalizedMessage(String localeKey, String path) {
    for (String candidate : getLocaleCandidates(localeKey)) {
      String value = plugin.getLocaleMessage(candidate, path);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private List<String> getLocaleCandidates(String localeKey) {
    List<String> candidates = new ArrayList<>();
    addLocaleCandidate(candidates, localeKey);
    addLocaleCandidate(candidates, getDefaultLocale());
    addLocaleCandidate(candidates, getFallbackLocale());
    return candidates;
  }

  private void addLocaleCandidate(List<String> candidates, String localeKey) {
    String normalized = normalizeLocale(localeKey);
    if (normalized == null) {
      return;
    }
    if (!candidates.contains(normalized)) {
      candidates.add(normalized);
    }
    int separator = normalized.indexOf('_');
    if (separator > 0) {
      String languageOnly = normalized.substring(0, separator);
      if (!candidates.contains(languageOnly)) {
        candidates.add(languageOnly);
      }
    }
  }

  private String resolveLocale(CommandSender sender) {
    if (shouldUsePlayerLocale() && sender instanceof Player player) {
      String matched = matchConfiguredLocale(player.getLocale());
      if (matched != null) {
        return matched;
      }
    }
    return matchConfiguredLocale(getDefaultLocale());
  }

  private String matchConfiguredLocale(String localeKey) {
    String normalized = normalizeLocale(localeKey);
    if (normalized == null) {
      return normalizeLocale(getFallbackLocale());
    }
    if (plugin.hasLocale(normalized)) {
      return normalized;
    }
    int separator = normalized.indexOf('_');
    if (separator > 0) {
      String languageOnly = normalized.substring(0, separator);
      if (plugin.hasLocale(languageOnly)) {
        return languageOnly;
      }
    }
    for (String key : plugin.getAvailableLocales()) {
      String configured = normalizeLocale(key);
      if (normalized.equals(configured)) {
        return configured;
      }
      if (configured != null && normalized.startsWith(configured + "_")) {
        return configured;
      }
    }
    return normalized;
  }

  private String getDefaultLocale() {
    return plugin.getConfig().getString(LOCALIZATION_PATH + ".default-locale", "en");
  }

  private String getFallbackLocale() {
    return plugin.getConfig().getString(LOCALIZATION_PATH + ".fallback-locale", "en");
  }

  private boolean shouldUsePlayerLocale() {
    return plugin.getConfig().getBoolean(LOCALIZATION_PATH + ".use-player-locale", true);
  }

  private String normalizeLocale(String localeKey) {
    if (localeKey == null || localeKey.isBlank()) {
      return null;
    }
    return localeKey.trim().replace('-', '_').toLowerCase(Locale.ROOT);
  }

  private String color(String input) {
    return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
  }

  private record ParsedMuteInput(boolean valid, long durationMs, String reason) {
  }

  private record VoiceActionRecord(String id, long createdAtMs, String action, String actor, String details) {
  }

  private record VoiceNote(long createdAtMs, String actor, String text) {
  }

  private record VoiceWarning(long createdAtMs, String actor, String reason) {
  }
}
