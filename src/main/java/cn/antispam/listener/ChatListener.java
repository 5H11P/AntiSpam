package cn.antispam.listener;

import cn.antispam.AntiSpamPlugin;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatListener implements Listener {
    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("(?i)&[0-9A-FK-OR]");
    private static final Pattern SPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern PUNCTUATION_PATTERN = Pattern.compile("[\\p{Punct}，。！？、；：“”‘’（）【】《》￥…]+");
    private static final Pattern DOT_WORD_PATTERN = Pattern.compile("(?i)(?:\\s|^)(?:dot|点)(?:\\s|$)");
    private static final Pattern BRACKET_DOT_PATTERN = Pattern.compile("(?i)(?:\\(\\s*\\.\\s*\\)|\\[\\s*\\.\\s*\\]|\\{\\s*\\.\\s*\\})");
    private static final Pattern DOT_SEPARATOR_PATTERN = Pattern.compile("\\s*[\\.。．。·]\\s*");
    private static final Pattern COLON_SEPARATOR_PATTERN = Pattern.compile("\\s*[:：]\\s*");
    private static final Pattern NON_ADDRESS_CHAR_PATTERN = Pattern.compile("[^a-z0-9\\.:\\-]");

    private final AntiSpamPlugin plugin;
    private final Map<UUID, PlayerChatState> states = new ConcurrentHashMap<UUID, PlayerChatState>();

    private String bypassAllPermission;
    private boolean cooldownEnabled;
    private int defaultCooldownSeconds;
    private String cooldownBypassPermission;
    private List<CooldownGroup> cooldownGroups;
    private boolean antiSpamEnabled;
    private String antiSpamBypassPermission;
    private int maxRepeatTimes;
    private long resetSpamMillis;
    private double similarityThreshold;
    private int minMessageLength;
    private int historySize;
    private boolean antiAdvertiseEnabled;
    private String antiAdvertiseBypassPermission;
    private boolean checkIp;
    private boolean checkAddressWithPort;
    private boolean checkDomain;
    private Set<String> blockedTlds;
    private Set<String> whitelist;
    private Pattern addressPattern;

    public ChatListener(AntiSpamPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        bypassAllPermission = plugin.getConfig().getString("settings.bypass-all-permission", "antispam.bypass.all");

        cooldownEnabled = plugin.getConfig().getBoolean("cooldown.enabled", true);
        defaultCooldownSeconds = Math.max(0, plugin.getConfig().getInt("cooldown.default-seconds", 3));
        cooldownBypassPermission = plugin.getConfig().getString("cooldown.bypass-permission", "antispam.bypass.cooldown");
        cooldownGroups = loadCooldownGroups();

        antiSpamEnabled = plugin.getConfig().getBoolean("anti-spam.enabled", true);
        antiSpamBypassPermission = plugin.getConfig().getString("anti-spam.bypass-permission", "antispam.bypass.spam");
        maxRepeatTimes = Math.max(1, plugin.getConfig().getInt("anti-spam.max-repeat-times", 3));
        resetSpamMillis = Math.max(1, plugin.getConfig().getLong("anti-spam.reset-spam-time", 10)) * 1000L;
        similarityThreshold = clamp(plugin.getConfig().getDouble("anti-spam.similarity-threshold", 0.85), 0.0D, 1.0D);
        minMessageLength = Math.max(1, plugin.getConfig().getInt("anti-spam.min-message-length", 3));
        historySize = Math.max(1, plugin.getConfig().getInt("anti-spam.history-size", 5));

        antiAdvertiseEnabled = plugin.getConfig().getBoolean("anti-advertise.enabled", true);
        antiAdvertiseBypassPermission = plugin.getConfig().getString("anti-advertise.bypass-permission", "antispam.bypass.advertise");
        checkIp = plugin.getConfig().getBoolean("anti-advertise.check-ip", true);
        checkAddressWithPort = plugin.getConfig().getBoolean("anti-advertise.check-address-with-port", true);
        checkDomain = plugin.getConfig().getBoolean("anti-advertise.check-domain", true);
        blockedTlds = loadBlockedTlds();
        whitelist = loadWhitelist();
        addressPattern = buildAddressPattern();
    }

    public void clearStates() {
        states.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (hasPermission(player, bypassAllPermission)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        PlayerChatState state = getState(uuid);

        synchronized (state) {
            String message = event.getMessage();
            if (antiAdvertiseEnabled && !hasPermission(player, antiAdvertiseBypassPermission) && containsBlockedAddress(message)) {
                event.setCancelled(true);
                send(player, plugin.getMessage("ip-blocked"));
                return;
            }

            if (antiSpamEnabled && !hasPermission(player, antiSpamBypassPermission) && isSpamBlocked(state, normalize(message), now)) {
                event.setCancelled(true);
                send(player, plugin.getMessage("spam-blocked"));
                return;
            }

            if (cooldownEnabled && isCooldownBlocked(player, state, now)) {
                event.setCancelled(true);
                send(player, plugin.getMessage("cooldown-blocked").replace("%seconds%", String.valueOf(getRemainingCooldownSeconds(player, state, now))));
                return;
            }

            state.lastChatTime = now;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    private boolean isCooldownBlocked(Player player, PlayerChatState state, long now) {
        int cooldownSeconds = getCooldownSeconds(player);
        if (cooldownSeconds <= 0) {
            return false;
        }

        long elapsed = now - state.lastChatTime;
        return state.lastChatTime > 0L && elapsed < cooldownSeconds * 1000L;
    }

    private int getRemainingCooldownSeconds(Player player, PlayerChatState state, long now) {
        int cooldownSeconds = getCooldownSeconds(player);
        long elapsed = now - state.lastChatTime;
        long remainingMillis = cooldownSeconds * 1000L - elapsed;
        return Math.max(1, (int) Math.ceil(remainingMillis / 1000.0D));
    }

    private int getCooldownSeconds(Player player) {
        if (hasPermission(player, cooldownBypassPermission)) {
            return 0;
        }

        int seconds = defaultCooldownSeconds;
        for (CooldownGroup group : cooldownGroups) {
            if (hasPermission(player, group.permission)) {
                seconds = Math.min(seconds, group.seconds);
            }
        }
        return seconds;
    }

    private boolean isSpamBlocked(PlayerChatState state, String normalizedMessage, long now) {
        if (normalizedMessage.length() < minMessageLength) {
            addHistory(state, normalizedMessage);
            return false;
        }

        if (now - state.lastSimilarMessageTime > resetSpamMillis) {
            state.repeatCount = 0;
            state.lastSpamMessage = null;
        }

        boolean similar = state.lastSpamMessage != null && similarity(normalizedMessage, state.lastSpamMessage) >= similarityThreshold;

        if (similar) {
            state.repeatCount++;
        } else {
            state.repeatCount = 1;
            state.lastSpamMessage = normalizedMessage;
        }

        state.lastSimilarMessageTime = now;
        addHistory(state, normalizedMessage);
        return state.repeatCount > maxRepeatTimes;
    }

    private boolean containsBlockedAddress(String message) {
        if (addressPattern == null) {
            return false;
        }

        String lowerMessage = normalizeAddressMessage(message);
        Matcher matcher = addressPattern.matcher(lowerMessage);
        while (matcher.find()) {
            if (!isWhitelistedAddress(matcher.group())) {
                return true;
            }
        }
        return false;
    }

    private boolean isWhitelistedAddress(String address) {
        String normalized = normalizeAddressToken(address);
        if (whitelist.contains(normalized)) {
            return true;
        }

        int colonIndex = normalized.indexOf(':');
        if (colonIndex > 0 && whitelist.contains(normalized.substring(0, colonIndex))) {
            return true;
        }

        return false;
    }

    private Pattern buildAddressPattern() {
        List<String> patterns = new ArrayList<String>();

        String ip = "(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
        if (checkAddressWithPort) {
            patterns.add("\\b" + ip + "\\s*:\\s*\\d{2,5}\\b");
        }
        if (checkIp) {
            patterns.add("\\b" + ip + "\\b");
        }
        if (checkDomain) {
            String tldPattern = buildTldPattern();
            if (!tldPattern.isEmpty()) {
                patterns.add("\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+" + tldPattern + "\\b");
            }
        }

        if (patterns.isEmpty()) {
            return null;
        }
        return Pattern.compile(join(patterns, "|"), Pattern.CASE_INSENSITIVE);
    }

    private String normalizeAddressMessage(String message) {
        String normalized = ChatColor.stripColor(plugin.color(message));
        normalized = COLOR_CODE_PATTERN.matcher(normalized).replaceAll("");
        normalized = normalized.toLowerCase(Locale.ENGLISH);
        normalized = BRACKET_DOT_PATTERN.matcher(normalized).replaceAll(".");
        normalized = DOT_WORD_PATTERN.matcher(normalized).replaceAll(".");
        normalized = DOT_SEPARATOR_PATTERN.matcher(normalized).replaceAll(".");
        normalized = COLON_SEPARATOR_PATTERN.matcher(normalized).replaceAll(":");
        normalized = NON_ADDRESS_CHAR_PATTERN.matcher(normalized).replaceAll("");
        return normalized;
    }

    private String normalizeAddressToken(String address) {
        return normalizeAddressMessage(address).replaceAll("^\\.+|\\.+$", "");
    }

    private String buildTldPattern() {
        List<String> tlds = new ArrayList<String>(blockedTlds);
        StringBuilder builder = new StringBuilder("(?:");
        for (int i = 0; i < tlds.size(); i++) {
            if (i > 0) {
                builder.append("|");
            }
            builder.append(Pattern.quote(tlds.get(i)));
        }
        builder.append(")");
        return tlds.isEmpty() ? "" : builder.toString();
    }

    private List<CooldownGroup> loadCooldownGroups() {
        List<CooldownGroup> groups = new ArrayList<CooldownGroup>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("cooldown.permission-groups");
        if (section == null) {
            return groups;
        }

        for (String key : section.getKeys(false)) {
            String permission = section.getString(key + ".permission", "");
            int seconds = Math.max(0, section.getInt(key + ".seconds", defaultCooldownSeconds));
            if (permission != null && !permission.isEmpty()) {
                groups.add(new CooldownGroup(permission, seconds));
            }
        }
        return groups;
    }

    private Set<String> loadWhitelist() {
        Set<String> result = new HashSet<String>();
        for (String value : plugin.getConfig().getStringList("anti-advertise.whitelist")) {
            String normalized = normalizeAddressToken(value);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private Set<String> loadBlockedTlds() {
        Set<String> result = new HashSet<String>();
        List<String> configured = plugin.getConfig().getStringList("anti-advertise.blocked-tlds");
        if (configured.isEmpty()) {
            configured = Arrays.asList("com", "net", "org", "cn", "cc", "top", "xyz", "vip", "icu", "shop", "store", "site", "online", "fun", "club", "pro", "me", "io", "gg", "ink", "link", "info", "biz", "tv", "asia", "pw", "run", "live", "world", "space", "cloud", "app", "dev", "tech", "host", "network", "games", "game", "wiki", "work", "lol");
        }

        for (String value : configured) {
            String tld = value.toLowerCase(Locale.ENGLISH).replace(".", "").trim();
            if (tld.matches("[a-z0-9-]{2,24}")) {
                result.add(tld);
            }
        }
        return result;
    }

    private PlayerChatState getState(UUID uuid) {
        PlayerChatState state = states.get(uuid);
        if (state == null) {
            state = new PlayerChatState();
            states.put(uuid, state);
        }
        return state;
    }

    private void addHistory(PlayerChatState state, String message) {
        state.recentMessages.addLast(message);
        while (state.recentMessages.size() > historySize) {
            state.recentMessages.removeFirst();
        }
    }

    private String normalize(String message) {
        String normalized = ChatColor.stripColor(plugin.color(message));
        normalized = COLOR_CODE_PATTERN.matcher(normalized).replaceAll("");
        normalized = normalized.toLowerCase(Locale.ENGLISH);
        normalized = PUNCTUATION_PATTERN.matcher(normalized).replaceAll("");
        normalized = SPACE_PATTERN.matcher(normalized).replaceAll(" ").trim();
        return normalized;
    }

    private double similarity(String first, String second) {
        int maxLength = Math.max(first.length(), second.length());
        if (maxLength == 0) {
            return 1.0D;
        }
        return 1.0D - ((double) levenshtein(first, second) / (double) maxLength);
    }

    private int levenshtein(String first, String second) {
        int[] previous = new int[second.length() + 1];
        int[] current = new int[second.length() + 1];

        for (int j = 0; j <= second.length(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= first.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= second.length(); j++) {
                int cost = first.charAt(i - 1) == second.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }

        return previous[second.length()];
    }

    private boolean hasPermission(Player player, String permission) {
        return permission != null && !permission.isEmpty() && player.hasPermission(permission);
    }

    private void send(Player player, String message) {
        player.sendMessage(plugin.color(message));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(separator);
            }
            builder.append("(?:").append(values.get(i)).append(")");
        }
        return builder.toString();
    }

    private static final class CooldownGroup {
        private final String permission;
        private final int seconds;

        private CooldownGroup(String permission, int seconds) {
            this.permission = permission;
            this.seconds = seconds;
        }
    }

    private static final class PlayerChatState {
        private final ArrayDeque<String> recentMessages = new ArrayDeque<String>();
        private long lastChatTime;
        private long lastSimilarMessageTime;
        private String lastSpamMessage;
        private int repeatCount;
    }
}
