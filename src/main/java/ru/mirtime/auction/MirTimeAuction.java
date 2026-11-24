package ru.mirtime.auction;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

import static ru.mirtime.auction.AuctionData.*;

public class MirTimeAuction extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private AuctionData data;

    private final Map<UUID, Integer> playerPages = new HashMap<>();
    private final Map<UUID, Category> playerCategories = new HashMap<>();
    private final Map<UUID, SortType> playerSorts = new HashMap<>();
    private final Map<UUID, Map<Integer, AuctionLot>> lotSlotMaps = new HashMap<>();
    private final Map<UUID, Map<Integer, Integer>> storageSlotMaps = new HashMap<>();
    private final Map<UUID, AuctionLot> pendingBuy = new HashMap<>();

    @Override
    public void onEnable() {
        this.data = new AuctionData(this);
        if (!data.setupEconomy()) {
            getLogger().severe("Vault не найден! Отключение MirTimeAuction...");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        data.load();

        if (getCommand("ah") != null) {
            getCommand("ah").setExecutor(this);
            getCommand("ah").setTabCompleter(this);
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> data.checkExpired(), 200L, 600L);
        getLogger().info("MirTimeAuction v1.0.0 успешно запущен!");
    }

    @Override
    public void onDisable() {
        if (data != null) data.save();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("sell", "продать", "my", "мои", "rent", "аренда", "storage", "хранилище").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("sell") || args[0].equalsIgnoreCase("продать"))) {
            return List.of("1000", "5000", "25000", "100000");
        }
        return List.of();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;

        data.checkExpired();

        if (args.length == 0) {
            playerPages.put(p.getUniqueId(), 0);
            openMainMenu(p);
            return true;
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("my") || sub.equals("мои")) {
            openMyLotsMenu(p);
            return true;
        }
        if (sub.equals("storage") || sub.equals("хранилище") || sub.equals("expired")) {
            openStorageMenu(p);
            return true;
        }
        if (sub.equals("rent") || sub.equals("аренда") || sub.equals("slots")) {
            openRentMenu(p);
            return true;
        }
        if (sub.equals("sell") || sub.equals("продать")) {
            if (args.length < 2) {
                p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &fИспользование: &#00FF66/ah sell <цена>"));
                return true;
            }
            long price;
            try {
                price = Long.parseLong(args[1].replace(",", "").replace(".", ""));
            } catch (NumberFormatException ex) {
                p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &cУкажите корректную цену числом!"));
                return true;
            }
            if (price < 10 || price > 10_000_000_000L) {
                p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &cЦена должна быть от &#00FF66$10 &cдо &#00FF66$10,000,000,000&c!"));
                return true;
            }

            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType() == Material.AIR || hand.getAmount() <= 0) {
                p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &cВозьмите предмет в основную руку!"));
                return true;
            }

            int active = data.getActiveCount(p.getUniqueId());
            int max = data.getMaxSlots(p);
            if (active >= max) {
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &cДостигнут лимит слотов (&e" + active + "/" + max + "&c)! Арендуйте доп. слоты в &#00FF66/ah rent"));
                return true;
            }

            ItemStack toSell = hand.clone();
            p.getInventory().setItemInMainHand(null);

            long now = System.currentTimeMillis();
            String id = UUID.randomUUID().toString().substring(0, 8);
            AuctionLot lot = new AuctionLot(id, p.getUniqueId(), p.getName(), toSell, price, now, now + LOT_DURATION_MS);
            data.activeLots.add(lot);
            data.save();

            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.3f);
            p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &fПредмет выставлен на продажу за &#00FF66$" + fmt(price) + " &8(&7Слоты: " + (active + 1) + "/" + max + "&8)"));
            return true;
        }

        p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &fКоманды: &#00FF66/ah &7| &#00FF66/ah sell <цена> &7| &#00FF66/ah my &7| &#00FF66/ah rent"));
        return true;
    }

    private Inventory createInv(MenuType type, String title) {
        AuctionHolder holder = new AuctionHolder(type);
        Inventory inv = Bukkit.createInventory(holder, 54, color(title));
        holder.setInventory(inv);
        frame(inv);
        return inv;
    }
    public void openMainMenu(Player p) {
        UUID id = p.getUniqueId();
        int page = playerPages.getOrDefault(id, 0);
        Category cat = playerCategories.getOrDefault(id, Category.ALL);
        SortType sort = playerSorts.getOrDefault(id, SortType.NEWEST);

        List<AuctionLot> filtered = new ArrayList<>();
        for (AuctionLot lot : data.activeLots) {
            if (cat.matches(lot.item)) filtered.add(lot);
        }

        switch (sort) {
            case NEWEST -> filtered.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
            case CHEAPEST -> filtered.sort(Comparator.comparingLong(a -> a.price));
            case EXPENSIVE -> filtered.sort((a, b) -> Long.compare(b.price, a.price));
        }

        int[] grid = {
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34,
                37, 38, 39, 40, 41, 42, 43
        };
        int perPage = grid.length;
        int totalPages = Math.max(1, (int) Math.ceil((double) filtered.size() / perPage));
        if (page >= totalPages) page = totalPages - 1;
        playerPages.put(id, page);

        Inventory inv = createInv(MenuType.MAIN, "&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8| &fСтр. &#00FF66" + (page + 1) + "&8/&f" + totalPages);

        inv.setItem(3, makeItem(cat.icon, "&#0055ff[★] &#00E5FFКатегория: &#00FF66" + cat.display, List.of(
                "&7Фильтр предметов на аукционе.",
                "",
                "&#00FF66➥ Нажмите&f, чтобы сменить категорию"
        )));

        inv.setItem(4, makeItem(Material.BOOK, "&#0055ff[i] &#00E5FF&lКак выставить товар?", List.of(
                "&7Возьмите предмет в руку и введите:",
                "&#00FF66/ah sell <цена>",
                "",
                "&#0055ff● &fВаши слоты: &#00FF66" + data.getActiveCount(id) + "&7/&#00E5FF" + data.getMaxSlots(p),
                "&#0055ff● &fАрендовано доп. слотов: &#00FF66+" + data.getRentedSlots(id)
        )));

        inv.setItem(5, makeItem(Material.HOPPER, "&#0055ff[⇅] &#00E5FFСортировка: &#00FF66" + sort.display, List.of(
                "&7Порядок отображения лотов.",
                "",
                "&#00FF66➥ Нажмите&f, чтобы переключить"
        )));

        int storageCount = data.getStorage(id).size();
        inv.setItem(45, makeItem(Material.ENDER_CHEST, "&#0055ff[📦] &#00E5FFХранилище возврата &8(&#00FF66" + storageCount + "&8)", List.of(
                "&7Сюда попадают снятые или",
                "&7истёкшие через 24 ч. лоты.",
                "",
                "&#00FF66➥ Нажмите&f, чтобы забрать вещи"
        )));

        inv.setItem(46, makeItem(Material.CHEST, "&#0055ff[★] &#00E5FFМои активные лоты &8(&#00FF66" + data.getActiveCount(id) + "/" + data.getMaxSlots(p) + "&8)", List.of(
                "&7Просмотр и снятие ваших",
                "&7текущих товаров с продажи.",
                "",
                "&#00FF66➥ Нажмите&f, чтобы открыть"
        )));

        if (page > 0) inv.setItem(48, makeItem(Material.ARROW, "&#00E5FF&l[◀ Назад]", null));
        inv.setItem(49, makeItem(Material.SUNFLOWER, "&#0055ff[⟳] &#00FF66Обновить аукцион", List.of("&7Обновить список товаров.")));
        if (page + 1 < totalPages) inv.setItem(50, makeItem(Material.ARROW, "&#00FF66&l[Вперёд ▶]", null));

        inv.setItem(52, makeItem(Material.GOLDEN_CARROT, "&#0055ff[☃] &#00FF66&lАренда слотов &8(&#00E5FF+слоты&8)", List.of(
                "&7Не хватает места на аукционе?",
                "&7Арендуйте до &#00FF66+20 слотов &7на 1 час",
                "&7за &#00E5FFТокены &7или &#00FF66Монеты&7!",
                "",
                "&#00FF66➥ Нажмите&f, чтобы арендовать"
        )));

        Map<Integer, AuctionLot> slotMap = new HashMap<>();
        lotSlotMaps.put(id, slotMap);

        int start = page * perPage;
        for (int i = 0; i < perPage && (start + i) < filtered.size(); i++) {
            AuctionLot lot = filtered.get(start + i);
            ItemStack display = lot.item.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add("");
                lore.add(color("&#0055ff&m                              "));
                lore.add(color(" &#0055ff● &fПродавец: &#00E5FF" + lot.sellerName));
                lore.add(color(" &#0055ff● &fЦена: &#00FF66&l$" + fmt(lot.price)));
                lore.add(color(" &#0055ff● &fОсталось: &7" + formatTimeLeft(lot.expiresAt - System.currentTimeMillis())));
                lore.add(color("&#0055ff&m                              "));
                if (lot.sellerId.equals(id)) {
                    lore.add(color("&c[ПКМ] &fСнять свой лот с продажи"));
                } else {
                    lore.add(color("&#00FF66[ЛКМ] &fКупить этот предмет"));
                    if (p.hasPermission("mirtime.ah.admin")) {
                        lore.add(color("&c[ШИФТ+ПКМ] &fУдалить лот (Админ)"));
                    }
                }
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            inv.setItem(grid[i], display);
            slotMap.put(grid[i], lot);
        }

        p.openInventory(inv);
    }
    public void openMyLotsMenu(Player p) {
        UUID id = p.getUniqueId();
        Inventory inv = createInv(MenuType.MY_LOTS, "&#0055ff&l⚡ &#00E5FF&lМои лоты &8(&#00FF66" + data.getActiveCount(id) + "/" + data.getMaxSlots(p) + "&8)");

        int[] grid = {
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34,
                37, 38, 39, 40, 41, 42, 43
        };

        Map<Integer, AuctionLot> slotMap = new HashMap<>();
        lotSlotMaps.put(id, slotMap);

        int idx = 0;
        for (AuctionLot lot : data.activeLots) {
            if (!lot.sellerId.equals(id) || idx >= grid.length) continue;
            ItemStack display = lot.item.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add("");
                lore.add(color(" &#0055ff● &fЦена: &#00FF66$" + fmt(lot.price)));
                lore.add(color(" &#0055ff● &fОсталось: &7" + formatTimeLeft(lot.expiresAt - System.currentTimeMillis())));
                lore.add(color("&c➥ Нажмите&f, чтобы снять с продажи"));
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            inv.setItem(grid[idx], display);
            slotMap.put(grid[idx], lot);
            idx++;
        }

        inv.setItem(49, makeItem(Material.ARROW, "&c[←] &fВернуться на аукцион", null));
        p.openInventory(inv);
    }

    public void openStorageMenu(Player p) {
        UUID id = p.getUniqueId();
        List<ItemStack> items = data.getStorage(id);
        Inventory inv = createInv(MenuType.STORAGE, "&#0055ff&l⚡ &#00E5FF&lХранилище возврата &8(&#00FF66" + items.size() + "&8)");

        int[] grid = {
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34,
                37, 38, 39, 40, 41, 42, 43
        };

        Map<Integer, Integer> slotMap = new HashMap<>();
        storageSlotMaps.put(id, slotMap);

        for (int i = 0; i < Math.min(items.size(), grid.length); i++) {
            ItemStack display = items.get(i).clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add("");
                lore.add(color("&#00FF66➥ Нажмите&f, чтобы забрать в инвентарь"));
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            inv.setItem(grid[i], display);
            slotMap.put(grid[i], i);
        }

        inv.setItem(49, makeItem(Material.ARROW, "&c[←] &fВернуться на аукцион", null));
        p.openInventory(inv);
    }

    public void openRentMenu(Player p) {
        UUID id = p.getUniqueId();
        Inventory inv = createInv(MenuType.RENT, "&#0055ff&l☃ &#00E5FF&lАренда слотов аукциона &8(&#00FF661 час&8)");

        RentBoost boost = data.activeBoosts.get(id);
        String activeInfo = (boost != null && boost.expiresAt > System.currentTimeMillis())
                ? "&#00FF66+" + boost.extraSlots + " сл. &8(&7осталось " + formatTimeLeft(boost.expiresAt - System.currentTimeMillis()) + "&8)"
                : "&7Нет активной аренды";

        inv.setItem(4, makeItem(Material.NETHER_STAR, "&#0055ff[i] &#00E5FF&lВаши слоты: &#00FF66" + data.getActiveCount(id) + "&7/&#00E5FF" + data.getMaxSlots(p), List.of(
                " &#0055ff● &fБазовые слоты: &#00E5FF" + data.getBaseSlots(p),
                " &#0055ff● &fАктивная аренда: " + activeInfo,
                "",
                " &#0055ff● &fВаш баланс: &#00FF66$" + fmt((long) data.getEcon().getBalance(p)) + " &8| &#00E5FF" + fmt(data.getTokens(p)) + " ТК"
        )));

        inv.setItem(20, makeRentIcon(1, 490, 350_000));
        inv.setItem(21, makeRentIcon(3, 790, 1_000_000));
        inv.setItem(22, makeRentIcon(5, 970, 1_500_000));
        inv.setItem(23, makeRentIcon(10, 1490, 2_500_000));
        inv.setItem(24, makeRentIcon(20, 2490, 0));

        inv.setItem(49, makeItem(Material.ARROW, "&c[←] &fВернуться на аукцион", null));
        p.openInventory(inv);
    }

    private ItemStack makeRentIcon(int slots, long tokenPrice, long moneyPrice) {
        List<String> lore = new ArrayList<>();
        lore.add("&#0055ff&m                              ");
        lore.add(" &#00FF66$ &fЦена в Токенах: &#00E5FF" + fmt(tokenPrice) + " ТК");
        if (moneyPrice > 0) {
            lore.add(" &#00FF66$ &fЦена в Монетах: &#00FF66$" + fmt(moneyPrice));
        } else {
            lore.add(" &7(Доступно только за Токены)");
        }
        lore.add("&#0055ff&m                              ");
        lore.add("");
        lore.add("&#00E5FF[ЛКМ] &fАрендовать за &#00E5FFТокены");
        if (moneyPrice > 0) {
            lore.add("&#00FF66[ПКМ] &fАрендовать за &#00FF66Монеты");
        }
        ItemStack is = makeItem(Material.GOLDEN_CARROT, "&#0055ff[☃] &fАренда: &#00FF66+" + slots + " слот(ов) &8/ &#00E5FF1 час", lore);
        is.setAmount(Math.min(64, slots));
        return is;
    }

    public void openConfirmMenu(Player p, AuctionLot lot) {
        pendingBuy.put(p.getUniqueId(), lot);
        Inventory inv = createInv(MenuType.CONFIRM, "&#0055ff&l⚡ &#00E5FF&lПодтверждение покупки");

        inv.setItem(22, lot.item.clone());
        inv.setItem(20, makeItem(Material.LIME_STAINED_GLASS_PANE, "&#00FF66&l[✔ ПОДТВЕРДИТЬ ПОКУПКУ]", List.of(
                " &#0055ff● &fПродавец: &#00E5FF" + lot.sellerName,
                " &#0055ff● &fСпишется: &#00FF66&l$" + fmt(lot.price),
                "",
                "&#00FF66➥ Нажмите&f, чтобы купить товар"
        )));
        inv.setItem(24, makeItem(Material.RED_STAINED_GLASS_PANE, "&c&l[✖ ОТМЕНА]", List.of(
                "&7Вернуться обратно на аукцион."
        )));

        p.openInventory(inv);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof AuctionHolder) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!(e.getView().getTopInventory().getHolder() instanceof AuctionHolder holder)) return;

        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (e.getCurrentItem() == null || e.getCurrentItem().getType() == Material.AIR) return;

        UUID id = p.getUniqueId();
        int slot = e.getRawSlot();
        data.checkExpired();

        switch (holder.getType()) {
            case MAIN -> {
                if (slot == 3) {
                    playerCategories.put(id, playerCategories.getOrDefault(id, Category.ALL).next());
                    playerPages.put(id, 0);
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
                    openMainMenu(p);
                    return;
                }
                if (slot == 5) {
                    playerSorts.put(id, playerSorts.getOrDefault(id, SortType.NEWEST).next());
                    playerPages.put(id, 0);
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
                    openMainMenu(p);
                    return;
                }
                if (slot == 45) { openStorageMenu(p); return; }
                if (slot == 46) { openMyLotsMenu(p); return; }
                if (slot == 48 && playerPages.getOrDefault(id, 0) > 0) {
                    playerPages.put(id, playerPages.get(id) - 1);
                    openMainMenu(p);
                    return;
                }
                if (slot == 49) {
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.3f);
                    openMainMenu(p);
                    return;
                }
                if (slot == 50 && e.getCurrentItem().getType() == Material.ARROW) {
                    playerPages.put(id, playerPages.getOrDefault(id, 0) + 1);
                    openMainMenu(p);
                    return;
                }
                if (slot == 52) { openRentMenu(p); return; }

                Map<Integer, AuctionLot> slotMap = lotSlotMaps.get(id);
                if (slotMap == null || !slotMap.containsKey(slot)) return;
                AuctionLot lot = slotMap.get(slot);

                if (!data.activeLots.contains(lot)) {
                    p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &cЭтот лот уже куплен или снят!"));
                    openMainMenu(p);
                    return;
                }

                if (lot.sellerId.equals(id) && e.getClick() == ClickType.RIGHT) {
                    data.activeLots.remove(lot);
                    data.addToStorage(id, lot.item);
                    data.save();
                    p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
                    p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &fЛот снят с продажи. Заберите предмет в &#00FF66Хранилище возврата&f."));
                    openMainMenu(p);
                    return;
                }

                if (!lot.sellerId.equals(id) && e.getClick() == ClickType.SHIFT_RIGHT && p.hasPermission("mirtime.ah.admin")) {
                    data.activeLots.remove(lot);
                    data.addToStorage(lot.sellerId, lot.item);
                    data.save();
                    p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
                    p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &cЛот игрока &f" + lot.sellerName + " &cснят модератором и отправлен в его хранилище."));
                    openMainMenu(p);
                    return;
                }

                if (!lot.sellerId.equals(id) && e.getClick() == ClickType.LEFT) {
                    openConfirmMenu(p, lot);
                }
            }
            case MY_LOTS -> {
                if (slot == 49) { openMainMenu(p); return; }
                Map<Integer, AuctionLot> slotMap = lotSlotMaps.get(id);
                if (slotMap == null || !slotMap.containsKey(slot)) return;
                AuctionLot lot = slotMap.get(slot);
                if (data.activeLots.remove(lot)) {
                    data.addToStorage(id, lot.item);
                    data.save();
                    p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
                    p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &fЛот снят. Предмет перемещён в &#00FF66Хранилище возврата&f."));
                }
                openMyLotsMenu(p);
            }
            case STORAGE -> {
                if (slot == 49) { openMainMenu(p); return; }
                Map<Integer, Integer> slotMap = storageSlotMaps.get(id);
                if (slotMap == null || !slotMap.containsKey(slot)) return;
                int index = slotMap.get(slot);
                List<ItemStack> items = data.getStorage(id);
                if (index >= 0 && index < items.size()) {
                    if (p.getInventory().firstEmpty() == -1) {
                        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                        p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &cОсвободите хотя бы 1 слот в инвентаре!"));
                        return;
                    }
                    ItemStack taken = items.remove(index);
                    p.getInventory().addItem(taken);
                    data.save();
                    p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);
                }
                openStorageMenu(p);
            }
            case RENT -> {
                if (slot == 49) { openMainMenu(p); return; }
                int slotsToRent = switch (slot) {
                    case 20 -> 1;
                    case 21 -> 3;
                    case 22 -> 5;
                    case 23 -> 10;
                    case 24 -> 20;
                    default -> 0;
                };
                if (slotsToRent == 0) return;

                long tokenPrice = switch (slotsToRent) {
                    case 1 -> 490L;
                    case 3 -> 790L;
                    case 5 -> 970L;
                    case 10 -> 1490L;
                    default -> 2490L;
                };
                long moneyPrice = switch (slotsToRent) {
                    case 1 -> 350_000L;
                    case 3 -> 1_000_000L;
                    case 5 -> 1_500_000L;
                    case 10 -> 2_500_000L;
                    default -> 0L;
                };

                if (e.getClick() == ClickType.LEFT) {
                    if (!data.takeTokens(p, tokenPrice)) {
                        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                        p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &cНедостаточно токенов! Додеп: &#00E5FF&ndon.mir-time.ru"));
                        return;
                    }
                } else if (e.getClick() == ClickType.RIGHT && moneyPrice > 0) {
                    if (!data.getEcon().has(p, moneyPrice)) {
                        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                        p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &cНедостаточно монет!"));
                        return;
                    }
                    data.getEcon().withdrawPlayer(p, moneyPrice);
                } else {
                    return;
                }

                long oneHour = 60L * 60L * 1000L;
                data.activeBoosts.put(id, new RentBoost(slotsToRent, System.currentTimeMillis() + oneHour));
                data.save();

                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &#00FF66Успешно! &fВы арендовали &#00FF66+" + slotsToRent + " слот(ов) &fна 1 час!"));
                openRentMenu(p);
            }
            case CONFIRM -> {
                if (slot == 24) {
                    pendingBuy.remove(id);
                    openMainMenu(p);
                    return;
                }
                if (slot == 20) {
                    AuctionLot lot = pendingBuy.remove(id);
                    if (lot == null || !data.activeLots.contains(lot)) {
                        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                        p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &cУпс! Этот лот уже успели купить."));
                        openMainMenu(p);
                        return;
                    }
                    if (!data.getEcon().has(p, lot.price)) {
                        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                        p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &cУ вас недостаточно монет для покупки!"));
                        openMainMenu(p);
                        return;
                    }

                    data.activeLots.remove(lot);
                    data.getEcon().withdrawPlayer(p, lot.price);
                    OfflinePlayer seller = Bukkit.getOfflinePlayer(lot.sellerId);
                    data.getEcon().depositPlayer(seller, lot.price);
                    data.save();

                    HashMap<Integer, ItemStack> left = p.getInventory().addItem(lot.item.clone());
                    for (ItemStack drop : left.values()) {
                        p.getWorld().dropItemNaturally(p.getLocation(), drop);
                    }

                    p.closeInventory();
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                    p.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &#00FF66Покупка успешна! &fВы купили товар у &#00E5FF" + lot.sellerName + " &fза &#00FF66$" + fmt(lot.price)));

                    if (seller.isOnline() && seller.getPlayer() != null) {
                        Player onlineSeller = seller.getPlayer();
                        onlineSeller.playSound(onlineSeller.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.3f);
                        onlineSeller.sendMessage(color("&#0055ff&l⚡ &#00E5FF&lАУКЦИОН &8» &fИгрок &#00E5FF" + p.getName() + " &fкупил ваш лот за &#00FF66$" + fmt(lot.price) + "&f!"));
                    }
                }
            }
        }
    }
}
