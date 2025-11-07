package eternalpolar.spigot.karoanticheat.Utils;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class ActionbarUtils {
    private static final boolean USE_OLD_METHOD;
    private static String nmsVersion;

    static {
        String packageName = Bukkit.getServer().getClass().getPackage().getName();
        nmsVersion = packageName.substring(packageName.lastIndexOf('.') + 1);
        USE_OLD_METHOD = nmsVersion.startsWith("v1_8") || nmsVersion.startsWith("v1_7");
    }

    public static void sendActionbar(Player player, String message) {
        if (player == null || !player.isOnline()) return;
        String colored = message.replace("&", "§");

        if (USE_OLD_METHOD) {
            try {
                Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit." + nmsVersion + ".entity.CraftPlayer");
                Class<?> packetPlayOutChatClass = Class.forName("net.minecraft.server." + nmsVersion + ".PacketPlayOutChat");
                Class<?> chatComponentTextClass = Class.forName("net.minecraft.server." + nmsVersion + ".ChatComponentText");
                Class<?> iChatBaseComponentClass = Class.forName("net.minecraft.server." + nmsVersion + ".IChatBaseComponent");
                Class<?> packetClass = Class.forName("net.minecraft.server." + nmsVersion + ".Packet");

                Object chatComponent = chatComponentTextClass.getConstructor(String.class).newInstance(colored);

                Object packet = packetPlayOutChatClass.getConstructor(
                        iChatBaseComponentClass,
                        byte.class
                ).newInstance(chatComponent, (byte) 2);

                Object craftPlayer = craftPlayerClass.cast(player);
                Object entityPlayer = craftPlayerClass.getMethod("getHandle").invoke(craftPlayer);
                Object playerConnection = entityPlayer.getClass().getField("playerConnection").get(entityPlayer);
                playerConnection.getClass().getMethod("sendPacket", packetClass).invoke(playerConnection, packet);

            } catch (Exception e) {
                try {
                    player.sendTitle("", colored, 0, 20, 0);
                } catch (Exception ex) {
                }
            }
        } else {
            try {
                player.spigot().sendMessage(
                        net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText(colored)
                );
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}