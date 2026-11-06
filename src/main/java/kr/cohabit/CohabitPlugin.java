package kr.cohabit;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class CohabitPlugin extends JavaPlugin implements Listener, TabCompleter {

    private static final int[] SHARED = new int[37]; // 0~35 + 오프핸드(40)
    static {
        for (int i = 0; i < 36; i++) SHARED[i] = i;
        SHARED[36] = 40;
    }

    private boolean active;
    private final List<UUID> players = new ArrayList<>();
    private final Map<Integer, Material> slotBlock = new HashMap<>();
    private final Set<Integer> unlocked = new HashSet<>();
    private final ItemStack[] snap = new ItemStack[41];
    private NamespacedKey lockKey;
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        lockKey = new NamespacedKey(this, "lock");
        dataFile = new File(getDataFolder(), "data.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("cohabit").setExecutor(this);
        getCommand("cohabit").setTabCompleter(this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, this::save, 1200L, 1200L);
    }

    @Override
    public void onDisable() {
        save();
    }

    // ---------- 저장 / 불러오기 ----------
    private void load() {
        YamlConfiguration d = YamlConfiguration.loadConfiguration(dataFile);
        active = d.getBoolean("active");
        players.clear();
        for (String s : d.getStringList("players")) players.add(UUID.fromString(s));
        slotBlock.clear();
        ConfigurationSection sec = d.getConfigurationSection("slots");
        if (sec != null) for (String k : sec.getKeys(false)) {
            Material m = Material.matchMaterial(sec.getString(k, ""));
            if (m != null) slotBlock.put(Integer.parseInt(k), m);
        }
        unlocked.clear();
        unlocked.addAll(d.getIntegerList("unlocked"));
        for (int i = 0; i < 41; i++) snap[i] = d.getItemStack("snap." + i);
    }

    private void save() {
        YamlConfiguration d = new YamlConfiguration();
        d.set("active", active);
        List<String> ps = new ArrayList<>();
        for (UUID u : players) ps.add(u.toString());
        d.set("players", ps);
        for (Map.Entry<Integer, Material> e : slotBlock.entrySet())
            d.set("slots." + e.getKey(), e.getValue().name());
        d.set("unlocked", new ArrayList<>(unlocked));
        for (int i = 0; i < 41; i++) if (snap[i] != null) d.set("snap." + i, snap[i]);
        try {
            getDataFolder().mkdirs();
            d.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("data.yml 저장 실패: " + ex.getMessage());
        }
    }

    // ---------- 유틸 ----------
    private boolean isPlaying(Player p) {
        return active && players.contains(p.getUniqueId());
    }

    private List<Player> online() {
        List<Player> list = new ArrayList<>();
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) list.add(p);
        }
        return list;
    }

    private boolean locked(int slot) {
        return active && slot >= 0 && slot < 36 && !unlocked.contains(slot);
    }

    private ItemStack lockItem() {
        ItemStack it = new ItemStack(Material.BARRIER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName("§c잠긴 칸");
        m.setLore(List.of("§7특정 블록을 캐면 열립니다"));
        m.getPersistentDataContainer().set(lockKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean isLock(ItemStack it) {
        return it != null && it.getType() == Material.BARRIER && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(lockKey, PersistentDataType.BYTE);
    }

    private Material normalize(Material m) {
        String n = m.name();
        if (n.startsWith("DEEPSLATE_") && n.endsWith("_ORE")) {
            Material r = Material.getMaterial(n.substring(10));
            if (r != null) return r;
        }
        return m;
    }

    private void broadcast(String msg) {
        for (Player p : online()) p.sendMessage("§e[동거] §f" + msg);
    }

    // ---------- 인벤토리 공유 ----------
    private void tick() {
        if (!active) return;
        List<Player> on = online();
        for (Player p : on) enforce(p);
        Player src = null;
        for (Player p : on) if (differs(p)) { src = p; break; }
        if (src == null) return;
        PlayerInventory inv = src.getInventory();
        for (int s : SHARED) {
            ItemStack it = inv.getItem(s);
            snap[s] = it == null ? null : it.clone();
        }
        for (Player p : on) if (p != src) apply(p);
    }

    private boolean differs(Player p) {
        for (int s : SHARED)
            if (!Objects.equals(snap[s], p.getInventory().getItem(s))) return true;
        return false;
    }

    private void apply(Player p) {
        for (int s : SHARED)
            p.getInventory().setItem(s, snap[s] == null ? null : snap[s].clone());
    }

    private void enforce(Player p) {
        PlayerInventory inv = p.getInventory();
        List<ItemStack> extra = new ArrayList<>();
        for (int s = 0; s < 36; s++) {
            ItemStack it = inv.getItem(s);
            if (locked(s)) {
                if (!isLock(it)) {
                    if (it != null) extra.add(it);
                    inv.setItem(s, lockItem());
                }
            } else if (isLock(it)) {
                inv.setItem(s, null);
            }
        }
        for (ItemStack e : extra)
            inv.addItem(e).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
    }

    private void unlock(int slot, String who) {
        if (slot < 0 || slot >= 36 || !unlocked.add(slot)) return;
        snap[slot] = null;
        for (Player p : online()) p.getInventory().setItem(slot, null);
        broadcast((who != null ? who + "님이 블록을 캐서 " : "") + "인벤토리 " + (slot + 1)
                + "번 칸이 열렸습니다! (" + unlocked.size() + "/36)");
        save();
    }

    // ---------- 이벤트 ----------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!isPlaying(p)) return;
        Material m = normalize(e.getBlock().getType());
        for (Map.Entry<Integer, Material> en : slotBlock.entrySet()) {
            if (en.getValue() == m && !unlocked.contains(en.getKey())) {
                unlock(en.getKey(), p.getName());
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !isPlaying(p)) return;
        if (e.getClick() == ClickType.NUMBER_KEY && locked(e.getHotbarButton())) {
            e.setCancelled(true);
            return;
        }
        Inventory ci = e.getClickedInventory();
        if (ci instanceof PlayerInventory && (locked(e.getSlot()) || isLock(e.getCurrentItem())))
            e.setCancelled(true);
        if (isLock(e.getCursor())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !isPlaying(p)) return;
        InventoryView v = e.getView();
        for (int raw : e.getRawSlots()) {
            if (v.getInventory(raw) instanceof PlayerInventory && locked(v.convertSlot(raw))) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isLock(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (isLock(e.getMainHandItem()) || isLock(e.getOffHandItem())) e.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (isLock(e.getItem())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (isLock(e.getItemInHand())) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (isPlaying(e.getEntity()) && getConfig().getBoolean("keep-inventory-on-death")) {
            e.setKeepInventory(true);
            e.getDrops().clear();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (isPlaying(e.getPlayer())) apply(e.getPlayer());
    }

    // ---------- 명령어 ----------
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase();
        if (sub.equals("status")) {
            s.sendMessage(active ? "§e[동거] 진행 중 - " + unlocked.size() + "/36칸 열림"
                    : "§e[동거] 진행 중이 아닙니다.");
            return true;
        }
        if (!s.hasPermission("cohabit.admin")) {
            s.sendMessage("§c권한이 없습니다.");
            return true;
        }
        switch (sub) {
            case "start" -> {
                if (a.length < 3) { s.sendMessage("§c/cohabit start <플레이어1> <플레이어2>"); return true; }
                Player p1 = Bukkit.getPlayerExact(a[1]);
                Player p2 = Bukkit.getPlayerExact(a[2]);
                if (p1 == null || p2 == null || p1 == p2) {
                    s.sendMessage("§c서로 다른 두 명이 접속 중이어야 합니다.");
                    return true;
                }
                List<Material> mats = new ArrayList<>();
                for (String n : getConfig().getStringList("unlock-blocks")) {
                    Material m = Material.matchMaterial(n);
                    if (m != null) mats.add(m);
                }
                if (mats.size() < 36) {
                    s.sendMessage("§cconfig.yml의 unlock-blocks가 36개 이상이어야 합니다.");
                    return true;
                }
                mats = new ArrayList<>(mats.subList(0, 36));
                if (getConfig().getBoolean("shuffle")) Collections.shuffle(mats);
                slotBlock.clear();
                for (int i = 0; i < 36; i++) slotBlock.put(i, mats.get(i));
                players.clear();
                players.add(p1.getUniqueId());
                players.add(p2.getUniqueId());
                unlocked.clear();
                for (int i : getConfig().getIntegerList("initial-unlocked"))
                    if (i >= 0 && i < 36) unlocked.add(i);
                p1.getInventory().clear();
                p2.getInventory().clear();
                Arrays.fill(snap, null);
                active = true;
                save();
                broadcast("불편한 동거 시작! 인벤토리가 공유되고, 블록을 캐야 칸이 열립니다.");
            }
            case "stop" -> {
                active = false;
                for (Player p : online()) {
                    for (int i = 0; i < 36; i++)
                        if (isLock(p.getInventory().getItem(i))) p.getInventory().setItem(i, null);
                    p.sendMessage("§e[동거] §f종료되었습니다.");
                }
                save();
            }
            case "reveal" -> {
                for (int i = 0; i < 36; i++)
                    s.sendMessage("§7" + (i + 1) + "번 칸 ← " + slotBlock.get(i)
                            + (unlocked.contains(i) ? " §a(열림)" : ""));
            }
            case "unlock" -> {
                if (!active || a.length < 2) { s.sendMessage("§c/cohabit unlock <1~36>"); return true; }
                try {
                    unlock(Integer.parseInt(a[1]) - 1, null);
                } catch (NumberFormatException ex) {
                    s.sendMessage("§c숫자를 입력하세요.");
                }
            }
            default -> s.sendMessage("§e/cohabit start <p1> <p2> | stop | status | reveal | unlock <칸>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 1) return List.of("start", "stop", "status", "reveal", "unlock");
        return null;
    }
}
