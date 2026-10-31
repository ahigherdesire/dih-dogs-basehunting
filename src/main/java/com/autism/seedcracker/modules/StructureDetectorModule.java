package com.autism.seedcracker.modules;

import java.util.List;

import com.autism.seedcracker.finder.landmark.LocateReply;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.render.BlockEspRenderer;

import dihclient.api.module.BoolSetting;
import dihclient.api.module.ColorSetting;
import dihclient.api.module.EnumSetting;
import dihclient.api.module.IntSetting;
import dihclient.modules.Module;
import dihclient.util.DihClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.phys.AABB;

/**
 * Structure Detector.
 *
 * Locates natural structures via the server's {@code /locate structure} command so you can tell a
 * natural landmark apart from a player-activity signal. A generated structure does not rule out
 * nearby player builds. Locate needs server permission; results are bound to one outstanding
 * query, dimension and structure selection. Periodic queries obey the configured interval.
 */
public final class StructureDetectorModule extends Module {

    public enum StructureType {
        VILLAGE("village"), PILLAGER_OUTPOST("pillager_outpost"), DESERT_PYRAMID("desert_pyramid"),
        JUNGLE_TEMPLE("jungle_pyramid"), IGLOO("igloo"), SWAMP_HUT("swamp_hut"),
        OCEAN_MONUMENT("monument"), SHIPWRECK("shipwreck"), RUINED_PORTAL("ruined_portal"),
        DUNGEON("dungeon"), TRIAL_CHAMBERS("trial_chambers"), STRONGHOLD("stronghold"),
        MINESHAFT("mineshaft"), ANCIENT_CITY("ancient_city"), WOODLAND_MANSION("mansion"),
        NETHER_FORTRESS("fortress"), BASTION("bastion_remnant"), END_CITY("end_city");

        public final String id;
        StructureType(String id) { this.id = id; }
    }

    private final EnumSetting<StructureType> structure = add(new EnumSetting<>(
            "structure", "Structure", StructureType.TRIAL_CHAMBERS, StructureType.values())
        .description("Structure to locate (server permission required). Dungeons are features and cannot be /located.").group("General"));
    private final BoolSetting autoScan = add(new BoolSetting("auto-scan", "Scan on enable", true)
        .description("Run a /locate query as soon as the module is enabled.").group("General"));
    private final IntSetting rescanTicks = add(new IntSetting("rescan", "Re-scan (ticks)", 200, 0, 2400, 20)
        .description("How often to re-locate the structure (0 = only once).").group("General"));
    private final BoolSetting markInWorld = add(new BoolSetting("mark-in-world", "Mark in world", true)
        .description("Draw a landmark marker (Y=64 if omitted by the server). Results expire after 5 minutes without a fresh reply.").group("Render"));
    private final ColorSetting markColor = add(new ColorSetting("mark-colour", "Mark colour", 0x8050C8FF).group("Render"));
    private final BoolSetting notify = add(new BoolSetting("notify", "Notify", true)
        .description("Chat the located coordinates.").group("General"));

    private int cooldown = -1;
    private volatile BlockPos located;
    private volatile String locatedName;
    private volatile LocateReply.Request pending;
    private Object world;
    private record QuerySettings(StructureType type, boolean auto, int interval) {}
    private QuerySettings settings;
    private long locatedUntil;
    private String status = "none found";

    public StructureDetectorModule() {
        super(SeedcrackerAddon.ID + ":structure-detector", "Structure Detector",
            "Locates natural structures via /locate so they aren't mistaken for player bases.");
    }

    /** Grouped into the Finders hub (it's a simple on/off tool); hidden from the main menu. */
    @Override
    public boolean showInModuleMenu() {
        return false;
    }

    @Override public void onEnable() { clear(); }
    @Override public void onDisable() { clear(); }

    private void clear() {
        pending = null;
        located = null;
        locatedName = null;
        locatedUntil = 0;
        cooldown = -1;
        world = null;
        settings = null;
        status = "none found";
        BlockEspRenderer.clearBox(id() + ":s");
    }

    @Override
    public void onGameLeft() {
        clear();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) { clear(); return; }
        var current = new QuerySettings(structure.get(), autoScan.get(), rescanTicks.get());
        if (world != mc.level || !current.equals(settings)) {
            clear();
            world = mc.level;
            settings = current;
            cooldown = current.auto() ? 0 : -1;
        }
        long now = System.nanoTime();
        if (located != null && now - locatedUntil > 0) { located = null; locatedName = null; status = "result expired"; }
        if (markInWorld.get() && located != null) {
            BlockEspRenderer.feedBoxes(id() + ":s", List.of(new AABB(located).inflate(2.0, 4.0, 2.0)), markColor.get());
        } else BlockEspRenderer.clearBox(id() + ":s");
        if (pending != null && !pending.valid(mc.level, current.type().id, now)) {
            pending = null;
            status = "no reply (server permission required)";
        }
        if (current.type() == StructureType.DUNGEON) { status = "dungeons cannot be /located"; return; }
        if (cooldown < 0) return;
        if (cooldown > 0) { cooldown--; return; }
        if (pending != null) return;
        String tag = current.type() == StructureType.VILLAGE || current.type() == StructureType.RUINED_PORTAL ? "#" : "";
        pending = new LocateReply.Request(mc.level, current.type().id, now + 5_000_000_000L);
        status = "awaiting locate reply";
        mc.getConnection().sendCommand("locate structure " + tag + "minecraft:" + current.type().id);
        cooldown = current.interval() <= 0 ? -1 : current.interval();
    }

    @Override
    public boolean onPacketReceive(Packet<?> packet) {
        if (!(packet instanceof ClientboundSystemChatPacket chat) || chat.overlay()) return false;
        var query = pending;
        Minecraft mc = Minecraft.getInstance();
        if (!isEnabled() || query == null || !query.valid(mc.level, structure.get().id, System.nanoTime())) return false;
        var content = chat.content();
        if (content == null) return false;
        LocateReply.Location result;
        if (content.getContents() instanceof TranslatableContents translated
            && "commands.locate.structure.success".equals(translated.getKey()) && translated.getArgs().length >= 2) {
            result = LocateReply.parse(query.structure(), plain(translated.getArgs()[0]), plain(translated.getArgs()[1]));
        } else result = LocateReply.parseEnglish(query.structure(), content.getString());
        if (result != null) mc.execute(() -> accept(mc, query, result));
        return false;
    }

    private static String plain(Object value) { return value instanceof Component component ? component.getString() : String.valueOf(value); }

    private void accept(Minecraft mc, LocateReply.Request query, LocateReply.Location result) {
        if (!isEnabled() || pending != query || !query.valid(mc.level, structure.get().id, System.nanoTime())) return;
        pending = null;
        BlockPos found = new BlockPos(result.x(), result.y(), result.z());
        boolean changed = !found.equals(located) || !query.structure().equals(locatedName);
        located = found;
        locatedName = query.structure();
        locatedUntil = System.nanoTime() + 300_000_000_000L;
        status = "natural landmark";
        if (changed && notify.get()) DihClientMessaging.sendPrefixed("§b[Structure] §fNearest §d" + query.structure()
            + "§f at §e" + result.x() + " " + (result.knownY() ? Integer.toString(result.y()) : "~") + " " + result.z());
    }

    @Override public String info() {
        BlockPos pos = located;
        if (pos == null) return status;
        return locatedName + " @ " + pos.getX() + "," + pos.getZ();
    }
}
