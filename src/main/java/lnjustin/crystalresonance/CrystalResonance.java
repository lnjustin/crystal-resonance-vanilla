package lnjustin.crystalresonance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

public class CrystalResonance implements ModInitializer {
	static final Logger LOGGER = LoggerFactory.getLogger("crystal-resonance");
	private static final int CHUNKS_PER_TICK = 4;
	private static final Map<UUID, Set<BlockPos>> pendingNodeSelection = new HashMap<>();
	private static ToolConfig toolConfig = ToolConfig.defaults();
	private static int tickCounter = 0;

	@Override
	public void onInitialize() {
		toolConfig = ToolConfig.load();

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
			pendingNodeSelection.remove(handler.player.getUUID())
		);

		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (world.isClientSide()) return InteractionResult.PASS;

			ServerLevel serverWorld = (ServerLevel) world;
			ItemStack stack = player.getItemInHand(hand);
			BlockPos pos = hit.getBlockPos();
			NodeState state = NodeState.get(serverWorld);
			BlockState blockState = serverWorld.getBlockState(pos);

			pendingNodeSelection.putIfAbsent(player.getUUID(), new HashSet<>());

			if (blockState.getBlock() instanceof LeverBlock) {
				Set<BlockPos> selected = pendingNodeSelection.get(player.getUUID());
				if (selected == null || selected.isEmpty()) return InteractionResult.PASS;

				Mode mode;
				if (toolConfig.matchesLinkTool(stack)) {
					mode = Mode.ANY_PLAYER;
				} else if (toolConfig.matchesSelectTool(stack)) {
					mode = Mode.OWNER_ONLY;
				} else {
					return InteractionResult.PASS;
					}

					state.linkNodes(selected, pos, mode, player.getUUID());
					consumeTool(player, stack);
					player.displayClientMessage(Component.literal("Linked " + selected.size() + " node(s) to lever."), true);
					serverWorld.sendParticles(toolConfig.linkParticle(), pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 1, 0.0, 0.05, 0.0, 0.0);
					selected.clear();
					return InteractionResult.SUCCESS;
				}

			if (toolConfig.matchesSelectTool(stack) || toolConfig.matchesLinkTool(stack)) {
				state.getOrCreateNode(pos, player.getUUID());
				pendingNodeSelection.get(player.getUUID()).add(pos);
				consumeTool(player, stack);
				serverWorld.sendParticles(toolConfig.selectParticle(), pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 1, 0.0, 0.05, 0.0, 0.0);
				return InteractionResult.SUCCESS;
			}

				if (toolConfig.matchesUnlinkTool(stack)) {
					boolean unlinked = state.unlink(pos, player.getUUID());
					if (unlinked) {
						consumeTool(player, stack);
					}
					player.displayClientMessage(Component.literal(unlinked ? "Node unlinked." : "Nothing to unlink."), true);
					serverWorld.sendParticles(toolConfig.unlinkParticle(), pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 1, 0.0, 0.02, 0.0, 0.0);
					return InteractionResult.SUCCESS;
				}

			return InteractionResult.PASS;
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ServerLevel world = server.overworld();
			NodeState state = NodeState.get(world);

			List<Long> chunkKeys = new ArrayList<>(state.chunkKeys());
			if (!chunkKeys.isEmpty()) {
				int start = tickCounter++ % chunkKeys.size();

				for (int i = 0; i < Math.min(CHUNKS_PER_TICK, chunkKeys.size()); i++) {
					long key = chunkKeys.get((start + i) % chunkKeys.size());
					int cx = ChunkPos.getX(key);
					int cz = ChunkPos.getZ(key);
					if (!world.hasChunk(cx, cz)) continue;

					Set<BlockPos> levers = state.getLeversInChunk(key);
					if (levers == null) continue;

					for (BlockPos leverPos : levers) {
						if (!world.hasChunkAt(leverPos)) continue;

						Set<BlockPos> linked = state.getLinkedNodes(leverPos);
						if (linked == null || linked.isEmpty()) continue;

						boolean anyActive = false;
						for (BlockPos nodePos : linked) {
							Node node = state.getNode(nodePos);
							if (node != null && node.active()) {
								anyActive = true;
								break;
							}
						}

						BlockState leverState = world.getBlockState(leverPos);
						if (leverState.getBlock() instanceof LeverBlock && leverState.hasProperty(LeverBlock.POWERED)) {
							boolean current = leverState.getValue(LeverBlock.POWERED);
							if (current != anyActive) {
								BlockState updatedLeverState = leverState.setValue(LeverBlock.POWERED, anyActive);
								world.setBlock(leverPos, updatedLeverState, 3);
								world.updateNeighborsAt(leverPos, updatedLeverState.getBlock());
								world.updateNeighborsAt(getLeverSupportPos(leverPos, updatedLeverState), updatedLeverState.getBlock());
							}
						}
					}
				}
			}

