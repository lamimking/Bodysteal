package com.bodysteal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class BodyStealPlugin extends JavaPlugin implements Listener {

    /** Parts are lost in this order: death 1..4. Death 5 = cage. */
    enum Part {
        LEFT_ARM("Left Arm"), RIGHT_ARM("Right Arm"), LEFT_LEG("Left Leg"), RIGHT_LEG("Right Leg");
        final String label;
        Part(String label) { this.label = label; }
    }

    /** A part that originally belongs to someone else, attached to a slot of the holder. */
    record Held(UUID owner, Part part) {
        String enc() { return owner + ":" + part.name(); }
        static Held dec(String s) {
            String[] a = s.split(":");
            return new Held(UUID.fromString(a[0]), Part.valueOf(a[1]));
        }
    }

    static class PD {
        EnumSet<Part> lost = EnumSet.noneOf(Part.class);          // empty slots
        EnumMap<Part, Held> held = new EnumMap<>(Part.class);     // slots filled with others' parts
        boolean caged;
        boolean release;     // revived while offline
        long nextNausea;     // runtime only
    }

    static class Menu implements InventoryHolder {            // revive menu
        @Override public Inventory getInventory() { return null; }
    }

    static class RecipeMenu implements InventoryHolder {      // recipe viewer
        @Override public Inventory getInventory() { return null; }
    }

    private final Map<UUID, PD> store = new HashMap<>();
    private final Map<String, ShapedRecipe> recipes = new LinkedHashMap<>();
    private final Map<String, ItemStack> display = new LinkedHashMap<>();
    private final Map<NamespacedKey, Part> ownRecipes = new HashMap<>();
    private File dataFile;
    private NamespacedKey PART_KEY, OWNER_KEY, REVIVE_KEY, SAW_KEY, TARGET_KEY, RECIPE_KEY;

    @Override
    public void onEnable() {
        PART_KEY = new NamespacedKey(this, "body_part");
        OWNER_KEY = new NamespacedKey(this, "part_owner");
        REVIVE_KEY = new NamespacedKey(this, "revive_item");
        SAW_KEY = new NamespacedKey(this, "bone_saw");
        TARGET_KEY = new NamespacedKey(this, "revive_target");
        RECIPE_KEY = new NamespacedKey(this, "recipe_id");
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        load();
        registerRecipes();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 40L, 40L);
    }

    @Override
    public void onDisable() { save(); }

    // ---------------------------------------------------------------- data

    PD pd(UUID id) { return store.computeIfAbsent(id, k -> new PD()); }

    String nameOf(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "Unknown" : n;
    }

    void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection s = y.getConfigurationSection("players");
        if (s == null) return;
        for (String key : s.getKeys(false)) {
            PD d = new PD();
            for (String n : s.getStringList(key + ".lost")) {
                try { d.lost.add(Part.valueOf(n)); } catch (IllegalArgumentException ignored) { }
            }
            ConfigurationSection hs = s.getConfigurationSection(key + ".held");
            if (hs != null) {
                for (String slot : hs.getKeys(false)) {
                    try { d.held.put(Part.valueOf(slot), Held.dec(hs.getString(slot))); }
                    catch (RuntimeException ignored) { }
                }
            }
            d.caged = s.getBoolean(key + ".caged");
            d.release = s.getBoolean(key + ".release");
            store.put(UUID.fromString(key), d);
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        store.forEach((id, d) -> {
            String b = "players." + id;
            y.set(b + ".lost", d.lost.stream().map(Enum::name).toList());
            d.held.forEach((slot, h) -> y.set(b + ".held." + slot.name(), h.enc()));
            y.set(b + ".caged", d.caged);
            y.set(b + ".release", d.release);
        });
        try { y.save(dataFile); } catch (IOException ex) { getLogger().warning(ex.getMessage()); }
    }

    // ---------------------------------------------------------------- items

    ItemStack reviveItem() {
        ItemStack it = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Revive Heart", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Right-click to free a caged player", NamedTextColor.GRAY)));
        m.getPersistentDataContainer().set(REVIVE_KEY, PersistentDataType.STRING, "1");
        it.setItemMeta(m);
        return it;
    }

    ItemStack sawItem() {
        ItemStack it = new ItemStack(Material.SHEARS);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Bone Saw", NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Right-click a player to saw off a part", NamedTextColor.GRAY),
                Component.text("Single use", NamedTextColor.DARK_GRAY)));
        m.getPersistentDataContainer().set(SAW_KEY, PersistentDataType.STRING, "1");
        it.setItemMeta(m);
        return it;
    }

    ItemStack partItem(String ownerName, Part p, UUID owner) {
        ItemStack it = new ItemStack(Material.BONE);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(ownerName + "'s " + p.label, NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Right-click while missing a part to attach it", NamedTextColor.GRAY),
                Component.text("The owner can use it to take it back", NamedTextColor.DARK_GRAY)));
        m.getPersistentDataContainer().set(PART_KEY, PersistentDataType.STRING, p.name());
        m.getPersistentDataContainer().set(OWNER_KEY, PersistentDataType.STRING, owner.toString());
        it.setItemMeta(m);
        return it;
    }

    String tag(ItemStack i, NamespacedKey k) {
        if (i == null || !i.hasItemMeta()) return null;
        return i.getItemMeta().getPersistentDataContainer().get(k, PersistentDataType.STRING);
    }

    // ---------------------------------------------------------------- recipes

    void add(String id, ItemStack result, Map<Character, Material> ing, String... shape) {
        NamespacedKey k = new NamespacedKey(this, id);
        ShapedRecipe r = new ShapedRecipe(k, result);
        r.shape(shape);
        for (Map.Entry<Character, Material> en : ing.entrySet()) r.setIngredient(en.getKey(), en.getValue());
        Bukkit.removeRecipe(k);
        Bukkit.addRecipe(r);
        recipes.put(id, r);
        display.put(id, result);
    }

    void registerRecipes() {
        add("revive_heart", reviveItem(),
                Map.of('D', Material.DIAMOND_BLOCK, 'T', Material.TOTEM_OF_UNDYING, 'N', Material.NETHER_STAR),
                "DTD", "TNT", "DTD");
        add("bone_saw", sawItem(),
                Map.of('I', Material.IRON_INGOT, 'B', Material.BONE, 'D', Material.DIAMOND, 'S', Material.STICK),
                "IBI", "BDB", " S ");

        // "own part" recipes: result is re-written to the crafter's own name in onCraft
        Map<Part, Material> center = Map.of(
                Part.LEFT_ARM, Material.GOLDEN_APPLE,
                Part.RIGHT_ARM, Material.GLISTERING_MELON_SLICE,
                Part.LEFT_LEG, Material.GOLDEN_CARROT,
                Part.RIGHT_LEG, Material.RABBIT_FOOT);
        for (Part p : Part.values()) {
            String id = "own_" + p.name().toLowerCase();
            add(id, partItem("Your", p, new UUID(0, 0)),
                    Map.of('B', Material.BONE_BLOCK, 'D', Material.DIAMOND, 'C', center.get(p)),
                    "BDB", "DCD", "BDB");
            ownRecipes.put(new NamespacedKey(this, id), p);
        }
    }

    void openRecipes(Player p) {
        Inventory inv = Bukkit.createInventory(new RecipeMenu(), 27, Component.text("BodySteal recipes"));
        int slot = 10;
        for (Map.Entry<String, ItemStack> en : display.entrySet()) {
            ItemStack it = en.getValue().clone();
            ItemMeta m = it.getItemMeta();
            m.lore(List.of(Component.text("Click to view recipe", NamedTextColor.YELLOW)));
            m.getPersistentDataContainer().set(RECIPE_KEY, PersistentDataType.STRING, en.getKey());
            it.setItemMeta(m);
            inv.setItem(slot++, it);
        }
        p.openInventory(inv);
    }

    void showRecipe(Player p, String id) {
        ShapedRecipe r = recipes.get(id);
        Inventory inv = Bukkit.createInventory(new RecipeMenu(), 45,
                Component.text("Recipe: " + id.replace('_', ' ')));
        String[] shape = r.getShape();
        Map<Character, ItemStack> ing = r.getIngredientMap();
        for (int row = 0; row < shape.length; row++) {
            for (int col = 0; col < shape[row].length(); col++) {
                ItemStack s = ing.get(shape[row].charAt(col));
                if (s != null) inv.setItem(11 + row * 9 + col, new ItemStack(s.getType()));
            }
        }
        inv.setItem(24, display.get(id).clone());
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta m = back.getItemMeta();
        m.displayName(Component.text("Back", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        m.getPersistentDataContainer().set(RECIPE_KEY, PersistentDataType.STRING, "back");
        back.setItemMeta(m);
        inv.setItem(40, back);
        p.openInventory(inv);
    }

    // ---------------------------------------------------------------- cage

    Location cage() { return getConfig().getLocation("cage"); }

    void buildCage(Location l) {
        World w = l.getWorld();
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        for (int dx = -2; dx <= 2; dx++)
            for (int dy = -1; dy <= 3; dy++)
                for (int dz = -2; dz <= 2; dz++) {
                    boolean inner = Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && dy >= 0 && dy <= 2;
                    w.getBlockAt(x + dx, y + dy, z + dz).setType(inner ? Material.AIR : Material.BEDROCK);
                }
        getConfig().set("cage", new Location(w, x + 0.5, y, z + 0.5));
        saveConfig();
    }

    boolean inCage(Location l) {
        Location c = cage();
        if (c == null || l.getWorld() != c.getWorld()) return false;
        return Math.abs(l.getX() - c.getX()) <= 1.5 && Math.abs(l.getZ() - c.getZ()) <= 1.5
                && l.getY() >= c.getY() - 0.5 && l.getY() <= c.getY() + 3;
    }

    void revive(UUID id, Location to) {
        PD d = pd(id);
        for (Part p : Part.values()) {
            if (d.lost.contains(p) || d.held.containsKey(p)) reclaim(id, p);
        }
        d.caged = false;
        d.lost.clear();
        d.held.clear();
        Player p = Bukkit.getPlayer(id);
        if (p != null) p.teleport(to); else d.release = true;
        save();
    }

    // ---------------------------------------------------------------- logic

    Part firstPresent(PD d) {
        for (Part p : Part.values()) if (!d.lost.contains(p)) return p;
        return null;
    }

    boolean missingOwn(PD d, Part p) { return d.lost.contains(p) || d.held.containsKey(p); }

    /** Owner got their part back: whoever was wearing it loses that slot. */
    void reclaim(UUID owner, Part ownerPart) {
        for (Map.Entry<UUID, PD> en : store.entrySet()) {
            PD h = en.getValue();
            Part slot = null;
            for (Map.Entry<Part, Held> he : h.held.entrySet()) {
                if (he.getValue().owner().equals(owner) && he.getValue().part() == ownerPart) { slot = he.getKey(); break; }
            }
            if (slot == null) continue;
            h.held.remove(slot);
            h.lost.add(slot);
            Player hp = Bukkit.getPlayer(en.getKey());
            if (hp != null) hp.sendMessage(Component.text(nameOf(owner) + " took back their "
                    + ownerPart.label + " - you lost your " + slot.label + "!", NamedTextColor.DARK_RED));
        }
    }

    void attach(Player p, PD d, Held h, Part preferSlot) {
        Part slot = (preferSlot != null && d.lost.contains(preferSlot)) ? preferSlot : d.lost.iterator().next();
        d.lost.remove(slot);
        d.held.put(slot, h);
        if (d.nextNausea == 0) d.nextNausea = System.currentTimeMillis() + rnd();
        p.sendMessage(Component.text("You attached " + nameOf(h.owner()) + "'s " + h.part().label
                + ". It will make you nauseous now and then.", NamedTextColor.DARK_RED));
    }

    /** Remove the victim's next part (death or saw) and hand it to the taker, if any. */
    void strip(Player victim, PD d, Player taker) {
        Part slot = firstPresent(d);
        Held origin = d.held.remove(slot);
        UUID ownerId = origin != null ? origin.owner() : victim.getUniqueId();
        Part ownerPart = origin != null ? origin.part() : slot;
        d.lost.add(slot);
        victim.sendMessage(Component.text("You lost your " + slot.label + "!", NamedTextColor.RED));
        if (taker != null) takePart(taker, ownerId, ownerPart, slot);
    }

    void takePart(Player t, UUID owner, Part ownerPart, Part preferSlot) {
        PD td = pd(t.getUniqueId());
        if (td.caged) return;
        if (owner.equals(t.getUniqueId())) {
            if (td.lost.remove(ownerPart)) t.sendMessage(Component.text("You got your own " + ownerPart.label + " back.", NamedTextColor.GREEN));
            return;
        }
        if (!td.lost.isEmpty()) {
            attach(t, td, new Held(owner, ownerPart), preferSlot);
        } else {
            t.getInventory().addItem(partItem(nameOf(owner), ownerPart, owner)).values()
                    .forEach(i -> t.getWorld().dropItemNaturally(t.getLocation(), i));
            t.sendMessage(Component.text("You took " + nameOf(owner) + "'s " + ownerPart.label + ".", NamedTextColor.GOLD));
        }
    }

    void usePart(Player p, PD d, ItemStack hand, String partName, String ownerStr) {
        Part part = Part.valueOf(partName);
        UUID owner = ownerStr == null ? new UUID(0, 0) : UUID.fromString(ownerStr);

        if (owner.equals(p.getUniqueId())) {                       // my own part
            if (!missingOwn(d, part)) {
                p.sendMessage(Component.text("You still have your " + part.label + ".", NamedTextColor.GRAY));
                return;
            }
            hand.setAmount(hand.getAmount() - 1);
            if (d.held.remove(part) == null) d.lost.remove(part);
            reclaim(owner, part);                                  // whoever wore it loses it
            p.sendMessage(Component.text("Your " + part.label + " grew back!", NamedTextColor.GREEN));
            save();
            return;
        }

        if (d.lost.isEmpty()) {
            p.sendMessage(Component.text("You already have a full body.", NamedTextColor.GRAY));
            return;
        }
        PD od = store.get(owner);
        hand.setAmount(hand.getAmount() - 1);
        if (od != null && !missingOwn(od, part)) {                 // owner already regrew it
            p.sendMessage(Component.text("The part crumbles to dust - its owner already has it back.", NamedTextColor.GRAY));
            return;
        }
        attach(p, d, new Held(owner, part), part);
        save();
    }

    long rnd() { return (20 + ThreadLocalRandom.current().nextInt(31)) * 60_000L; }

    void eff(Player p, PotionEffectType t, int amp) {
        p.addPotionEffect(new PotionEffect(t, 100, amp, true, false, true));
    }

    void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PD d = pd(p.getUniqueId());
            if (d.caged) continue;
            boolean la = d.lost.contains(Part.LEFT_ARM), ra = d.lost.contains(Part.RIGHT_ARM);
            int legs = (d.lost.contains(Part.LEFT_LEG) ? 1 : 0) + (d.lost.contains(Part.RIGHT_LEG) ? 1 : 0);
            if (la) eff(p, PotionEffectType.WEAKNESS, ra ? 1 : 0);
            if (ra) eff(p, PotionEffectType.MINING_FATIGUE, getConfig().getInt("mining-fatigue-level", 3));
            if (legs == 1) eff(p, PotionEffectType.SLOWNESS, 1);
            if (legs == 2) { eff(p, PotionEffectType.SLOWNESS, 3); eff(p, PotionEffectType.JUMP_BOOST, 128); }
            if (!d.held.isEmpty()) {
                if (d.nextNausea == 0) d.nextNausea = now + rnd();
                else if (now >= d.nextNausea) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 300, 0));
                    d.nextNausea = now + rnd();
                }
            } else d.nextNausea = 0;
        }
    }

    boolean noArms(PD d) { return d.lost.contains(Part.LEFT_ARM) && d.lost.contains(Part.RIGHT_ARM); }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getEntity();
        PD d = pd(v.getUniqueId());
        if (d.caged) return;

        if (d.lost.size() >= 4) {                       // only torso+head left -> cage
            if (cage() == null) {
                v.sendMessage(Component.text("No cage is set. An admin must run /laps create.", NamedTextColor.RED));
                return;
            }
            d.caged = true;
            save();
            Bukkit.broadcast(Component.text(v.getName() + " lost everything and was locked in the bedrock cage!", NamedTextColor.DARK_RED));
            return;
        }
        Player k = v.getKiller();
        if (k != null && k.equals(v)) k = null;
        strip(v, d, k);
        save();
    }

    @EventHandler
    public void onSaw(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (tag(hand, SAW_KEY) == null) return;
        e.setCancelled(true);
        if (!(e.getRightClicked() instanceof Player t)) return;
        PD pdata = pd(p.getUniqueId()), td = pd(t.getUniqueId());
        if (pdata.caged || noArms(pdata)) {
            p.sendMessage(Component.text("You can't use the saw without arms.", NamedTextColor.RED));
            return;
        }
        if (td.caged || td.lost.size() >= 4) {
            p.sendMessage(Component.text("There is nothing left to saw off.", NamedTextColor.GRAY));
            return;
        }
        hand.setAmount(hand.getAmount() - 1);
        strip(t, td, p);
        save();
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        PD d = pd(e.getPlayer().getUniqueId());
        if (d.caged && cage() != null) e.setRespawnLocation(cage());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PD d = pd(p.getUniqueId());
        if (d.caged && cage() != null) p.teleport(cage());
        else if (d.release) {
            d.release = false;
            p.teleport(p.getWorld().getSpawnLocation());
            save();
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedPosition()) return;
        PD d = pd(e.getPlayer().getUniqueId());
        Location to = e.getTo();
        if (d.caged) {
            if (cage() != null && !inCage(to)) e.setTo(cage());
            return;
        }
        if (d.lost.size() >= 4) {                       // can't move at all (falling still works)
            Location f = e.getFrom();
            if (to.getX() != f.getX() || to.getZ() != f.getZ()) {
                Location n = to.clone();
                n.setX(f.getX());
                n.setZ(f.getZ());
                e.setTo(n);
            }
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        if (pd(e.getPlayer().getUniqueId()).caged && e.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN)
            e.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        PD d = pd(e.getPlayer().getUniqueId());
        if (d.caged || noArms(d)) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        PD d = pd(e.getPlayer().getUniqueId());
        if (d.caged || noArms(d)) e.setCancelled(true);
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && noArms(pd(p.getUniqueId()))) e.setCancelled(true);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && pd(p.getUniqueId()).caged) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (pd(e.getPlayer().getUniqueId()).caged) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && pd(p.getUniqueId()).caged) e.setCancelled(true);
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && pd(p.getUniqueId()).caged) e.setCancelled(true);
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        PD d = pd(p.getUniqueId());
        if (d.caged) { e.setCancelled(true); return; }
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = e.getItem();
        if (hand == null) return;

        String part = tag(hand, PART_KEY);
        if (part != null) {
            e.setCancelled(true);
            usePart(p, d, hand, part, tag(hand, OWNER_KEY));
            return;
        }
        if (tag(hand, REVIVE_KEY) != null) {
            e.setCancelled(true);
            openMenu(p);
        }
    }

    void openMenu(Player p) {
        Inventory inv = Bukkit.createInventory(new Menu(), 54, Component.text("Revive who?"));
        for (Map.Entry<UUID, PD> en : store.entrySet()) {
            if (!en.getValue().caged || inv.firstEmpty() < 0) continue;
            ItemStack s = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta m = (SkullMeta) s.getItemMeta();
            OfflinePlayer op = Bukkit.getOfflinePlayer(en.getKey());
            m.setOwningPlayer(op);
            m.displayName(Component.text(String.valueOf(op.getName())));
            m.getPersistentDataContainer().set(TARGET_KEY, PersistentDataType.STRING, en.getKey().toString());
            s.setItemMeta(m);
            inv.addItem(s);
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true);
        String t = tag(e.getCurrentItem(), TARGET_KEY);
        if (t == null) return;
        Player p = (Player) e.getWhoClicked();
        ItemStack hand = p.getInventory().getItemInMainHand();
        p.closeInventory();
        if (tag(hand, REVIVE_KEY) == null) return;
        hand.setAmount(hand.getAmount() - 1);
        UUID id = UUID.fromString(t);
        revive(id, p.getLocation());
        Bukkit.broadcast(Component.text(p.getName() + " revived " + nameOf(id) + "!", NamedTextColor.LIGHT_PURPLE));
    }

    @EventHandler
    public void onRecipeClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof RecipeMenu)) return;
        e.setCancelled(true);
        String id = tag(e.getCurrentItem(), RECIPE_KEY);
        if (id == null) return;
        Player p = (Player) e.getWhoClicked();
        if (id.equals("back")) Bukkit.getScheduler().runTask(this, () -> openRecipes(p));
        else if (recipes.containsKey(id)) Bukkit.getScheduler().runTask(this, () -> showRecipe(p, id));
    }

    @EventHandler
    public void onGuiDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (h instanceof RecipeMenu || h instanceof Menu) e.setCancelled(true);
    }

    // body-part items can't be renamed or used as crafting ingredients
    @EventHandler
    public void onAnvil(PrepareAnvilEvent e) {
        ItemStack a = e.getInventory().getFirstItem(), b = e.getInventory().getSecondItem();
        if (tag(a, PART_KEY) != null || tag(b, PART_KEY) != null) e.setResult(null);
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack i : e.getInventory().getMatrix())
            if (tag(i, PART_KEY) != null) { e.getInventory().setResult(null); return; }

        if (e.getRecipe() instanceof ShapedRecipe sr && e.getView().getPlayer() instanceof Player p) {
            Part part = ownRecipes.get(sr.getKey());
            if (part == null) return;
            // result is always YOUR part, and only craftable if you are missing it
            PD d = pd(p.getUniqueId());
            e.getInventory().setResult(missingOwn(d, part) ? partItem(p.getName(), part, p.getUniqueId()) : null);
        }
    }

    // ---------------------------------------------------------------- commands

    void openRecipesFor(CommandSender s) {
        if (s instanceof Player p) openRecipes(p); else s.sendMessage("Players only.");
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (c.getName().equalsIgnoreCase("recipes")) { openRecipesFor(s); return true; }
        if (a.length > 0 && a[0].equalsIgnoreCase("recipes")) { openRecipesFor(s); return true; }

        if (!s.hasPermission("bodysteal.admin")) {
            s.sendMessage(Component.text("No permission. Try /recipes", NamedTextColor.RED));
            return true;
        }
        if (a.length == 0) {
            s.sendMessage(Component.text("/laps <create|revive|parts|giverevive|givesaw|recipes> [player]", NamedTextColor.GRAY));
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "create" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                buildCage(p.getLocation());
                s.sendMessage(Component.text("Bedrock cage created here and set as the ban cage.", NamedTextColor.GREEN));
            }
            case "revive" -> {
                if (a.length < 2) { s.sendMessage("/laps revive <player>"); return true; }
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                Location to = s instanceof Player p ? p.getLocation() : Bukkit.getWorlds().get(0).getSpawnLocation();
                revive(op.getUniqueId(), to);
                s.sendMessage(Component.text("Revived " + a[1] + ".", NamedTextColor.GREEN));
            }
            case "parts" -> {
                if (a.length < 2) { s.sendMessage("/laps parts <player>"); return true; }
                PD d = pd(Bukkit.getOfflinePlayer(a[1]).getUniqueId());
                StringBuilder h = new StringBuilder();
                d.held.forEach((slot, he) -> h.append(slot.label).append("=").append(nameOf(he.owner())).append("'s ").append(he.part().label).append(" "));
                s.sendMessage(Component.text(a[1] + " missing: " + d.lost + " | wearing: " + h + "| caged: " + d.caged));
            }
            case "giverevive" -> { if (s instanceof Player p) p.getInventory().addItem(reviveItem()); }
            case "givesaw" -> { if (s instanceof Player p) p.getInventory().addItem(sawItem()); }
            default -> s.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
        }
        return true;
    }
}
