package top.wunanc.domfly.hooks;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import top.wunanc.domfly.config.LanguageManager;
import top.wunanc.domfly.handler.Fly;

/**
 * PlaceholderAPI 扩展，提供 DomFly 飞行状态占位符
 *
 * 可用占位符:
 *   %domfly_flying%   - 返回当前飞行状态文本 (可在 lang.yml 的 Placeholder 节点下自定义)
 *   %domfly_autofly%  - 返回自动飞行开关状态 (可在 lang.yml 的 Placeholder 节点下自定义)
 */
public class DomFlyExpansion extends PlaceholderExpansion {

    private final JavaPlugin plugin;
    private final Fly fly;
    private final LanguageManager languageManager;

    public DomFlyExpansion(JavaPlugin plugin, Fly fly, LanguageManager languageManager) {
        this.plugin = plugin;
        this.fly = fly;
        this.languageManager = languageManager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "domfly";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // 跨重载保持注册
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String identifier) {
        // %domfly_flying% - 返回飞行状态文本
        if (identifier.equalsIgnoreCase("flying")) {
            Component message;
            if (fly.isFlying(player.getUniqueId())) {
                message = languageManager.getMessage("Placeholder.Flying");
            } else {
                message = languageManager.getMessage("Placeholder.NotFlying");
            }
            return LegacyComponentSerializer.legacySection().serialize(message);
        }

        // %domfly_autofly% - 返回自动飞行开关状态
        if (identifier.equalsIgnoreCase("autofly")) {
            Component message;
            if (fly.isAutoFlyEnabled(player.getUniqueId())) {
                message = languageManager.getMessage("Placeholder.AutoFlyEnabled");
            } else {
                message = languageManager.getMessage("Placeholder.AutoFlyDisabled");
            }
            return LegacyComponentSerializer.legacySection().serialize(message);
        }

        return null; // 未知占位符
    }
}