			for (Node node : state.getNodes()) {
				BlockPos pos = node.pos();
				if (!world.hasChunkAt(pos)) continue;

				boolean active = false;
				for (ServerPlayer player : world.players()) {
					if (!state.matchesNodeTrigger(player, pos)) continue;

					if (node.mode() == Mode.ANY_PLAYER) {
						active = true;
						break;
					}

					if (node.mode() == Mode.OWNER_ONLY
							&& node.owner() != null
							&& node.owner().equals(player.getUUID())) {
						active = true;
						break;
					}
				}

				state.setActive(pos, active);
			}

			if (world.getGameTime() % 10 != 0) return;

			for (Set<BlockPos> selected : pendingNodeSelection.values()) {
					for (BlockPos pos : selected) {
						if (!world.hasChunkAt(pos)) continue;
						world.sendParticles(toolConfig.selectParticle(), pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 1, 0.1, 0.1, 0.1, 0.0);
					}
				}
			});
	}

	private static BlockPos getLeverSupportPos(BlockPos leverPos, BlockState leverState) {
		AttachFace face = leverState.getValue(FaceAttachedHorizontalDirectionalBlock.FACE);
		Direction facing = leverState.getValue(FaceAttachedHorizontalDirectionalBlock.FACING);
		return switch (face) {
			case FLOOR -> leverPos.below();
			case CEILING -> leverPos.above();
			case WALL -> leverPos.relative(facing.getOpposite());
		};
	}

	private static void consumeTool(Player player, ItemStack stack) {
		if (!player.isCreative()) {
			stack.shrink(1);
		}
	}
}

final class ToolConfig {
	static final List<String> DEFAULT_SELECT_TOOLS = List.of("minecraft:amethyst_shard");
	static final List<String> DEFAULT_LINK_TOOLS = List.of("minecraft:echo_shard");
	static final List<String> DEFAULT_UNLINK_TOOLS = List.of("minecraft:quartz");
	static final String DEFAULT_SELECT_PARTICLE = "minecraft:end_rod";
	static final String DEFAULT_LINK_PARTICLE = "minecraft:happy_villager";
	static final String DEFAULT_UNLINK_PARTICLE = "minecraft:smoke";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("crystal-resonance.json");

	private final Set<Item> selectTools;
	private final Set<Item> linkTools;
	private final Set<Item> unlinkTools;
	private final ParticleOptions selectParticle;
	private final ParticleOptions linkParticle;
	private final ParticleOptions unlinkParticle;

	private ToolConfig(
		Set<Item> selectTools,
		Set<Item> linkTools,
		Set<Item> unlinkTools,
		ParticleOptions selectParticle,
		ParticleOptions linkParticle,
		ParticleOptions unlinkParticle
	) {
		this.selectTools = selectTools;
		this.linkTools = linkTools;
		this.unlinkTools = unlinkTools;
		this.selectParticle = selectParticle;
		this.linkParticle = linkParticle;
		this.unlinkParticle = unlinkParticle;
	}

	static ToolConfig defaults() {
		return fromDiskModel(new ToolConfigDisk(
			DEFAULT_SELECT_TOOLS,
			DEFAULT_LINK_TOOLS,
			DEFAULT_UNLINK_TOOLS,
			DEFAULT_SELECT_PARTICLE,
			DEFAULT_LINK_PARTICLE,
			DEFAULT_UNLINK_PARTICLE
		));
	}

