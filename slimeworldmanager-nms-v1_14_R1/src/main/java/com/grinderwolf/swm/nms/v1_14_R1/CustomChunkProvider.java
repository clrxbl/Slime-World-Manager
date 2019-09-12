package com.grinderwolf.swm.nms.v1_14_R1;

import com.flowpowered.nbt.CompoundMap;
import com.flowpowered.nbt.CompoundTag;
import com.flowpowered.nbt.LongArrayTag;
import com.grinderwolf.swm.api.world.SlimeChunk;
import com.grinderwolf.swm.api.world.SlimeChunkSection;
import com.grinderwolf.swm.nms.CraftSlimeChunk;
import com.grinderwolf.swm.nms.CraftSlimeWorld;
import net.minecraft.server.v1_14_R1.BiomeBase;
import net.minecraft.server.v1_14_R1.Block;
import net.minecraft.server.v1_14_R1.BlockPosition;
import net.minecraft.server.v1_14_R1.Chunk;
import net.minecraft.server.v1_14_R1.ChunkConverter;
import net.minecraft.server.v1_14_R1.ChunkCoordIntPair;
import net.minecraft.server.v1_14_R1.ChunkGenerator;
import net.minecraft.server.v1_14_R1.ChunkProviderServer;
import net.minecraft.server.v1_14_R1.ChunkSection;
import net.minecraft.server.v1_14_R1.ChunkStatus;
import net.minecraft.server.v1_14_R1.DimensionManager;
import net.minecraft.server.v1_14_R1.Entity;
import net.minecraft.server.v1_14_R1.EntityPlayer;
import net.minecraft.server.v1_14_R1.EntityTypes;
import net.minecraft.server.v1_14_R1.EnumSkyBlock;
import net.minecraft.server.v1_14_R1.FluidType;
import net.minecraft.server.v1_14_R1.HeightMap;
import net.minecraft.server.v1_14_R1.IBlockAccess;
import net.minecraft.server.v1_14_R1.IChunkAccess;
import net.minecraft.server.v1_14_R1.IRegistry;
import net.minecraft.server.v1_14_R1.LightEngine;
import net.minecraft.server.v1_14_R1.LightEngineThreaded;
import net.minecraft.server.v1_14_R1.MinecraftServer;
import net.minecraft.server.v1_14_R1.NBTTagCompound;
import net.minecraft.server.v1_14_R1.NBTTagList;
import net.minecraft.server.v1_14_R1.Packet;
import net.minecraft.server.v1_14_R1.PlayerChunkMap;
import net.minecraft.server.v1_14_R1.SectionPosition;
import net.minecraft.server.v1_14_R1.TickListChunk;
import net.minecraft.server.v1_14_R1.TicketType;
import net.minecraft.server.v1_14_R1.TileEntity;
import net.minecraft.server.v1_14_R1.VillagePlace;
import net.minecraft.server.v1_14_R1.World;
import net.minecraft.server.v1_14_R1.WorldLoadListener;
import net.minecraft.server.v1_14_R1.WorldPersistentData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.craftbukkit.libs.it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.bukkit.craftbukkit.libs.it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.bukkit.craftbukkit.libs.it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import org.bukkit.craftbukkit.libs.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class CustomChunkProvider extends ChunkProviderServer {

    private static final Logger LOGGER = LogManager.getLogger("SWM Chunk Provider");

    // TODO use another Long2ObjectMap, as paper removes relocation when building the server jar
    private final Long2ObjectMap<IChunkAccess> chunks = new Long2ObjectOpenHashMap<>();
    private final Int2ObjectMap<PlayerChunkMap.EntityTracker> trackedEntities = new Int2ObjectOpenHashMap<>();

    public CustomChunkProvider(CustomWorldServer server, ChunkGenerator<?> chunkgenerator, WorldLoadListener worldloadlistener) {
        super(server, server.getDataManager().getDirectory(), MinecraftServer.getServer().aB(), server.getDataManager().f(), MinecraftServer.getServer().executorService, chunkgenerator, server.spigotConfig.viewDistance, worldloadlistener, () -> {
            return MinecraftServer.getServer().getWorldServer(DimensionManager.OVERWORLD).getWorldPersistentData();
        });

        CraftSlimeWorld world = server.getSlimeWorld();
        LOGGER.info("Loading chunks for world " + world.getName());

        for (SlimeChunk chunk : world.getChunks().values()) {
            int x = chunk.getX();
            int z = chunk.getZ();

            LOGGER.info("Loading chunk (" + x + ", " + z + ") on world " + world.getName());

            ChunkCoordIntPair pos = new ChunkCoordIntPair(x, z);
            BlockPosition.MutableBlockPosition mutableBlockPosition = new BlockPosition.MutableBlockPosition();

            // Tick lists
            TickListChunk<Block> airChunkTickList = new TickListChunk<>(IRegistry.BLOCK::getKey, new ArrayList<>());
            TickListChunk<FluidType> fluidChunkTickList = new TickListChunk<>(IRegistry.FLUID::getKey, new ArrayList<>());

            // Biomes
            BiomeBase[] biomeBaseArray = new BiomeBase[256];
            int[] biomeIntArray = chunk.getBiomes();

            for (int i = 0; i < biomeIntArray.length; i++) {
                biomeBaseArray[i] = IRegistry.BIOME.fromId(biomeIntArray[i]);

                if (biomeBaseArray[i] == null) {
                    biomeBaseArray[i] = server.getChunkProvider().getChunkGenerator().getWorldChunkManager().getBiome(mutableBlockPosition
                            .c((i & 15) + (pos.x << 4), 0, (i >> 4 & 15) + (pos.z << 4)));
                }
            }

            // Chunk sections
            LOGGER.debug("Loading chunk sections for chunk (" + pos.x + ", " + pos.z + ") on world " + world.getName());
            ChunkSection[] sections = new ChunkSection[16];
            LightEngine lightEngine = server.getChunkProvider().getLightEngine();

            lightEngine.b(pos, true);

            for (int sectionId = 0; sectionId < chunk.getSections().length; sectionId++) {
                SlimeChunkSection slimeSection = chunk.getSections()[sectionId];

                if (slimeSection != null && slimeSection.getBlockStates().length > 0) { // If block states array is empty, it's just a fake chunk made by the createEmptyWorld method
                    ChunkSection section = new ChunkSection(sectionId << 4);

                    LOGGER.debug("ChunkSection #" + sectionId + " - Chunk (" + pos.x + ", " + pos.z + ") - World " + world.getName() + ":");
                    LOGGER.debug("Block palette:");
                    LOGGER.debug(slimeSection.getPalette().toString());
                    LOGGER.debug("Block states array:");
                    LOGGER.debug(slimeSection.getBlockStates());
                    LOGGER.debug("Block light array:");
                    LOGGER.debug(slimeSection.getBlockLight() != null ? slimeSection.getBlockLight().getBacking() : "Not present");
                    LOGGER.debug("Sky light array:");
                    LOGGER.debug(slimeSection.getSkyLight() != null ? slimeSection.getSkyLight().getBacking() : "Not present");

                    section.getBlocks().a((NBTTagList) Converter.convertTag(slimeSection.getPalette()), slimeSection.getBlockStates());

                    if (slimeSection.getBlockLight() != null) {
                        lightEngine.a(EnumSkyBlock.BLOCK, SectionPosition.a(pos, sectionId), Converter.convertArray(slimeSection.getBlockLight()));
                    }

                    if (slimeSection.getSkyLight() != null) {
                        lightEngine.a(EnumSkyBlock.SKY, SectionPosition.a(pos, sectionId), Converter.convertArray(slimeSection.getSkyLight()));
                    }

                    section.recalcBlockCounts();
                    sections[sectionId] = section;
                }
            }

            Consumer<Chunk> loadEntities = (nmsChunk) -> {

                // Load tile entities
                LOGGER.debug("Loading tile entities for chunk (" + pos.x + ", " + pos.z + ") on world " + world.getName());
                List<CompoundTag> tileEntities = chunk.getTileEntities();
                int loadedEntities = 0;

                if (tileEntities != null) {
                    for (CompoundTag tag : tileEntities) {
                        Optional<String> type = tag.getStringValue("id");

                        // Sometimes null tile entities are saved
                        if (type.isPresent()) {
                            TileEntity entity = TileEntity.create((NBTTagCompound) Converter.convertTag(tag));

                            if (entity != null) {
                                nmsChunk.a(entity);
                                loadedEntities++;
                            }
                        }
                    }
                }

                LOGGER.debug("Loaded " + loadedEntities + " tile entities for chunk (" + pos.x + ", " + pos.z + ") on world " + world.getName());

                // Load entities
                LOGGER.debug("Loading entities for chunk (" + pos.x + ", " + pos.z + ") on world " + world.getName());
                List<CompoundTag> entities = chunk.getEntities();
                loadedEntities = 0;

                if (entities != null) {
                    for (CompoundTag tag : entities) {
                        EntityTypes.a((NBTTagCompound) Converter.convertTag(tag), nmsChunk.world, (entity) -> {

                            nmsChunk.a(entity);
                            return entity;

                        });

                        nmsChunk.d(true);
                        loadedEntities++;
                    }
                }

                LOGGER.debug("Loaded " + loadedEntities + " entities for chunk (" + pos.x + ", " + pos.z + ") on world " + world.getName());
                LOGGER.debug("Loaded chunk (" + pos.x + ", " + pos.z + ") on world " + world.getName());

            };

            CraftSlimeChunk craftChunk = (CraftSlimeChunk) chunk;
            CompoundTag upgradeDataTag = craftChunk.getUpgradeData();
            Chunk nmsChunk = new Chunk(server, pos, biomeBaseArray, upgradeDataTag == null ? ChunkConverter.a : new ChunkConverter((NBTTagCompound)
                    Converter.convertTag(upgradeDataTag)), airChunkTickList, fluidChunkTickList, 0L, sections, loadEntities);

            // Height Maps
            EnumSet<HeightMap.Type> heightMapTypes = nmsChunk.getChunkStatus().h();
            CompoundMap heightMaps = chunk.getHeightMaps().getValue();
            EnumSet<HeightMap.Type> unsetHeightMaps = EnumSet.noneOf(HeightMap.Type.class);

            for (HeightMap.Type type : heightMapTypes) {
                String name = type.a();

                if (heightMaps.containsKey(name)) {
                    LongArrayTag heightMap = (LongArrayTag) heightMaps.get(name);
                    nmsChunk.a(type, heightMap.getValue());
                } else {
                    unsetHeightMaps.add(type);
                }
            }

            HeightMap.a(nmsChunk, unsetHeightMaps);

            long index = ((long) z) * Integer.MAX_VALUE + ((long) x);
            chunks.put(index, nmsChunk);
        }
    }

    @Override
    public LightEngineThreaded getLightEngine() {
        return super.getLightEngine();
    }

    // Amount of chunks loaded
    @Override
    public int b() {
        return chunks.size();
    }

    @Override
    public IChunkAccess getChunkAt(int x, int z, ChunkStatus status, boolean load) {
        LOGGER.debug("Obtaining chunk (" + x + ", " + z + ") on world " + getWorld().getWorldData().getName());

        long index = ((long) z) * Integer.MAX_VALUE + ((long) x);
        IChunkAccess chunk = chunks.get(index);

        if (chunk == null) {
            LOGGER.debug("Failed to obtain chunk (" + x + ", " + z + ") (" + index + ") on world "
                    + getWorld().getWorldData().getName() + ": chunk does not exist. Generating empty one...");

            // Create new chunk
            BlockPosition.MutableBlockPosition mutableBlockPosition = new BlockPosition.MutableBlockPosition();

            // Tick lists
            TickListChunk<Block> airChunkTickList = new TickListChunk<>(IRegistry.BLOCK::getKey, new ArrayList<>());
            TickListChunk<FluidType> fluidChunkTickList = new TickListChunk<>(IRegistry.FLUID::getKey, new ArrayList<>());

            BiomeBase[] biomeBaseArray = new BiomeBase[256];

            for (int i = 0; i < biomeBaseArray.length; i++) {
                biomeBaseArray[i] = getWorld().getChunkProvider().getChunkGenerator().getWorldChunkManager().getBiome(mutableBlockPosition
                        .d((i & 15) + (x << 4), 0, (i >> 4 & 15) + (z << 4)));
            }

            ChunkCoordIntPair pos = new ChunkCoordIntPair(x, z);
            chunk = new Chunk(getWorld(), pos, biomeBaseArray, ChunkConverter.a, airChunkTickList, fluidChunkTickList, 0L, null, null);
            HeightMap.a(chunk, ChunkStatus.FULL.h());

            getWorld().getChunkProvider().getLightEngine().b(pos, true);
            chunks.put(index, chunk);
        }

        return chunk;
    }

    @Override
    public Chunk getChunkAt(int x, int z, boolean load) {
        return (Chunk) getChunkAt(x, z, ChunkStatus.FULL, load);
    }

    // Get chunk if on main thread
    @Nullable
    @Override
    public Chunk a(int x, int z) {
        return getChunkAt(x, z, true);
    }

    @Override
    public boolean isLoaded(int x, int z) {
        return true;
    }

    // Get chunk even if it isn't full yet
    @Override
    public IBlockAccess c(int x, int z) {
        return getChunkAt(x, z, true);
    }

    @Override // Returns true if full chunk exists and it's loaded
    public boolean b(int x, int z) {
        return true;
    }

    @Override
    public World getWorld() {
        return super.getWorld();
    }

    @Override
    public boolean runTasks() {
        return true; // There aren't any tasks
    }

    // Returns true if the chunk of the entity reached the full status
    @Override
    public boolean a(Entity entity) {
        return true;
    }

    // Returns true if the chunk is available
    @Override
    public boolean a(ChunkCoordIntPair pos) {
        return true;
    }

    // Returns true if the chunk of the block is available
    @Override
    public boolean a(BlockPosition pos) {
        return true;
    }

    // Returns true if the chunk of the entity reached the full status and calls chunk.B()? TODO check
    @Override
    public boolean b(Entity entity) {
        return true;
    }

    @Override
    public void save(boolean flag) {

    }

    @Override
    public void close() {

    }

    @Override
    public void close(boolean save) {

    }

    // Unload every chunk and delete all pending tickets
    @Override
    public void purgeUnload() {

    }

    // Delete all pending tickets and unload every chunk if BooleanSupplier retuns true
    @Override
    public void tick(BooleanSupplier supplier) {

    }

    @Override
    public String getName() {
        return "SlimeChunkCache: " + this.h();
    }

    // Returns how many tasks are left on the load queue
    @Override
    public int f() {
        return 0;
    }

    @Override
    public ChunkGenerator<?> getChunkGenerator() {
        return super.getChunkGenerator();
    }

    // Get the amount of visible chunks
    @Override
    public int h() {
        return chunks.size();
    }

    // Increment the amount of dirty blocks on a chunk
    @Override
    public void flagDirty(BlockPosition pos) { // This is related to the a(Chunk) method
        super.flagDirty(pos);
    }

    // Does something? TODO check
    @Override
    public void a(EnumSkyBlock enumskyblock, SectionPosition sectionposition) {
        super.a(enumskyblock, sectionposition);
    }

    // In theory the whole ticket system can be completely ignored

    @Override
    public <T> void addTicket(TicketType<T> type, ChunkCoordIntPair pos, int dueTick, T data) {

    }

    @Override
    public <T> void removeTicket(TicketType<T> type, ChunkCoordIntPair pos, int dueTick, T data) {

    }

    // Add or remove a FORCED type ticket
    @Override
    public void a(ChunkCoordIntPair pos, boolean flag) {

    }

    // Updates tracking data for the player and sends new chunks if needed
    @Override
    public void movePlayer(EntityPlayer player) {
        super.movePlayer(player);
    }

    @Override
    public void removeEntity(Entity entity) {
        super.removeEntity(entity);
    }

    @Override
    public void addEntity(Entity entity) {
        super.addEntity(entity);
    }

    // Sends a packet to all entities
    @Override
    public void broadcastIncludingSelf(Entity entity, Packet<?> packet) {
        super.broadcastIncludingSelf(entity, packet);
    }

    // Sends a packet to all entities but the one provided
    @Override
    public void broadcast(Entity entity, Packet<?> packet) {
        super.broadcast(entity, packet);
    }

    @Override
    public void setViewDistance(int i) { // TODO see if override is necessary
        super.setViewDistance(i);
    }

    // Allow monsters and animals
    @Override
    public void a(boolean allowMonsters, boolean allowAnimals) {
        super.a(allowMonsters, allowAnimals);
    }

    @Override
    public WorldPersistentData getWorldPersistentData() {
        return super.getWorldPersistentData();
    }

    @Override
    public VillagePlace j() {
        return super.j();
    }
}
