package me.squidgame.redlightgreenlight;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class RedLightGreenLight extends JavaPlugin implements Listener {

    private boolean tracking = false;
    private boolean debug = false;
    private BukkitTask movementTask;
    private final Map<UUID, Location> lastLocations = new HashMap<>();
    private final Set<UUID> executedPlayers = new HashSet<>();
    private final Queue<UUID> playerQueue = new ArrayDeque<>();
    private static final int PARTICLE_DENSITY = 1;
    private int tickCount = 0;

    private final Map<UUID, Long> lastDetectionTime = new HashMap<>();
    private static final long DETECTION_COOLDOWN_MS = 1000;

    // World and Playground Management
    private World gameWorld = null;
    private Location playground1 = null, playground2 = null;

    // Game Loop
    private boolean gameRunning = false;
    private BukkitTask gameLoopTask;
    private int greenLightDurationSeconds = 3;
    private int redLightDurationSeconds = 5;
    // Configurable delay (in ticks) between green and red light
    private int redLightDelayTicks = 10;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadFromConfig();
        this.getCommand("rlgl").setExecutor(this::onCommand);
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private void reloadFromConfig() {
        reloadConfig();
        FileConfiguration cfg = getConfig();

        String worldName = cfg.getString("playground-world", "");
        if (worldName == null || worldName.isEmpty()) {
            gameWorld = Bukkit.getWorlds().get(0);
        } else {
            gameWorld = Bukkit.getWorld(worldName);
            if (gameWorld == null) {
                getLogger().warning("World '" + worldName + "' not found! Falling back to the default world.");
                gameWorld = Bukkit.getWorlds().get(0);
            }
        }

        String[] p1 = cfg.getString("playground-first-point", "0 0 0").split(" ");
        String[] p2 = cfg.getString("playground-second-point", "0 0 0").split(" ");
        try {
            playground1 = parseLocation(p1);
            playground2 = parseLocation(p2);
        } catch (Exception e) {
            getLogger().severe("Could not parse playground locations from config!");
            playground1 = playground2 = null;
        }

        greenLightDurationSeconds = cfg.getInt("green-light-duration-seconds", 3);
        redLightDurationSeconds = cfg.getInt("red-light-duration-seconds", 5);
        // Load configurable delay between green and red light from config
        redLightDelayTicks = cfg.getInt("red-light-delay-ticks", 10);
    }

    private Location parseLocation(String[] arr) {
        if (arr.length < 3) throw new IllegalArgumentException("Invalid location string array");
        if (gameWorld == null) throw new IllegalStateException("Cannot parse location, gameWorld is not loaded!");
        double x = Double.parseDouble(arr[0]), y = Double.parseDouble(arr[1]), z = Double.parseDouble(arr[2]);
        return new Location(gameWorld, x, y, z);
    }

    @Override
    public void onDisable() {
        stopGameLoop();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("rlgl.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length == 1) {
            if (args[0].equalsIgnoreCase("start")) {
                if (gameRunning) {
                    sender.sendMessage(ChatColor.RED + "The game is already running. Use /rlgl stop first.");
                    return true;
                }
                if (!isPlaygroundSet()) {
                    sender.sendMessage(ChatColor.RED + "The playground is not set! Use a stone axe to set the two points in the world '" + gameWorld.getName() + "'.");
                    return true;
                }
                startGameLoop();
                sender.sendMessage(ChatColor.GREEN + "Red Light, Green Light game started in world '" + gameWorld.getName() + "'!");
                return true;
            }
            if (args[0].equalsIgnoreCase("stop")) {
                if (!gameRunning) {
                    sender.sendMessage(ChatColor.RED + "The game is not currently running.");
                    return true;
                }
                stopGameLoop();
                sender.sendMessage(ChatColor.RED + "Red Light, Green Light game stopped!");
                return true;
            }
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("track-movement")) {
                if (gameRunning) {
                    sender.sendMessage(ChatColor.YELLOW + "This command is controlled by the game loop. Use /rlgl stop to manage tracking manually.");
                    return true;
                }
                boolean enable = Boolean.parseBoolean(args[1]);
                if (enable) {
                    startTracking();
                    sender.sendMessage("Movement tracking enabled.");
                } else {
                    stopTracking();
                    sender.sendMessage("Movement tracking disabled.");
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("debug")) {
                debug = Boolean.parseBoolean(args[1]);
                sender.sendMessage("Debug " + (debug ? "enabled." : "disabled."));
                return true;
            }
        }
        sender.sendMessage(ChatColor.GOLD + "Usage: /rlgl <start|stop|track-movement|debug>");
        return true;
    }

    private void startGameLoop() {
        gameRunning = true;
        reloadFromConfig();

        long greenTicks = greenLightDurationSeconds * 20L;
        long redTicks = redLightDurationSeconds * 20L;
        // The total cycle is greenTicks + redTicks, but tracking only occurs during red

        gameLoopTask = new BukkitRunnable() {
            @Override
            public void run() {
                // Step 1: Green Light Phase
                stopTracking();
                for (Player p : gameWorld.getPlayers()) {
                    p.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "Green Light!", "", 10, 40, 10);
                }

                // Step 2: After greenTicks, show Red Light title (but do NOT track yet)
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (!gameRunning) {
                            this.cancel();
                            return;
                        }
                        for (Player p : gameWorld.getPlayers()) {
                            p.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "Red Light!", "", 10, 40, 10);
                        }
                        // Step 3: After redLightDelayTicks, start tracking (movement detection)
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                if (!gameRunning) {
                                    this.cancel();
                                    return;
                                }
                                startTracking();
                            }
                        }.runTaskLater(RedLightGreenLight.this, redLightDelayTicks);
                    }
                }.runTaskLater(RedLightGreenLight.this, greenTicks);
            }
        }.runTaskTimer(this, 0L, greenTicks + redTicks);
    }

    private void stopGameLoop() {
        gameRunning = false;
        if (gameLoopTask != null) {
            gameLoopTask.cancel();
            gameLoopTask = null;
        }
        stopTracking();
    }

    private boolean isPlaygroundSet() {
        reloadFromConfig();
        if (playground1 == null || playground2 == null || gameWorld == null) return false;
        Location defaultLoc = new Location(gameWorld, 0, 0, 0);
        return !playground1.equals(defaultLoc) && !playground2.equals(defaultLoc) && !playground1.equals(playground2);
    }

    private void startTracking() {
        if (tracking) return;
        tickCount = 0;
        tracking = true;
        movementTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            tickCount++;
            for (Player player : gameWorld.getPlayers()) {
                UUID id = player.getUniqueId();
                long now = System.currentTimeMillis();

                Long lastDetected = lastDetectionTime.get(id);
                if (lastDetected != null && now - lastDetected < DETECTION_COOLDOWN_MS) {
                    continue;
                }

                Location last = lastLocations.get(id);
                Location current = player.getLocation();

                if (last != null && last.getWorld() != null && last.getWorld().equals(current.getWorld())) {
                    double dx = current.getX() - last.getX();
                    double dz = current.getZ() - last.getZ();
                    double horizontalDistanceSquared = dx * dx + dz * dz;
                    double verticalDistance = Math.abs(current.getY() - last.getY());

                    double horizontalThresholdSquared = 0.0004;
                    double verticalThreshold = 0.1;

                    if (horizontalDistanceSquared > horizontalThresholdSquared || verticalDistance > verticalThreshold) {
                        boolean inPlayground = isInPlayground(current);
                        if (debug) {
                            getLogger().info(String.format("%s moved! H_dist_sq: %.4f, V_dist: %.4f | in playground: %s",
                                    player.getName(), horizontalDistanceSquared, verticalDistance, inPlayground));
                        }
                        if (inPlayground) {
                            // Execute kill protocol directly
                            executeKillProtocol(player);
                            lastDetectionTime.put(id, now);
                        }
                    }
                }
                lastLocations.put(id, current.clone());
            }
        }, 0L, 1L);
    }

    private void stopTracking() {
        if (!tracking) return;
        tracking = false;
        if (movementTask != null) {
            movementTask.cancel();
            movementTask = null;
        }
        lastLocations.clear();
        playerQueue.clear();
    }

    private boolean isInPlayground(Location loc) {
        if (playground1 == null || playground2 == null || loc.getWorld() == null) return false;
        if (!loc.getWorld().equals(gameWorld)) return false;

        double xMin = Math.min(playground1.getX(), playground2.getX());
        double xMax = Math.max(playground1.getX(), playground2.getX());
        double yMin = Math.min(playground1.getY(), playground2.getY());
        double yMax = Math.max(playground1.getY(), playground2.getY());
        double zMin = Math.min(playground1.getZ(), playground2.getZ());
        double zMax = Math.max(playground1.getZ(), playground2.getZ());
        return (loc.getX() >= xMin && loc.getX() <= xMax && loc.getY() >= yMin && loc.getY() <= yMax && loc.getZ() >= zMin && loc.getZ() <= zMax);
    }

    private void executeKillProtocol(Player player) {
        if (debug) {
            player.sendMessage("§e[DEBUG] You would have been executed!");
            return;
        }
        player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.2f, 1.4f);
        player.playSound(player.getLocation(), Sound.ENTITY_ARROW_SHOOT, 0.8f, 1.8f);
        executedPlayers.add(player.getUniqueId());
        player.sendMessage("§cYou have been executed");
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && !player.isDead()) {
                player.setHealth(0.0);
            }
        }, 1L);
    }



    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (executedPlayers.remove(player.getUniqueId())) {
            event.setDeathMessage("§c" + player.getName() + " has been executed for moving!");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("rlgl.admin")) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.STONE_AXE) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) return;

        event.setCancelled(true);

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && player.isSneaking()) {
            World selectedWorld = clickedBlock.getWorld();
            if (!selectedWorld.equals(this.gameWorld)) {
                this.gameWorld = selectedWorld;
                updateWorldInConfig(selectedWorld.getName());
                player.sendMessage(ChatColor.AQUA + "[RLGL] Game world set to: " + ChatColor.WHITE + selectedWorld.getName());

                Location defaultResetLoc = new Location(gameWorld, 0, 0, 0);
                playground1 = defaultResetLoc;
                playground2 = defaultResetLoc;
                updatePlaygroundInConfig("playground-first-point", playground1);
                updatePlaygroundInConfig("playground-second-point", playground2);
                player.sendMessage(ChatColor.YELLOW + "[RLGL] Playground points have been reset. Please set them again in the new world.");
            } else {
                player.sendMessage(ChatColor.AQUA + "[RLGL] Game world is already set to " + selectedWorld.getName() + ".");
            }
            return;
        }

        if (!clickedBlock.getWorld().equals(gameWorld)) {
            player.sendMessage(ChatColor.RED + "[RLGL] You can only set points in the selected game world: " + gameWorld.getName());
            player.sendMessage(ChatColor.RED + "Shift-right-click in the correct world to change it.");
            return;
        }

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            playground1 = clickedBlock.getLocation();
            updatePlaygroundInConfig("playground-first-point", playground1);
            player.sendMessage(ChatColor.GREEN + "[RLGL] Playground first point set to: " + locToString(playground1));
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            playground2 = clickedBlock.getLocation();
            updatePlaygroundInConfig("playground-second-point", playground2);
            player.sendMessage(ChatColor.GREEN + "[RLGL] Playground second point set to: " + locToString(playground2));
        }
    }

    private void updateWorldInConfig(String worldName) {
        getConfig().set("playground-world", worldName);
        saveConfig();
    }

    private void updatePlaygroundInConfig(String key, Location loc) {
        getConfig().set(key, locToString(loc));
        saveConfig();
    }

    private String locToString(Location loc) {
        return String.format("%.0f %.0f %.0f", loc.getX(), loc.getY(), loc.getZ());
    }
}