	static ToolConfig load() {
		ToolConfigDisk diskConfig = new ToolConfigDisk(
			DEFAULT_SELECT_TOOLS,
			DEFAULT_LINK_TOOLS,
			DEFAULT_UNLINK_TOOLS,
			DEFAULT_SELECT_PARTICLE,
			DEFAULT_LINK_PARTICLE,
			DEFAULT_UNLINK_PARTICLE
		);

		try {
			if (Files.notExists(CONFIG_PATH)) {
				Files.createDirectories(CONFIG_PATH.getParent());
				Files.writeString(CONFIG_PATH, GSON.toJson(diskConfig));
			} else {
				String json = Files.readString(CONFIG_PATH);
				ToolConfigDisk loaded = GSON.fromJson(json, ToolConfigDisk.class);
				if (loaded != null) {
					diskConfig = loaded.withDefaults();
				}
			}
		} catch (IOException | RuntimeException e) {
			CrystalResonance.LOGGER.warn("Failed to load config from {}. Using defaults.", CONFIG_PATH, e);
		}

		return fromDiskModel(diskConfig.withDefaults());
	}

	boolean matchesSelectTool(ItemStack stack) {
		return matches(stack, selectTools);
	}

	boolean matchesLinkTool(ItemStack stack) {
		return matches(stack, linkTools);
	}

	boolean matchesUnlinkTool(ItemStack stack) {
		return matches(stack, unlinkTools);
	}

	ParticleOptions selectParticle() {
		return selectParticle;
	}

	ParticleOptions linkParticle() {
		return linkParticle;
	}

	ParticleOptions unlinkParticle() {
		return unlinkParticle;
	}

	private static boolean matches(ItemStack stack, Set<Item> items) {
		for (Item item : items) {
			if (stack.is(item)) {
				return true;
			}
		}
		return false;
	}

	private static ToolConfig fromDiskModel(ToolConfigDisk diskConfig) {
		return new ToolConfig(
			resolveItems(diskConfig.select_tools, DEFAULT_SELECT_TOOLS, "select_tools"),
			resolveItems(diskConfig.link_tools, DEFAULT_LINK_TOOLS, "link_tools"),
			resolveItems(diskConfig.unlink_tools, DEFAULT_UNLINK_TOOLS, "unlink_tools"),
			resolveParticle(diskConfig.select_particle, DEFAULT_SELECT_PARTICLE, "select_particle"),
			resolveParticle(diskConfig.link_particle, DEFAULT_LINK_PARTICLE, "link_particle"),
			resolveParticle(diskConfig.unlink_particle, DEFAULT_UNLINK_PARTICLE, "unlink_particle")
		);
	}

	private static Set<Item> resolveItems(List<String> ids, List<String> fallbackIds, String fieldName) {
		Set<Item> resolved = new LinkedHashSet<>();
		for (String id : ids) {
			Identifier identifier = Identifier.tryParse(id);
			if (identifier == null) {
				CrystalResonance.LOGGER.warn("Ignoring invalid item id '{}' in {}", id, fieldName);
				continue;
			}

			Item item = BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
			if (item == null) {
				CrystalResonance.LOGGER.warn("Ignoring unknown item id '{}' in {}", id, fieldName);
				continue;
			}

			resolved.add(item);
		}

		if (!resolved.isEmpty()) {
			return resolved;
		}

		Set<Item> fallback = new LinkedHashSet<>();
		for (String id : fallbackIds) {
			Identifier identifier = Identifier.parse(id);
			BuiltInRegistries.ITEM.getOptional(identifier).ifPresent(fallback::add);
		}
		return fallback;
	}

	private static ParticleOptions resolveParticle(String id, String fallbackId, String fieldName) {
		Identifier identifier = Identifier.tryParse(id);
		if (identifier != null) {
			ParticleOptions particle = getSimpleParticle(identifier);
			if (particle != null) {
				return particle;
			}
			CrystalResonance.LOGGER.warn("Ignoring unknown particle id '{}' in {}", id, fieldName);
		} else {
			CrystalResonance.LOGGER.warn("Ignoring invalid particle id '{}' in {}", id, fieldName);
		}

		return getSimpleParticle(Identifier.parse(fallbackId));
	}

	private static ParticleOptions getSimpleParticle(Identifier identifier) {
		ParticleType<?> particleType = BuiltInRegistries.PARTICLE_TYPE.getOptional(identifier).orElse(null);
		if (particleType instanceof SimpleParticleType simpleParticleType) {
			return simpleParticleType;
		}
		return null;
	}
}

final class ToolConfigDisk {
	List<String> select_tools;
	List<String> link_tools;
	List<String> unlink_tools;
	String select_particle;
	String link_particle;
	String unlink_particle;

