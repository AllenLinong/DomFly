package top.wunanc.domfly.handler;

import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.api.DominionAPI;
import cn.lunadeer.dominion.api.dtos.flag.Flags;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import top.wunanc.domfly.config.Configuration;
import top.wunanc.domfly.config.LanguageManager;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Fly implements Listener {

    private final JavaPlugin plugin;
    private final Configuration configuration;
    private final Set<UUID> flyingPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> autoFlyOffPlayers = ConcurrentHashMap.newKeySet();
    private final LanguageManager languageManager;
    private final DominionAPI dominionAPI = DominionAPI.getInstance();

    public Fly(JavaPlugin plugin, Configuration configuration, LanguageManager languageManager) {
        this.plugin = plugin;
        this.configuration = configuration;
        this.languageManager = languageManager;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public boolean isFlying(UUID uuid) {
        return flyingPlayers.contains(uuid);
    }

    /**
     * 检查玩家的自动飞行功能是否开启
     * 需要同时满足：全局配置开启 且 玩家个人未关闭
     */
    public boolean isAutoFlyEnabled(UUID uuid) {
        return configuration.isAutoFly() && !autoFlyOffPlayers.contains(uuid);
    }

    /**
     * 切换玩家的自动飞行开关
     * @return true = 已开启, false = 已关闭
     */
    public boolean toggleAutoFly(Player player) {
        UUID uuid = player.getUniqueId();
        if (autoFlyOffPlayers.contains(uuid)) {
            autoFlyOffPlayers.remove(uuid);
            return true;
        } else {
            autoFlyOffPlayers.add(uuid);
            return false;
        }
    }

    public void executeFlyCommand(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            player.sendMessage(languageManager.getMessage("IsCreativeMode"));
            return;
        }

        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.sendMessage(languageManager.getMessage("IsSpectatorMode"));
            return;
        }

        if (!player.hasPermission("domfly.use")) {
            player.sendMessage(languageManager.getMessage("NoPermission"));
            return;
        }

        if (isInOwnClaim(player)) {
            // 切换飞行状态
            toggleFlight(player);
        } else {
            player.sendMessage(languageManager.getMessage("OnlyOwner"));

            if (configuration.isDebug()) {
                DominionDTO dominion = dominionAPI.getDominion(player.getLocation());
                String dominionName = dominion != null ? dominion.getName() : "无";
                plugin.getLogger().info("DEBUG - 玩家: " + player.getName() + " 尝试开启飞行，但不在其领地。所在领地: " + dominionName);
            }
        }
    }

    public void toggleFlight(Player player) {
        if (flyingPlayers.contains(player.getUniqueId())) {
            disableFlight(player);
            player.sendMessage(languageManager.getMessage("FlyDisabled"));
        } else {
            enableFlight(player);
            player.sendMessage(languageManager.getMessage("FlyEnabled"));
        }
    }

    private void enableFlight(Player player) {
        flyingPlayers.add(player.getUniqueId());
        player.getScheduler().execute(plugin, () -> {
            player.setAllowFlight(true);
            player.setFlying(true);
        }, null, 0);
    }

    private void disableFlight(Player player) {
        flyingPlayers.remove(player.getUniqueId());
        player.getScheduler().execute(plugin, () -> {
            player.setAllowFlight(false);
            // 不取消鞘翅滑翔状态，防止离开领地时坠机
            if (!player.isGliding()) {
                player.setFlying(false);
            }
        }, null, 0);
    }

    private boolean isInOwnClaim(Player player) {
        try {
            DominionDTO dominion = DominionAPI.getInstance().getDominion(player.getLocation());
            if (dominion == null) return false;

            if (dominion.getOwner().equals(player.getUniqueId())) return true;

            return DominionAPI.getInstance().checkPrivilegeFlag(
                    player.getLocation(),
                    Flags.FLY,
                    player
            );
        } catch (Exception e) {
            plugin.getLogger().warning("领地检查错误: " + e.getMessage());
            return false;
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        // 只处理实际移动了位置的事件 (过滤掉只转动视角的事件)
        if (!event.hasChangedBlock()) return;

        boolean inOwnClaim = isInOwnClaim(player);
        boolean isCurrentlyFlying = flyingPlayers.contains(player.getUniqueId());

        if (isCurrentlyFlying) {
            // 玩家正在飞行，检查是否离开领地
            if (!inOwnClaim) {
                disableFlight(player);
                player.sendMessage(languageManager.getMessage("AutoDisable"));
            }
        } else if (isAutoFlyEnabled(player.getUniqueId())) {
            // 自动飞行模式：玩家未在飞行，检查是否进入领地
            if (inOwnClaim
                    && player.hasPermission("domfly.use")
                    && player.getGameMode() != GameMode.CREATIVE
                    && player.getGameMode() != GameMode.SPECTATOR) {
                enableFlight(player);
                player.sendMessage(languageManager.getMessage("AutoEnable"));
            }
        }
    }

    /**
     * 监听玩家登入：强制重置飞行状态，防止跨服传送后客户端残留飞行状态
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 延迟检查：确保登录时领地/区块数据已加载完毕
        player.getScheduler().execute(plugin, () -> {
            boolean inOwnClaim = isInOwnClaim(player);
            boolean isCreativeOrSpectator = player.getGameMode() == GameMode.CREATIVE
                    || player.getGameMode() == GameMode.SPECTATOR;

            // 清理内存中的残留记录（如果存在）
            flyingPlayers.remove(player.getUniqueId());

            if (isCreativeOrSpectator) return; // 创造/旁观模式不干预

            if (inOwnClaim && player.hasPermission("domfly.use")) {
                // 在领地内：按自动飞行模式决定是否开启
                if (isAutoFlyEnabled(player.getUniqueId())) {
                    enableFlight(player);
                    player.sendMessage(languageManager.getMessage("AutoEnable"));
                }
            } else {
                // 不在领地内：强制关闭飞行，清除跨服带来的客户端残留
                player.setAllowFlight(false);
                player.setFlying(false);
            }
        }, null, 20L); // 延迟1秒，等待区块和领地数据加载
    }

    /**
     * 监听世界切换：玩家切换到其他世界后，检查是否仍在领地内
     * 如果不在领地内，自动关闭飞行，防止跨世界无限飞行
     */
    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!flyingPlayers.contains(player.getUniqueId())) return;

        // 延迟检查：等待世界加载完成后检查领地状态
        player.getScheduler().execute(plugin, () -> {
            if (!isInOwnClaim(player)) {
                disableFlight(player);
                player.sendMessage(languageManager.getMessage("FlyDisabledByWorldChange"));
            }
        }, null, 20L); // 延迟1秒，确保领地数据在新世界中已加载
    }

    /**
     * 监听玩家传送：传送后检查是否仍在领地内（仅处理同世界传送）
     * 跨世界传送由 onPlayerChangedWorld 处理，避免重复通知
     */
    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        // 跨世界传送交给 PlayerChangedWorldEvent 处理，此处只处理同世界传送
        if (event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            Player player = event.getPlayer();
            if (!flyingPlayers.contains(player.getUniqueId())) return;

            // 延迟检查：等待传送完成后检查领地状态
            player.getScheduler().execute(plugin, () -> {
                if (!isInOwnClaim(player)) {
                    disableFlight(player);
                    player.sendMessage(languageManager.getMessage("AutoDisable"));
                }
            }, null, 5L);
        }
    }

    /**
     * 监听玩家退出：清理集合防止内存泄漏，并重置状态
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (flyingPlayers.contains(player.getUniqueId())) {
            disableFlight(player);
        }
        autoFlyOffPlayers.remove(player.getUniqueId());
    }

    /**
     * 关服清理：强制重置所有在线玩家的飞行状态
     */
    public void disableAllFlight() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (flyingPlayers.contains(player.getUniqueId())) {
                player.getScheduler().execute(plugin, () -> {
                    player.setAllowFlight(false);
                    player.setFlying(false);
                }, null, 0);
            }
        }
        flyingPlayers.clear();
    }

    /**
     * 强制关闭方法 (供管理员命令或权限变动钩子使用)
     */
    public void forceDisableFlight(Player player, Boolean isAuto) {
        if (flyingPlayers.contains(player.getUniqueId())) {
            disableFlight(player);
            if (!isAuto) player.sendMessage(languageManager.getMessage("SudoDisabled"));
            else player.sendMessage(languageManager.getMessage("DeprivedOfFlightPrivileges"));
        } else {
            player.getScheduler().execute(plugin, () -> {
                player.setAllowFlight(false);
                player.setFlying(false);
            }, null, 0);
        }
    }

    public int getFlyingPlayerCount() {
        return flyingPlayers.size();
    }
}
