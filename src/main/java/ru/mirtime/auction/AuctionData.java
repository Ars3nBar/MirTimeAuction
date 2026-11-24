package ru.mirtime.auction;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.NumberFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AuctionData {

    private final MirTimeAuction plugin;
    private Economy econ;
    private File file;
    private YamlConfiguration db;

    public final List<AuctionLot> activeLots = new ArrayList<>();
    public final Map<UUID, List<ItemStack>> storage = new HashMap<>();
    public final Map<UUID, RentBoost> activeBoosts = new HashMap<>();
    public final Map<UUID, Long> pendingTokens = new HashMap<>();

    private static final NumberFormat NUM_FORMAT = NumberFormat.getInstance(Locale.US);
    public static final long LOT_DURATION_MS = 24L * 60L * 60L * 1000L;

    public enum MenuType { MAIN, MY_LOTS, STORAGE, RENT, CONFIRM }

    public enum CurrencyFilter {
        ALL("Все валюты", Material.NETHER_STAR),
        MONEY("За Монеты ($)", Material.GOLD_INGOT),
        TOKENS("За Токены (ТК)", Material.EMERALD);

        public final String display;
        public final Material icon;

        CurrencyFilter(String display, Material icon) {
            this.display = display;
            this.icon = icon;
        }

        public CurrencyFilter next() {
            CurrencyFilter[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }

        public boolean matches(AuctionLot lot) {
            if (this == ALL) return true;
            if (this == TOKENS) return lot.isTokens;
            return !lot.isTokens;
        }
    }

    public static class AuctionHolder implements InventoryHolder {
        private final MenuType type;
        private Inventory inventory;

        public AuctionHolder(MenuType type) {
            this.type = type;
        }

        public MenuType getType() { return type; }
        public void setInventory(Inventory inventory) { this.inventory = inventory; }
        @Override public Inventory getInventory() { return inventory; }
    }

    public enum Category {
        ALL("Все товары", Material.CHEST),
        COMBAT("Оружие и Броня", Material.NETHERITE_SWORD),
        TOOLS("Инструменты", Material.NETHERITE_PICKAXE),
        CONSUMABLES("Зелья и Еда", Material.ENCHANTED_GOLDEN_APPLE),
        RESOURCES("Ресурсы и Блоки", Material.DIAMOND);

        public final String display;
        public final Material icon;

        Category(String display, Material icon) {
            this.display = display;
            this.icon = icon;
        }

        public Category next() {
            Category[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }

        public boolean matches(ItemStack item) {
            if (this == ALL || item == null) return true;
            String name = item.getType().name();
            return switch (this) {
                case COMBAT -> name.endsWith("_SWORD") || name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                        || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS") || name.equals("BOW")
                        || name.equals("CROSSBOW") || name.equals("TRIDENT") || name.equals("MACE")
                        || name.equals("SHIELD") || name.equals("TOTEM_OF_UNDYING");
                case TOOLS -> name.endsWith("_PICKAXE") || name.endsWith("_AXE") || name.endsWith("_SHOVEL")
                        || name.endsWith("_HOE") || name.equals("FISHING_ROD") || name.equals("SHEARS") || name.equals("ELYTRA");
                case CONSUMABLES -> item.getType().isEdible() || name.contains("POTION") || name.contains("ARROW")
                        || name.equals("ENDER_PEARL") || name.equals("EXPERIENCE_BOTTLE");
                case RESOURCES -> item.getType().isBlock() || name.endsWith("_INGOT") || name.endsWith("_NUGGET")
                        || name.equals("DIAMOND") || name.equals("EMERALD") || name.equals("NETHERITE_SCRAP")
                        || name.equals("COAL") || name.equals("REDSTONE") || name.equals("LAPIS_LAZULI")
                        || name.equals("QUARTZ") || name.equals("ENCHANTED_BOOK");
                default -> true;
            };
        }
    }
    public enum SortType {
        NEWEST("Сначала новые"),
        CHEAPEST("Сначала дешёвые"),
        EXPENSIVE("Сначала дорогие");

        public final String display;
        SortType(String display) { this.display = display; }
        public SortType next() {
            SortType[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }
    }

    public static class AuctionLot {
        public final String id;
        public final UUID sellerId;
        public final String sellerName;
        public final ItemStack item;
        public final long price;
        public final boolean isTokens;
        public final long createdAt;
        public final long expiresAt;

        public AuctionLot(String id, UUID sellerId, String sellerName, ItemStack item, long price, boolean isTokens, long createdAt, long expiresAt) {
            this.id = id;
            this.sellerId = sellerId;
            this.sellerName = sellerName;
            this.item = item;
            this.price = price;
            this.isTokens = isTokens;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        public String formattedPrice() {
            return isTokens ? "&#00E5FF&l" + fmt(price) + " ТК" : "&#00FF66&l$" + fmt(price);
        }
    }

    public static class RentBoost {
        public final int extraSlots;
        public final long expiresAt;

        public RentBoost(int extraSlots, long expiresAt) {
            this.extraSlots = extraSlots;
            this.expiresAt = expiresAt;
        }
    }

    public AuctionData(MirTimeAuction plugin) {
        this.plugin = plugin;
    }

    public boolean setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        econ = rsp.getProvider();
        return econ != null;
    }

    public Economy getEcon() { return econ; }

    public void load() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        file = new File(plugin.getDataFolder(), "auctions.yml");
        if (!file.exists()) {
            try { file.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        db = YamlConfiguration.loadConfiguration(file);

        activeLots.clear();
        ConfigurationSection lotsSec = db.getConfigurationSection("lots");
        if (lotsSec != null) {
            for (String key : lotsSec.getKeys(false)) {
                try {
                    UUID sellerId = UUID.fromString(lotsSec.getString(key + ".sellerId"));
                    String sellerName = lotsSec.getString(key + ".sellerName", "Игрок");
                    ItemStack item = lotsSec.getItemStack(key + ".item");
                    long price = lotsSec.getLong(key + ".price");
                    boolean isTokens = lotsSec.getBoolean(key + ".isTokens", false);
                    long created = lotsSec.getLong(key + ".createdAt");
                    long expires = lotsSec.getLong(key + ".expiresAt");
                    if (item != null && item.getType() != Material.AIR) {
                        activeLots.add(new AuctionLot(key, sellerId, sellerName, item, price, isTokens, created, expires));
                    }
                } catch (Exception ignored) {}
            }
        }

        storage.clear();
        ConfigurationSection storSec = db.getConfigurationSection("storage");
        if (storSec != null) {
            for (String uuidStr : storSec.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    List<?> rawList = storSec.getList(uuidStr);
                    if (rawList != null) {
                        List<ItemStack> items = new ArrayList<>();
                        for (Object o : rawList) {
                            if (o instanceof ItemStack is) items.add(is);
                        }
                        if (!items.isEmpty()) storage.put(uuid, items);
                    }
                } catch (Exception ignored) {}
            }
        }

        activeBoosts.clear();
        ConfigurationSection boostSec = db.getConfigurationSection("boosts");
        if (boostSec != null) {
            long now = System.currentTimeMillis();
            for (String uuidStr : boostSec.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    int slots = boostSec.getInt(uuidStr + ".slots");
                    long exp = boostSec.getLong(uuidStr + ".expiresAt");
                    if (exp > now && slots > 0) {
                        activeBoosts.put(uuid, new RentBoost(slots, exp));
                    }
                } catch (Exception ignored) {}
            }
        }

        pendingTokens.clear();
        ConfigurationSection tokSec = db.getConfigurationSection("pendingTokens");
        if (tokSec != null) {
            for (String uuidStr : tokSec.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    long amt = tokSec.getLong(uuidStr);
                    if (amt > 0) pendingTokens.put(uuid, amt);
                } catch (Exception ignored) {}
            }
        }
    }
    public void save() {
        if (db == null || file == null) return;
        db.set("lots", null);
        for (AuctionLot lot : activeLots) {
            String p = "lots." + lot.id;
            db.set(p + ".sellerId", lot.sellerId.toString());
            db.set(p + ".sellerName", lot.sellerName);
            db.set(p + ".item", lot.item);
            db.set(p + ".price", lot.price);
            db.set(p + ".isTokens", lot.isTokens);
            db.set(p + ".createdAt", lot.createdAt);
            db.set(p + ".expiresAt", lot.expiresAt);
        }

        db.set("storage", null);
        for (Map.Entry<UUID, List<ItemStack>> entry : storage.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                db.set("storage." + entry.getKey(), entry.getValue());
            }
        }

        db.set("boosts", null);
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, RentBoost> entry : activeBoosts.entrySet()) {
            if (entry.getValue().expiresAt > now) {
                db.set("boosts." + entry.getKey() + ".slots", entry.getValue().extraSlots);
                db.set("boosts." + entry.getKey() + ".expiresAt", entry.getValue().expiresAt);
            }
        }

        db.set("pendingTokens", null);
        for (Map.Entry<UUID, Long> entry : pendingTokens.entrySet()) {
            if (entry.getValue() > 0) {
                db.set("pendingTokens." + entry.getKey(), entry.getValue());
            }
        }

        try { db.save(file); } catch (IOException e) { e.printStackTrace(); }
    }

    public void checkExpired() {
        long now = System.currentTimeMillis();
        boolean changed = false;

        Iterator<AuctionLot> it = activeLots.iterator();
        while (it.hasNext()) {
            AuctionLot lot = it.next();
            if (now >= lot.expiresAt) {
                it.remove();
                addToStorage(lot.sellerId, lot.item);
                Player seller = Bukkit.getPlayer(lot.sellerId);
                if (seller != null && seller.isOnline()) {
                    seller.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &eВремя продажи лота истекло! Заберите предмет в &#00FF66/ah &8(&#00E5FFХранилище&8)."));
                }
                changed = true;
            }
        }

        Iterator<Map.Entry<UUID, RentBoost>> bIt = activeBoosts.entrySet().iterator();
        while (bIt.hasNext()) {
            Map.Entry<UUID, RentBoost> entry = bIt.next();
            if (now >= entry.getValue().expiresAt) {
                bIt.remove();
                Player p = Bukkit.getPlayer(entry.getKey());
                if (p != null && p.isOnline()) {
                    p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &eСрок аренды дополнительных слотов аукциона истёк."));
                }
                changed = true;
            }
        }

        if (changed) save();
    }

    public void addToStorage(UUID uuid, ItemStack item) {
        storage.computeIfAbsent(uuid, k -> new ArrayList<>()).add(item.clone());
    }

    public List<ItemStack> getStorage(UUID uuid) {
        return storage.computeIfAbsent(uuid, k -> new ArrayList<>());
    }

    public int getActiveCount(UUID uuid) {
        int count = 0;
        for (AuctionLot lot : activeLots) {
            if (lot.sellerId.equals(uuid)) count++;
        }
        return count;
    }

    public int getBaseSlots(Player p) {
        int base = 3;
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String perm = info.getPermission().toLowerCase();
            if (perm.startsWith("mirtime.ah.slots.")) {
                try {
                    int val = Integer.parseInt(perm.substring("mirtime.ah.slots.".length()));
                    if (val > base) base = val;
                } catch (NumberFormatException ignored) {}
            }
        }
        return base;
    }

    public int getRentedSlots(UUID uuid) {
        RentBoost boost = activeBoosts.get(uuid);
        if (boost == null) return 0;
        if (System.currentTimeMillis() >= boost.expiresAt) {
            activeBoosts.remove(uuid);
            return 0;
        }
        return boost.extraSlots;
    }

    public int getMaxSlots(Player p) {
        return getBaseSlots(p) + getRentedSlots(p.getUniqueId());
    }

    public long getTokens(Player p) {
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                Method setPlaceholders = papi.getMethod("setPlaceholders", Player.class, String.class);
                String res = (String) setPlaceholders.invoke(null, p, "%playerpoints_points%");
                res = res.replaceAll("[^0-9.]", "");
                if (!res.isEmpty()) return (long) Double.parseDouble(res);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    public boolean takeTokens(Player p, long amount) {
        if (amount <= 0 || getTokens(p) < amount) return false;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "points take " + p.getName() + " " + amount);
        return true;
    }

    public void giveTokens(UUID sellerId, String sellerName, long amount) {
        if (amount <= 0) return;
        Player online = Bukkit.getPlayer(sellerId);
        if (online != null && online.isOnline()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "points give " + online.getName() + " " + amount);
        } else {
            pendingTokens.put(sellerId, pendingTokens.getOrDefault(sellerId, 0L) + amount);
        }
    }

    public void claimPendingTokens(Player p) {
        Long amount = pendingTokens.remove(p.getUniqueId());
        if (amount != null && amount > 0) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "points give " + p.getName() + " " + amount);
            save();
            p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &fПока вас не было, на аукционе купили ваши товары! Зачислено: &#00E5FF&l" + fmt(amount) + " ТК"));
        }
    }

    public static String fmt(long num) {
        return NUM_FORMAT.format(num);
    }

    public static String formatTimeLeft(long ms) {
        if (ms <= 0) return "0 мин.";
        long hours = ms / 3600000L;
        long mins = Math.max(1L, (ms % 3600000L) / 60000L);
        if (hours > 0) return hours + " ч. " + mins + " мин.";
        return mins + " мин.";
    }

    public static String color(String text) {
        if (text == null) return "";
        Pattern pattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
        Matcher matcher = pattern.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder rep = new StringBuilder("§x");
            for (char c : hex.toCharArray()) rep.append('§').append(c);
            matcher.appendReplacement(buffer, rep.toString());
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    public static ItemStack makeItem(Material mat, String name, List<String> lore) {
        ItemStack is = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            if (lore != null && !lore.isEmpty()) {
                List<String> list = new ArrayList<>();
                for (String s : lore) list.add(color(s));
                meta.setLore(list);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            is.setItemMeta(meta);
        }
        return is;
    }

    public static void frame(Inventory inv) {
        ItemStack blue = makeItem(Material.BLUE_STAINED_GLASS_PANE, "&#0055ff▪", null);
        ItemStack cyan = makeItem(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "&#00E5FF▫", null);
        int[] bSlots = {0, 1, 7, 8, 9, 17, 18, 26, 27, 35, 36, 44, 45, 46, 52, 53};
        int[] cSlots = {2, 3, 4, 5, 6, 47, 48, 49, 50, 51};
        for (int s : bSlots) inv.setItem(s, blue);
        for (int s : cSlots) inv.setItem(s, cyan);
    }
}