	ToolConfigDisk(
		List<String> selectTools,
		List<String> linkTools,
		List<String> unlinkTools,
		String selectParticle,
		String linkParticle,
		String unlinkParticle
	) {
		this.select_tools = new ArrayList<>(selectTools);
		this.link_tools = new ArrayList<>(linkTools);
		this.unlink_tools = new ArrayList<>(unlinkTools);
		this.select_particle = selectParticle;
		this.link_particle = linkParticle;
		this.unlink_particle = unlinkParticle;
	}

	ToolConfigDisk withDefaults() {
		return new ToolConfigDisk(
			select_tools == null ? ToolConfig.DEFAULT_SELECT_TOOLS : select_tools,
			link_tools == null ? ToolConfig.DEFAULT_LINK_TOOLS : link_tools,
			unlink_tools == null ? ToolConfig.DEFAULT_UNLINK_TOOLS : unlink_tools,
			select_particle == null ? ToolConfig.DEFAULT_SELECT_PARTICLE : select_particle,
			link_particle == null ? ToolConfig.DEFAULT_LINK_PARTICLE : link_particle,
			unlink_particle == null ? ToolConfig.DEFAULT_UNLINK_PARTICLE : unlink_particle
		);
	}
}

enum Mode {
	ANY_PLAYER,
	OWNER_ONLY
}

record Node(BlockPos pos, UUID owner, BlockPos leverPos, Mode mode, boolean active) {
	static final Codec<Node> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		BlockPos.CODEC.fieldOf("pos").forGetter(Node::pos),
		UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(node -> Optional.ofNullable(node.owner())),
		BlockPos.CODEC.optionalFieldOf("lever").forGetter(node -> Optional.ofNullable(node.leverPos())),
		Codec.STRING.optionalFieldOf("mode").forGetter(node -> Optional.ofNullable(node.mode()).map(Enum::name)),
		Codec.BOOL.optionalFieldOf("active", false).forGetter(Node::active)
	).apply(instance, (pos, owner, leverPos, mode, active) ->
		new Node(pos, owner.orElse(null), leverPos.orElse(null), mode.map(Mode::valueOf).orElse(null), active)
	));
}

record LeverLink(BlockPos leverPos, List<BlockPos> nodes) {
	static final Codec<LeverLink> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		BlockPos.CODEC.fieldOf("lever").forGetter(LeverLink::leverPos),
		BlockPos.CODEC.listOf().optionalFieldOf("nodes", List.of()).forGetter(LeverLink::nodes)
	).apply(instance, LeverLink::new));
}

