package lnjustin.crystalresonance;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

public class CrystalResonance implements ModInitializer {
	static final Logger LOGGER = LoggerFactory.getLogger("crystal-resonance");
	private static final int CHUNKS_PER_TICK = 4;
	private static final Map<UUID, Map<BlockPos, String>> pendingNodeSelection = new HashMap<>();
	private static ToolConfig toolConfig = ToolConfig.defaults();
	private static int tickCounter = 0;

	@Override
	public void onInitialize() {
		toolConfig = ToolConfig.load();

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
			pendingNodeSelection.remove(handler.player.getUUID())
		);

		registerCommands();
		registerUseBlock();
		registerServerTick();
	}

	private static void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(
				Commands.literal("resonance")
					.then(Commands.literal("members")
						.then(Commands.literal("list")
							.executes(context -> listMembers(context.getSource())))
						.then(Commands.literal("add")
							.then(Commands.argument("player", EntityArgument.player())
								.executes(context -> addMember(
									context.getSource(),
									EntityArgument.getPlayer(context, "player")
								))))
						.then(Commands.literal("remove")
							.then(Commands.argument("player", EntityArgument.player())
								.executes(context -> removeMember(
									context.getSource(),
									EntityArgument.getPlayer(context, "player")
								))))
						.then(Commands.literal("clear")
							.executes(context -> clearMembers(context.getSource()))))
			);
		});
	}

	private static void registerUseBlock() {
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (world.isClientSide()) {
				return InteractionResult.PASS;
			}

			ServerLevel serverWorld = (ServerLevel) world;
			ItemStack stack = player.getItemInHand(hand);
			BlockPos pos = hit.getBlockPos();
			NodeState state = NodeState.get(serverWorld);
			BlockState blockState = serverWorld.getBlockState(pos);

			pendingNodeSelection.putIfAbsent(player.getUUID(), new HashMap<>());

			if (blockState.getBlock() instanceof LeverBlock) {
				Map<BlockPos, String> selected = pendingNodeSelection.get(player.getUUID());
				if (selected == null || selected.isEmpty()) {
					return InteractionResult.PASS;
				}

				Mode mode;
				if (toolConfig.matchesAnyPlayerTool(stack)) {
					mode = Mode.ANY_PLAYER;
				} else if (toolConfig.matchesOwnerOnlyTool(stack)) {
					mode = Mode.OWNER_ONLY;
				} else {
					return InteractionResult.PASS;
				}

				LinkAttempt attempt = state.linkNodes(selected.keySet(), pos, mode, player.getUUID(), stack, serverWorld, toolConfig);
				selected.clear();

				if (attempt.linkedCount() <= 0) {
					player.displayClientMessage(Component.literal(
						attempt.excludedCount() > 0
							? "No nodes linked. Excluded blocks were skipped."
							: "No nodes linked."
					), true);
					return InteractionResult.SUCCESS;
				}

				consumeTool(player, stack);
				String message = "Linked " + attempt.linkedCount() + " node(s) to lever.";
				if (attempt.excludedCount() > 0) {
					message += " Skipped " + attempt.excludedCount() + " excluded block(s).";
				}
				player.displayClientMessage(Component.literal(message), true);
				serverWorld.sendParticles(toolConfig.linkParticle(), pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 1, 0.0, 0.05, 0.0, 0.0);
				return InteractionResult.SUCCESS;
			}

			if (toolConfig.matchesOwnerOnlyTool(stack) || toolConfig.matchesAnyPlayerTool(stack)) {
				if (toolConfig.isExcluded(blockState)) {
					player.displayClientMessage(Component.literal(
						"That block type cannot be used as a resonance node."
					), true);
					return InteractionResult.PASS;
				}

				state.getOrCreateNode(pos, player.getUUID());
				String selectToolId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
				pendingNodeSelection.get(player.getUUID()).put(pos, selectToolId);
				consumeTool(player, stack);
				serverWorld.sendParticles(toolConfig.selectParticle(), pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 1, 0.0, 0.05, 0.0, 0.0);
				return InteractionResult.SUCCESS;
			}

			if (toolConfig.matchesUnlinkTool(stack)) {
				Map<BlockPos, String> selected = pendingNodeSelection.get(player.getUUID());
				if (selected != null && selected.containsKey(pos)) {
					String selectToolId = selected.remove(pos);
					ItemStack refundStack = createSelectRefundStack(selectToolId, 1);
					if (!refundStack.isEmpty()) {
						if (!player.getInventory().add(refundStack)) {
							player.drop(refundStack, false);
						}
					}
					player.displayClientMessage(Component.literal("Selection removed."), true);
					serverWorld.sendParticles(toolConfig.unlinkParticle(), pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 1, 0.0, 0.02, 0.0, 0.0);
					return InteractionResult.SUCCESS;
				}
				UnlinkResult unlinkResult = state.unlink(pos, player.getUUID());
				if (unlinkResult.success()) {
					consumeTool(player, stack);
					refundLinkedTool(player, unlinkResult.refundStack());
				}
				player.displayClientMessage(Component.literal(unlinkResult.success() ? "Node unlinked." : "Nothing to unlink."), true);
				serverWorld.sendParticles(toolConfig.unlinkParticle(), pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 1, 0.0, 0.02, 0.0, 0.0);
				return InteractionResult.SUCCESS;
			}

			return InteractionResult.PASS;
		});
	}

	private static void registerServerTick() {
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
					if (!world.hasChunk(cx, cz)) {
						continue;
					}

					Set<BlockPos> levers = state.getLeversInChunk(key);
					if (levers == null) {
						continue;
					}

					for (BlockPos leverPos : levers) {
						if (!world.hasChunkAt(leverPos)) {
							continue;
						}

						Set<BlockPos> linked = state.getLinkedNodes(leverPos);
						if (linked == null || linked.isEmpty()) {
							continue;
						}

						BlockState leverState = world.getBlockState(leverPos);
						if (!(leverState.getBlock() instanceof LeverBlock) || !leverState.hasProperty(LeverBlock.POWERED)) {
							state.removeOrphanedLever(world, leverPos);
							continue;
						}

						boolean anyActive = false;
						for (BlockPos nodePos : linked) {
							if (state.isNodeActiveForLever(world, nodePos, leverPos)) {
								anyActive = true;
								break;
							}
						}

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

			if (world.getGameTime() % 10 != 0) {
				return;
			}

			for (ServerPlayer player : world.players()) {
				ItemStack heldStack = player.getMainHandItem();
				if (!toolConfig.matchesVisibilityTool(heldStack)) {
					continue;
				}

				Node standingNode = state.getNode(player.blockPosition().below());
				if (standingNode == null || standingNode.leverPositions().isEmpty()) {
					continue;
				}

				Set<BlockPos> linkedNodes = state.getLinkedNodesForNode(standingNode);
				if (linkedNodes.isEmpty()) {
					continue;
				}

				for (BlockPos linkedPos : linkedNodes) {
					if (!world.hasChunkAt(linkedPos)) {
						continue;
					}
					sendVisibilityHighlight(world, player, linkedPos, toolConfig.visibilityParticle());
				}
			}

			for (Map<BlockPos, String> selected : pendingNodeSelection.values()) {
				for (BlockPos pos : selected.keySet()) {
					if (!world.hasChunkAt(pos)) {
						continue;
					}
					world.sendParticles(toolConfig.selectParticle(), pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 1, 0.1, 0.1, 0.1, 0.0);
				}
			}
		});
	}

	private static int listMembers(CommandSourceStack source) {
		OwnedLinkContext context = resolveOwnedLinkContext(source);
		if (context == null) {
			return 0;
		}

		Set<UUID> members = context.state().getNodeMembers(context.node().pos(), context.node().owner());
		if (members.isEmpty()) {
			source.sendSuccess(() -> Component.literal("This owner-only linkage has no extra members."), false);
			return 1;
		}

		List<String> names = new ArrayList<>();
		for (UUID memberId : members) {
			ServerPlayer onlinePlayer = source.getServer().getPlayerList().getPlayer(memberId);
			names.add(onlinePlayer != null ? onlinePlayer.getName().getString() : memberId.toString());
		}

		source.sendSuccess(() -> Component.literal("Members: " + String.join(", ", names)), false);
		return members.size();
	}

	private static int addMember(CommandSourceStack source, ServerPlayer target) {
		OwnedLinkContext context = resolveOwnedLinkContext(source);
		if (context == null) {
			return 0;
		}

		if (target.getUUID().equals(context.node().owner())) {
			source.sendFailure(Component.literal("The owner already has access."));
			return 0;
		}

		boolean changed = context.state().addMemberToNode(context.node().pos(), context.node().owner(), target.getUUID());
		if (!changed) {
			source.sendFailure(Component.literal(target.getName().getString() + " already has access to this linkage."));
			return 0;
		}

		source.sendSuccess(() -> Component.literal("Added " + target.getName().getString() + " to this owner-only linkage."), true);
		return 1;
	}

	private static int removeMember(CommandSourceStack source, ServerPlayer target) {
		OwnedLinkContext context = resolveOwnedLinkContext(source);
		if (context == null) {
			return 0;
		}

		boolean changed = context.state().removeMemberFromNode(context.node().pos(), context.node().owner(), target.getUUID());
		if (!changed) {
			source.sendFailure(Component.literal(target.getName().getString() + " does not have access to this linkage."));
			return 0;
		}

		source.sendSuccess(() -> Component.literal("Removed " + target.getName().getString() + " from this owner-only linkage."), true);
		return 1;
	}

	private static int clearMembers(CommandSourceStack source) {
		OwnedLinkContext context = resolveOwnedLinkContext(source);
		if (context == null) {
			return 0;
		}

		int removed = context.state().clearMembersFromNode(context.node().pos(), context.node().owner());
		if (removed == 0) {
			source.sendFailure(Component.literal("This owner-only linkage has no extra members to clear."));
			return 0;
		}

		source.sendSuccess(() -> Component.literal("Cleared member access from this owner-only linkage."), true);
		return removed;
	}

	private static OwnedLinkContext resolveOwnedLinkContext(CommandSourceStack source) {
		ServerPlayer player;
		try {
			player = source.getPlayerOrException();
		} catch (Exception e) {
			source.sendFailure(Component.literal("Only players can use this command."));
			return null;
		}

		ServerLevel world = (ServerLevel) player.level();
		NodeState state = NodeState.get(world);
		Node node = state.getNode(player.blockPosition().below());

		if (node == null || node.mode() != Mode.OWNER_ONLY || node.leverPositions().isEmpty()) {
			source.sendFailure(Component.literal("Stand on an owner-only resonance node to manage its members."));
			return null;
		}

		if (!player.getUUID().equals(node.owner())) {
			source.sendFailure(Component.literal("Only the owner of this owner-only linkage can manage members."));
			return null;
		}

		return new OwnedLinkContext(state, node);
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

	private static void refundLinkedTool(Player player, ItemStack refundStack) {
		if (refundStack.isEmpty()) {
			return;
		}

		if (!player.getInventory().add(refundStack)) {
			player.drop(refundStack, false);
		}
	}

	private static ItemStack createSelectRefundStack(String toolId, int count) {
		if (toolId == null) {
			return ItemStack.EMPTY;
		}

		Identifier identifier = Identifier.tryParse(toolId);
		if (identifier == null) {
			return ItemStack.EMPTY;
		}

		Item item = BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
		if (item == null) {
			return ItemStack.EMPTY;
		}

		return new ItemStack(item, count);
	}

	private static void sendVisibilityHighlight(ServerLevel world, ServerPlayer player, BlockPos pos, ParticleOptions particle) {
		double minX = pos.getX() + 0.18;
		double maxX = pos.getX() + 0.82;
		double minZ = pos.getZ() + 0.18;
		double maxZ = pos.getZ() + 0.82;
		double lowY = pos.getY() + 0.08;
		double highY = pos.getY() + 1.02;
		double midY = pos.getY() + 0.55;

		sendVisibilityParticle(world, player, particle, minX, lowY, minZ);
		sendVisibilityParticle(world, player, particle, maxX, lowY, minZ);
		sendVisibilityParticle(world, player, particle, minX, lowY, maxZ);
		sendVisibilityParticle(world, player, particle, maxX, lowY, maxZ);
		sendVisibilityParticle(world, player, particle, minX, highY, minZ);
		sendVisibilityParticle(world, player, particle, maxX, highY, minZ);
		sendVisibilityParticle(world, player, particle, minX, highY, maxZ);
		sendVisibilityParticle(world, player, particle, maxX, highY, maxZ);
		sendVisibilityParticle(world, player, particle, pos.getX() + 0.5, highY, pos.getZ() + 0.5);
		sendVisibilityParticle(world, player, particle, pos.getX() + 0.5, midY, minZ);
		sendVisibilityParticle(world, player, particle, pos.getX() + 0.5, midY, maxZ);
		sendVisibilityParticle(world, player, particle, minX, midY, pos.getZ() + 0.5);
		sendVisibilityParticle(world, player, particle, maxX, midY, pos.getZ() + 0.5);
	}

	private static void sendVisibilityParticle(ServerLevel world, ServerPlayer player, ParticleOptions particle, double x, double y, double z) {
		world.sendParticles(player, particle, false, false, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
	}
}

final class ToolConfig {
	static final List<String> DEFAULT_OWNER_ONLY_TOOLS = List.of("minecraft:echo_shard");
	static final List<String> DEFAULT_ANY_PLAYER_TOOLS = List.of("minecraft:amethyst_shard");
	static final List<String> DEFAULT_UNLINK_TOOLS = List.of("minecraft:quartz");
	static final List<String> DEFAULT_EXCLUDED_BLOCKS = List.of(
		"minecraft:chest",
		"minecraft:trapped_chest",
		"minecraft:barrel",
		"minecraft:ender_chest",
		"minecraft:hopper",
		"minecraft:crafting_table",
		"minecraft:furnace",
		"minecraft:blast_furnace",
		"minecraft:smoker",
		"minecraft:smithing_table",
		"minecraft:cartography_table",
		"minecraft:fletching_table",
		"minecraft:loom",
		"minecraft:stonecutter",
		"minecraft:grindstone",
		"minecraft:lectern",
		"minecraft:brewing_stand",
		"minecraft:enchanting_table",
		"minecraft:anvil",
		"minecraft:chipped_anvil",
		"minecraft:damaged_anvil",
		"minecraft:white_bed",
		"minecraft:orange_bed",
		"minecraft:magenta_bed",
		"minecraft:light_blue_bed",
		"minecraft:yellow_bed",
		"minecraft:lime_bed",
		"minecraft:pink_bed",
		"minecraft:gray_bed",
		"minecraft:light_gray_bed",
		"minecraft:cyan_bed",
		"minecraft:purple_bed",
		"minecraft:blue_bed",
		"minecraft:brown_bed",
		"minecraft:green_bed",
		"minecraft:red_bed",
		"minecraft:black_bed",
		"minecraft:shulker_box",
		"minecraft:white_shulker_box",
		"minecraft:orange_shulker_box",
		"minecraft:magenta_shulker_box",
		"minecraft:light_blue_shulker_box",
		"minecraft:yellow_shulker_box",
		"minecraft:lime_shulker_box",
		"minecraft:pink_shulker_box",
		"minecraft:gray_shulker_box",
		"minecraft:light_gray_shulker_box",
		"minecraft:cyan_shulker_box",
		"minecraft:purple_shulker_box",
		"minecraft:blue_shulker_box",
		"minecraft:brown_shulker_box",
		"minecraft:green_shulker_box",
		"minecraft:red_shulker_box",
		"minecraft:black_shulker_box",
		"minecraft:oak_door",
		"minecraft:spruce_door",
		"minecraft:birch_door",
		"minecraft:jungle_door",
		"minecraft:acacia_door",
		"minecraft:dark_oak_door",
		"minecraft:mangrove_door",
		"minecraft:cherry_door",
		"minecraft:bamboo_door",
		"minecraft:crimson_door",
		"minecraft:warped_door",
		"minecraft:iron_door",
		"minecraft:oak_button",
		"minecraft:spruce_button",
		"minecraft:birch_button",
		"minecraft:jungle_button",
		"minecraft:acacia_button",
		"minecraft:dark_oak_button",
		"minecraft:mangrove_button",
		"minecraft:cherry_button",
		"minecraft:bamboo_button",
		"minecraft:crimson_button",
		"minecraft:warped_button",
		"minecraft:stone_button",
		"minecraft:polished_blackstone_button",
		"minecraft:lever",
		"minecraft:dandelion",
		"minecraft:poppy",
		"minecraft:blue_orchid",
		"minecraft:allium",
		"minecraft:azure_bluet",
		"minecraft:red_tulip",
		"minecraft:orange_tulip",
		"minecraft:white_tulip",
		"minecraft:pink_tulip",
		"minecraft:oxeye_daisy",
		"minecraft:cornflower",
		"minecraft:lily_of_the_valley",
		"minecraft:wither_rose",
		"minecraft:sunflower",
		"minecraft:lilac",
		"minecraft:rose_bush",
		"minecraft:peony",
		"minecraft:torchflower",
		"minecraft:redstone_wire",
		"minecraft:redstone_torch",
		"minecraft:redstone_block",
		"minecraft:repeater",
		"minecraft:comparator"
	);
	static final String DEFAULT_SELECT_PARTICLE = "minecraft:end_rod";
	static final String DEFAULT_LINK_PARTICLE = "minecraft:happy_villager";
	static final String DEFAULT_UNLINK_PARTICLE = "minecraft:smoke";
	static final String DEFAULT_VISIBILITY_PARTICLE = "minecraft:composter";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("crystal-resonance.json");

	private final Set<Item> ownerOnlyTools;
	private final Set<Item> anyPlayerTools;
	private final Set<Item> unlinkTools;
	private final Set<Block> excludedBlocks;
	private final ParticleOptions selectParticle;
	private final ParticleOptions linkParticle;
	private final ParticleOptions unlinkParticle;
	private final ParticleOptions visibilityParticle;

	private ToolConfig(
		Set<Item> ownerOnlyTools,
		Set<Item> anyPlayerTools,
		Set<Item> unlinkTools,
		Set<Block> excludedBlocks,
		ParticleOptions selectParticle,
		ParticleOptions linkParticle,
		ParticleOptions unlinkParticle,
		ParticleOptions visibilityParticle
	) {
		this.ownerOnlyTools = ownerOnlyTools;
		this.anyPlayerTools = anyPlayerTools;
		this.unlinkTools = unlinkTools;
		this.excludedBlocks = excludedBlocks;
		this.selectParticle = selectParticle;
		this.linkParticle = linkParticle;
		this.unlinkParticle = unlinkParticle;
		this.visibilityParticle = visibilityParticle;
	}

	static ToolConfig defaults() {
		return fromDiskModel(new ToolConfigDisk(
			DEFAULT_OWNER_ONLY_TOOLS,
			DEFAULT_ANY_PLAYER_TOOLS,
			DEFAULT_UNLINK_TOOLS,
			DEFAULT_EXCLUDED_BLOCKS,
			DEFAULT_SELECT_PARTICLE,
			DEFAULT_LINK_PARTICLE,
			DEFAULT_UNLINK_PARTICLE,
			DEFAULT_VISIBILITY_PARTICLE
		));
	}

	static ToolConfig load() {
		ToolConfigDisk diskConfig = new ToolConfigDisk(
			DEFAULT_OWNER_ONLY_TOOLS,
			DEFAULT_ANY_PLAYER_TOOLS,
			DEFAULT_UNLINK_TOOLS,
			DEFAULT_EXCLUDED_BLOCKS,
			DEFAULT_SELECT_PARTICLE,
			DEFAULT_LINK_PARTICLE,
			DEFAULT_UNLINK_PARTICLE,
			DEFAULT_VISIBILITY_PARTICLE
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
					// Write back if defaults were applied to missing fields
					Files.writeString(CONFIG_PATH, GSON.toJson(diskConfig));
				}
			}
		} catch (IOException | RuntimeException e) {
			CrystalResonance.LOGGER.warn("Failed to load config from {}. Using defaults.", CONFIG_PATH, e);
		}

		return fromDiskModel(diskConfig.withDefaults());
	}

	boolean matchesOwnerOnlyTool(ItemStack stack) {
		return matches(stack, ownerOnlyTools);
	}

	boolean matchesAnyPlayerTool(ItemStack stack) {
		return matches(stack, anyPlayerTools);
	}

	boolean matchesUnlinkTool(ItemStack stack) {
		return matches(stack, unlinkTools);
	}

	boolean matchesVisibilityTool(ItemStack stack) {
		return matchesOwnerOnlyTool(stack) || matchesAnyPlayerTool(stack) || matchesUnlinkTool(stack);
	}

	boolean isExcluded(BlockState blockState) {
		return excludedBlocks.contains(blockState.getBlock());
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

	ParticleOptions visibilityParticle() {
		return visibilityParticle;
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
			resolveItems(diskConfig.owner_only_tools, DEFAULT_OWNER_ONLY_TOOLS, "owner_only_tools"),
			resolveItems(diskConfig.any_player_tools, DEFAULT_ANY_PLAYER_TOOLS, "any_player_tools"),
			resolveItems(diskConfig.unlink_tools, DEFAULT_UNLINK_TOOLS, "unlink_tools"),
			resolveBlocks(diskConfig.excluded_blocks, DEFAULT_EXCLUDED_BLOCKS, "excluded_blocks"),
			resolveParticle(diskConfig.select_particle, DEFAULT_SELECT_PARTICLE, "select_particle"),
			resolveParticle(diskConfig.link_particle, DEFAULT_LINK_PARTICLE, "link_particle"),
			resolveParticle(diskConfig.unlink_particle, DEFAULT_UNLINK_PARTICLE, "unlink_particle"),
			resolveParticle(diskConfig.visibility_particle, DEFAULT_VISIBILITY_PARTICLE, "visibility_particle")
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

	private static Set<Block> resolveBlocks(List<String> ids, List<String> fallbackIds, String fieldName) {
		List<String> effectiveIds = ids == null ? fallbackIds : ids;
		Set<Block> resolved = new LinkedHashSet<>();

		for (String id : effectiveIds) {
			Identifier identifier = Identifier.tryParse(id);
			if (identifier == null) {
				CrystalResonance.LOGGER.warn("Ignoring invalid block id '{}' in {}", id, fieldName);
				continue;
			}

			Block block = BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
			if (block == null) {
				CrystalResonance.LOGGER.warn("Ignoring unknown block id '{}' in {}", id, fieldName);
				continue;
			}

			resolved.add(block);
		}

		return resolved;
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
	List<String> owner_only_tools;
	List<String> any_player_tools;
	List<String> unlink_tools;
	List<String> excluded_blocks;
	String select_particle;
	String link_particle;
	String unlink_particle;
	String visibility_particle;
	List<String> select_tools;
	List<String> link_tools;

	ToolConfigDisk(
		List<String> ownerOnlyTools,
		List<String> anyPlayerTools,
		List<String> unlinkTools,
		List<String> excludedBlocks,
		String selectParticle,
		String linkParticle,
		String unlinkParticle,
		String visibilityParticle
	) {
		this.owner_only_tools = new ArrayList<>(ownerOnlyTools);
		this.any_player_tools = new ArrayList<>(anyPlayerTools);
		this.unlink_tools = new ArrayList<>(unlinkTools);
		this.excluded_blocks = new ArrayList<>(excludedBlocks);
		this.select_particle = selectParticle;
		this.link_particle = linkParticle;
		this.unlink_particle = unlinkParticle;
		this.visibility_particle = visibilityParticle;
	}

	ToolConfigDisk withDefaults() {
		List<String> resolvedOwnerOnlyTools = owner_only_tools != null ? owner_only_tools : select_tools;
		List<String> resolvedAnyPlayerTools = any_player_tools != null ? any_player_tools : link_tools;

		return new ToolConfigDisk(
			resolvedOwnerOnlyTools == null ? ToolConfig.DEFAULT_OWNER_ONLY_TOOLS : resolvedOwnerOnlyTools,
			resolvedAnyPlayerTools == null ? ToolConfig.DEFAULT_ANY_PLAYER_TOOLS : resolvedAnyPlayerTools,
			unlink_tools == null ? ToolConfig.DEFAULT_UNLINK_TOOLS : unlink_tools,
			excluded_blocks == null ? ToolConfig.DEFAULT_EXCLUDED_BLOCKS : excluded_blocks,
			select_particle == null ? ToolConfig.DEFAULT_SELECT_PARTICLE : select_particle,
			link_particle == null ? ToolConfig.DEFAULT_LINK_PARTICLE : link_particle,
			unlink_particle == null ? ToolConfig.DEFAULT_UNLINK_PARTICLE : unlink_particle,
			visibility_particle == null ? ToolConfig.DEFAULT_VISIBILITY_PARTICLE : visibility_particle
		);
	}
}

enum Mode {
	ANY_PLAYER,
	OWNER_ONLY
}

record Node(
	BlockPos pos,
	UUID owner,
	List<BlockPos> leverPositions,
	Mode mode,
	String linkToolId,
	List<UUID> memberUuids
) {
	static final Codec<Node> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		BlockPos.CODEC.fieldOf("pos").forGetter(Node::pos),
		UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(node -> Optional.ofNullable(node.owner())),
		BlockPos.CODEC.listOf().optionalFieldOf("levers", List.of()).forGetter(Node::leverPositions),
		BlockPos.CODEC.optionalFieldOf("lever").forGetter(node -> node.leverPositions().isEmpty() ? Optional.empty() : Optional.of(node.leverPositions().getFirst())),
		Codec.STRING.optionalFieldOf("mode").forGetter(node -> Optional.ofNullable(node.mode()).map(Enum::name)),
		Codec.STRING.optionalFieldOf("link_tool").forGetter(node -> Optional.ofNullable(node.linkToolId())),
		UUIDUtil.CODEC.listOf().optionalFieldOf("members", List.of()).forGetter(Node::memberUuids)
	).apply(instance, (pos, owner, leverPositions, legacyLever, mode, linkToolId, memberUuids) ->
		new Node(
			pos,
			owner.orElse(null),
			normalizeLeverPositions(leverPositions, legacyLever.orElse(null)),
			mode.map(Mode::valueOf).orElse(null),
			linkToolId.orElse(null),
			memberUuids
		)
	));

	Node {
		leverPositions = List.copyOf(new LinkedHashSet<>(leverPositions == null ? List.of() : leverPositions));
		memberUuids = List.copyOf(memberUuids == null ? List.of() : memberUuids);
	}

	boolean allows(UUID playerId) {
		return owner != null && owner.equals(playerId) || memberUuids.contains(playerId);
	}

	boolean hasLever(BlockPos leverPos) {
		return leverPositions.contains(leverPos);
	}

	Node withAddedLever(BlockPos newLeverPos, Mode newMode, UUID newOwner, String newLinkToolId, List<UUID> newMemberUuids) {
		LinkedHashSet<BlockPos> updatedLevers = new LinkedHashSet<>(leverPositions);
		updatedLevers.add(newLeverPos);
		return new Node(pos, newOwner, List.copyOf(updatedLevers), newMode, newLinkToolId, newMemberUuids);
	}

	Node withMembers(List<UUID> newMemberUuids) {
		return new Node(pos, owner, leverPositions, mode, linkToolId, newMemberUuids);
	}

	Node withoutLever(BlockPos leverPos) {
		LinkedHashSet<BlockPos> updatedLevers = new LinkedHashSet<>(leverPositions);
		updatedLevers.remove(leverPos);
		return new Node(pos, owner, List.copyOf(updatedLevers), mode, linkToolId, memberUuids);
	}

	Node unlinked() {
		return new Node(pos, owner, List.of(), null, null, List.of());
	}

	private static List<BlockPos> normalizeLeverPositions(List<BlockPos> leverPositions, BlockPos legacyLever) {
		LinkedHashSet<BlockPos> normalized = new LinkedHashSet<>(leverPositions == null ? List.of() : leverPositions);
		if (legacyLever != null) {
			normalized.add(legacyLever);
		}
		return List.copyOf(normalized);
	}
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
			for (BlockPos leverPos : node.leverPositions()) {
				indexLever(leverPos);
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
			node = new Node(pos, owner, List.of(), null, null, List.of());
			nodes.put(pos, node);
			indexNode(pos);
			setDirty();
			CrystalResonance.LOGGER.info("Created node {} owned by {}", pos, owner);
		}
		return node;
	}

	public LinkAttempt linkNodes(
		Set<BlockPos> nodePositions,
		BlockPos leverPos,
		Mode mode,
		UUID owner,
		ItemStack linkToolStack,
		ServerLevel world,
		ToolConfig toolConfig
	) {
		indexLever(leverPos);
		String linkToolId = BuiltInRegistries.ITEM.getKey(linkToolStack.getItem()).toString();
		int linkedCount = 0;
		int excludedCount = 0;

		for (BlockPos nodePos : nodePositions) {
			if (!world.hasChunkAt(nodePos)) {
				continue;
			}
			if (toolConfig.isExcluded(world.getBlockState(nodePos))) {
				excludedCount++;
				continue;
			}

			Node node = nodes.get(nodePos);
			if (node == null) {
				continue;
			}

			if (node.mode() == Mode.OWNER_ONLY && (node.owner() == null || !node.owner().equals(owner))) {
				continue;
			}

			UUID nodeOwner = node.owner() == null ? owner : node.owner();
			if (node.mode() != null && node.mode() != mode && !node.leverPositions().isEmpty()) {
				continue;
			}
			if (node.owner() != null && mode == Mode.OWNER_ONLY && !node.owner().equals(owner) && !node.leverPositions().isEmpty()) {
				continue;
			}

			List<UUID> memberUuids = mode == Mode.OWNER_ONLY ? node.memberUuids() : List.of();
			if (!node.hasLever(leverPos)) {
				nodes.put(nodePos, node.withAddedLever(leverPos, mode, nodeOwner, linkToolId, memberUuids));
				leverToNodes.computeIfAbsent(leverPos, ignored -> new LinkedHashSet<>()).add(nodePos);
				linkedCount++;
				CrystalResonance.LOGGER.info("Node {} linked to lever {} with mode {} and owner {}", nodePos, leverPos, mode, nodeOwner);
			}
		}

		if (linkedCount > 0) {
			setDirty();
		}

		return new LinkAttempt(linkedCount, excludedCount);
	}

	public UnlinkResult unlink(BlockPos nodePos, UUID player) {
		Node node = nodes.get(nodePos);
		if (node == null || node.leverPositions().isEmpty()) {
			return new UnlinkResult(false, ItemStack.EMPTY);
		}

		if (node.mode() == Mode.OWNER_ONLY && (node.owner() == null || !node.owner().equals(player))) {
			return new UnlinkResult(false, ItemStack.EMPTY);
		}

		ItemStack refundStack = createRefundStack(node.linkToolId(), node.leverPositions().size());
		for (BlockPos leverPos : node.leverPositions()) {
			Set<BlockPos> linked = leverToNodes.get(leverPos);
			if (linked != null) {
				linked.remove(nodePos);
				if (linked.isEmpty()) {
					leverToNodes.remove(leverPos);
					unindexLever(leverPos);
				}
			}
		}

		nodes.put(nodePos, node.unlinked());
		setDirty();
		CrystalResonance.LOGGER.info("Node {} unlinked by {}", nodePos, player);
		return new UnlinkResult(true, refundStack);
	}

	public void removeOrphanedLever(ServerLevel world, BlockPos leverPos) {
		Set<BlockPos> linked = leverToNodes.remove(leverPos);
		if (linked == null || linked.isEmpty()) {
			unindexLever(leverPos);
			return;
		}

		Map<Item, Integer> refundCounts = new HashMap<>();
		for (BlockPos nodePos : linked) {
			Node node = nodes.get(nodePos);
			if (node == null) {
				continue;
			}
			ItemStack refundStack = createRefundStack(node.linkToolId(), 1);
			if (!refundStack.isEmpty()) {
				refundCounts.merge(refundStack.getItem(), refundStack.getCount(), Integer::sum);
			}
			Node updatedNode = node.withoutLever(leverPos);
			nodes.put(nodePos, updatedNode.leverPositions().isEmpty() ? updatedNode.unlinked() : updatedNode);
		}

		dropRefunds(world, leverPos, refundCounts);
		unindexLever(leverPos);
		setDirty();
		CrystalResonance.LOGGER.info("Removed orphaned linkage for broken lever {}", leverPos);
	}

	private void dropRefunds(ServerLevel world, BlockPos pos, Map<Item, Integer> refundCounts) {
		for (Map.Entry<Item, Integer> entry : refundCounts.entrySet()) {
			Item item = entry.getKey();
			int remaining = entry.getValue();

			while (remaining > 0) {
				int stackSize = Math.min(remaining, item.getDefaultInstance().getMaxStackSize());
				ItemStack stack = new ItemStack(item, stackSize);
				ItemEntity drop = new ItemEntity(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
				drop.setDefaultPickUpDelay();
				world.addFreshEntity(drop);
				remaining -= stackSize;
			}
		}
	}

	public boolean matchesNodeTrigger(ServerPlayer player, BlockPos nodePos) {
		return player.blockPosition().below().equals(nodePos);
	}

	public boolean isNodeActiveForLever(ServerLevel world, BlockPos nodePos, BlockPos leverPos) {
		Node node = nodes.get(nodePos);
		if (node == null || !node.hasLever(leverPos) || node.mode() == null) {
			return false;
		}

		for (ServerPlayer player : world.players()) {
			if (!matchesNodeTrigger(player, nodePos)) {
				continue;
			}
			if (node.mode() == Mode.ANY_PLAYER) {
				return true;
			}
			if (node.mode() == Mode.OWNER_ONLY && node.allows(player.getUUID())) {
				return true;
			}
		}

		return false;
	}

	public boolean addMemberToNode(BlockPos nodePos, UUID owner, UUID memberId) {
		Node node = nodes.get(nodePos);
		if (node == null || node.mode() != Mode.OWNER_ONLY || !owner.equals(node.owner())) {
			return false;
		}
		LinkedHashSet<UUID> members = new LinkedHashSet<>(node.memberUuids());
		if (!members.add(memberId)) {
			return false;
		}
		nodes.put(nodePos, node.withMembers(List.copyOf(members)));
		setDirty();
		return true;
	}

	public boolean removeMemberFromNode(BlockPos nodePos, UUID owner, UUID memberId) {
		Node node = nodes.get(nodePos);
		if (node == null || node.mode() != Mode.OWNER_ONLY || !owner.equals(node.owner())) {
			return false;
		}
		LinkedHashSet<UUID> members = new LinkedHashSet<>(node.memberUuids());
		if (!members.remove(memberId)) {
			return false;
		}
		nodes.put(nodePos, node.withMembers(List.copyOf(members)));
		setDirty();
		return true;
	}

	public int clearMembersFromNode(BlockPos nodePos, UUID owner) {
		Node node = nodes.get(nodePos);
		if (node == null || node.mode() != Mode.OWNER_ONLY || !owner.equals(node.owner()) || node.memberUuids().isEmpty()) {
			return 0;
		}
		int removed = node.memberUuids().size();
		nodes.put(nodePos, node.withMembers(List.of()));
		setDirty();
		return removed;
	}

	public Set<UUID> getNodeMembers(BlockPos nodePos, UUID owner) {
		Node node = nodes.get(nodePos);
		if (node == null || node.mode() != Mode.OWNER_ONLY || !owner.equals(node.owner())) {
			return Set.of();
		}
		return new LinkedHashSet<>(node.memberUuids());
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

	public Set<BlockPos> getLinkedNodesForNode(Node node) {
		LinkedHashSet<BlockPos> linkedNodes = new LinkedHashSet<>();
		for (BlockPos leverPos : node.leverPositions()) {
			Set<BlockPos> linked = leverToNodes.get(leverPos);
			if (linked != null) {
				linkedNodes.addAll(linked);
			}
		}
		return linkedNodes;
	}

	private ItemStack createRefundStack(String linkToolId, int count) {
		if (linkToolId == null) {
			return ItemStack.EMPTY;
		}

		Identifier identifier = Identifier.tryParse(linkToolId);
		if (identifier == null) {
			return ItemStack.EMPTY;
		}

		Item item = BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
		if (item == null) {
			return ItemStack.EMPTY;
		}

		return new ItemStack(item, count);
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
		if (levers == null) {
			return;
		}

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

record LinkAttempt(int linkedCount, int excludedCount) {
}

record UnlinkResult(boolean success, ItemStack refundStack) {
}

record OwnedLinkContext(NodeState state, Node node) {
}
