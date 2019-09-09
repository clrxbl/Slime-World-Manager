package com.grinderwolf.swm.nms.v1_14_R1;

import com.mojang.datafixers.DataFixer;
import net.minecraft.server.v1_14_R1.BiomeBase;
import net.minecraft.server.v1_14_R1.Block;
import net.minecraft.server.v1_14_R1.BlockPosition;
import net.minecraft.server.v1_14_R1.Chunk;
import net.minecraft.server.v1_14_R1.ChunkConverter;
import net.minecraft.server.v1_14_R1.ChunkCoordIntPair;
import net.minecraft.server.v1_14_R1.ChunkGenerator;
import net.minecraft.server.v1_14_R1.ChunkProviderServer;
import net.minecraft.server.v1_14_R1.ChunkStatus;
import net.minecraft.server.v1_14_R1.DefinedStructureManager;
import net.minecraft.server.v1_14_R1.Entity;
import net.minecraft.server.v1_14_R1.EntityPlayer;
import net.minecraft.server.v1_14_R1.EnumSkyBlock;
import net.minecraft.server.v1_14_R1.FluidType;
import net.minecraft.server.v1_14_R1.HeightMap;
import net.minecraft.server.v1_14_R1.IBlockAccess;
import net.minecraft.server.v1_14_R1.IChunkAccess;
import net.minecraft.server.v1_14_R1.IRegistry;
import net.minecraft.server.v1_14_R1.LightEngineThreaded;
import net.minecraft.server.v1_14_R1.Packet;
import net.minecraft.server.v1_14_R1.SectionPosition;
import net.minecraft.server.v1_14_R1.TickListChunk;
import net.minecraft.server.v1_14_R1.TicketType;
import net.minecraft.server.v1_14_R1.VillagePlace;
import net.minecraft.server.v1_14_R1.World;
import net.minecraft.server.v1_14_R1.WorldLoadListener;
import net.minecraft.server.v1_14_R1.WorldPersistentData;
import net.minecraft.server.v1_14_R1.WorldServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.craftbukkit.libs.it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import org.bukkit.craftbukkit.libs.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class CustomChunkProvider extends ChunkProviderServer {

    private static final Logger LOGGER = LogManager.getLogger("SWM Chunk Provider");

    // TODO use another Long2ObjectMap, as paper removes relocation when building the server jar
    private final Long2ObjectMap<IChunkAccess> chunks = new Long2ObjectOpenHashMap<>();

    public CustomChunkProvider(WorldServer worldserver, File file, DataFixer datafixer, DefinedStructureManager definedstructuremanager, Executor executor, ChunkGenerator<?> chunkgenerator, int i, WorldLoadListener worldloadlistener, Supplier<WorldPersistentData> supplier) {
        super(worldserver, file, datafixer, definedstructuremanager, executor, chunkgenerator, i, worldloadlistener, supplier);
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
        LOGGER.debug("Loading chunk (" + x + ", " + z + ") on world " + getWorld().getWorldData().getName());

        long index = ((long) z) * Integer.MAX_VALUE + ((long) x);
        IChunkAccess chunk = chunks.get(index);

        if (chunk == null) {
            LOGGER.debug("Failed to load chunk (" + x + ", " + z + ") (" + index + ") on world "
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
        return super.runTasks(); // There aren't any tasks
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
    public void movePlayer(EntityPlayer entityplayer) {
        super.movePlayer(entityplayer);
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