class NodeState extends SavedData {
	static final Codec<NodeState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Node.CODEC.listOf().optionalFieldOf("nodes", List.of()).forGetter(state -> new ArrayList<>(state.nodes.values())),
		LeverLink.CODEC.listOf().optionalFieldOf("levers", List.of()).forGetter(NodeState::serializeLeverLinks)
	).apply(instance, NodeState::new));

	private static final SavedDataType<NodeState> TYPE =
		new SavedDataType<>("resonance_nodes", NodeState::new, NodeState.CODEC, DataFixTypes.LEVEL);

	private final Map<BlockPos, Node> nodes = new HashMap<>();
	private final Map<BlockPos, Set<BlockPos>> leverToNodes = new HashMap<>();
	private final Map<Long, Set<BlockPos>> nodesByChunk = new HashMap<>();
	private final Map<Long, Set<BlockPos>> leversByChunk = new HashMap<>();

	NodeState() {
	}

	private NodeState(List<Node> savedNodes, List<LeverLink> savedLinks) {
		for (Node node : savedNodes) {
			nodes.put(node.pos(), node);
			indexNode(node.pos());
			if (node.leverPos() != null) {
				indexLever(node.leverPos());
			}
		}

		for (LeverLink link : savedLinks) {
			BlockPos leverPos = link.leverPos();
			Set<BlockPos> linkedNodes = new LinkedHashSet<>(link.nodes());
			if (!linkedNodes.isEmpty()) {
				leverToNodes.put(leverPos, linkedNodes);
				indexLever(leverPos);
			}
		}
	}

	public static NodeState get(ServerLevel world) {
		return world.getDataStorage().computeIfAbsent(TYPE);
	}

	public Node getNode(BlockPos pos) {
		return nodes.get(pos);
	}

	public Node getOrCreateNode(BlockPos pos, UUID owner) {
		Node node = nodes.get(pos);
		if (node == null) {
			node = new Node(pos, owner, null, null, false);
			nodes.put(pos, node);
			indexNode(pos);
			setDirty();
			CrystalResonance.LOGGER.info("Created node {} owned by {}", pos, owner);
		}
		return node;
	}

	public void linkNodes(Set<BlockPos> nodePositions, BlockPos leverPos, Mode mode, UUID owner) {
		indexLever(leverPos);

		for (BlockPos nodePos : nodePositions) {
			Node node = nodes.get(nodePos);
			if (node == null) continue;

			if (node.mode() == Mode.OWNER_ONLY && (node.owner() == null || !node.owner().equals(owner))) {
				continue;
			}

			UUID nodeOwner = node.owner() == null ? owner : node.owner();

			if (node.leverPos() != null && !node.leverPos().equals(leverPos)) {
				BlockPos previousLeverPos = node.leverPos();
				Set<BlockPos> previous = leverToNodes.get(previousLeverPos);
				if (previous != null) {
					previous.remove(nodePos);
					if (previous.isEmpty()) {
						leverToNodes.remove(previousLeverPos);
						unindexLever(previousLeverPos);
					}
				}
			}

			nodes.put(nodePos, new Node(nodePos, nodeOwner, leverPos, mode, node.active()));
			leverToNodes.computeIfAbsent(leverPos, ignored -> new LinkedHashSet<>()).add(nodePos);
			CrystalResonance.LOGGER.info("Node {} linked to lever {} with mode {} and owner {}", nodePos, leverPos, mode, nodeOwner);
		}

		setDirty();
	}

	public boolean unlink(BlockPos nodePos, UUID player) {
		Node node = nodes.get(nodePos);
		if (node == null) return false;

		if (node.mode() == Mode.OWNER_ONLY && (node.owner() == null || !node.owner().equals(player))) {
			return false;
		}

		if (node.leverPos() != null) {
			Set<BlockPos> linked = leverToNodes.get(node.leverPos());
			if (linked != null) {
				linked.remove(nodePos);
				if (linked.isEmpty()) {
					BlockPos leverPos = node.leverPos();
					leverToNodes.remove(leverPos);
					unindexLever(leverPos);
				}
			}
		}

		nodes.put(nodePos, new Node(node.pos(), node.owner(), null, null, false));
		setDirty();
		CrystalResonance.LOGGER.info("Node {} unlinked by {}", nodePos, player);
		return true;
	}

	public boolean matchesNodeTrigger(ServerPlayer player, BlockPos nodePos) {
		// Trigger when the player is standing on top of the node block.
		return player.blockPosition().below().equals(nodePos);
	}

	public void setActive(BlockPos pos, boolean active) {
		Node node = nodes.get(pos);
		if (node == null || node.active() == active) return;
		nodes.put(pos, new Node(node.pos(), node.owner(), node.leverPos(), node.mode(), active));
		setDirty();
		CrystalResonance.LOGGER.info("Node {} active changed: {} -> {}", pos, node.active(), active);
	}

	public Collection<Node> getNodes() {
		return nodes.values();
	}

	public Set<Long> chunkKeys() {
		return nodesByChunk.keySet();
	}

	public Set<BlockPos> getLeversInChunk(long chunkKey) {
		return leversByChunk.get(chunkKey);
	}

	public Set<BlockPos> getLinkedNodes(BlockPos leverPos) {
		return leverToNodes.get(leverPos);
	}

	private void indexNode(BlockPos pos) {
		nodesByChunk.computeIfAbsent(ChunkPos.asLong(pos), ignored -> new LinkedHashSet<>()).add(pos);
	}


	private void indexLever(BlockPos pos) {
		leversByChunk.computeIfAbsent(ChunkPos.asLong(pos), ignored -> new LinkedHashSet<>()).add(pos);
	}

	private void unindexLever(BlockPos pos) {
		long chunkKey = ChunkPos.asLong(pos);
		Set<BlockPos> levers = leversByChunk.get(chunkKey);
		if (levers == null) return;

		levers.remove(pos);
		if (levers.isEmpty()) {
			leversByChunk.remove(chunkKey);
		}
	}

	private List<LeverLink> serializeLeverLinks() {
		List<LeverLink> result = new ArrayList<>();
		for (Map.Entry<BlockPos, Set<BlockPos>> entry : leverToNodes.entrySet()) {
			result.add(new LeverLink(entry.getKey(), new ArrayList<>(entry.getValue())));
		}
		return result;
	}
}
