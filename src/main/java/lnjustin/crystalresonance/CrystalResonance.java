package com.example.resonance;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeverBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.world.World;

import java.util.*;

public class ResonanceNodeSystem {

	/** Multi-selection per player */
	private static final Map<UUID, Set<BlockPos>> pendingNodeSelection = new HashMap<>();

	public enum Mode {
		ANY_PLAYER,
		OWNER_ONLY
	}

	public static class NodeState extends PersistentState {

		public static class Node {
			public BlockPos nodePos;
			public UUID owner;
			public BlockPos leverPos;
			public Mode mode;
			public boolean active;
			public boolean lastActive;

			public Node(BlockPos pos, UUID owner) {
				this.nodePos = pos;
				this.owner = owner;
			}
		}

		private final Map<BlockPos, Node> nodes = new HashMap<>();

		/** Lever -> linked nodes */
		private final Map<BlockPos, Set<BlockPos>> leverToNodes = new HashMap<>();

		public static NodeState get(MinecraftServer server) {
			return server.getOverworld().getPersistentStateManager()
					.getOrCreate(NodeState::fromNbt, NodeState::new, "resonance_nodes");
		}

		public static NodeState fromNbt(NbtCompound nbt) {
			NodeState state = new NodeState();

			// Nodes
			NbtList nodesList = nbt.getList("nodes", 10);
			for (int i = 0; i < nodesList.size(); i++) {
				NbtCompound tag = nodesList.getCompound(i);
				BlockPos pos = BlockPos.fromLong(tag.getLong("nodePos"));
				UUID owner = tag.getUuid("owner");

				Node node = new Node(pos, owner);

				if (tag.contains("leverPos")) {
					node.leverPos = BlockPos.fromLong(tag.getLong("leverPos"));
				}

				if (tag.contains("mode")) {
					node.mode = Mode.valueOf(tag.getString("mode"));
				}

				node.active = false;
				node.lastActive = false;

				state.nodes.put(pos, node);
			}

			// Lever mappings
			NbtList leverList = nbt.getList("leverLinks", 10);
			for (int i = 0; i < leverList.size(); i++) {
				NbtCompound tag = leverList.getCompound(i);
				BlockPos leverPos = BlockPos.fromLong(tag.getLong("leverPos"));

				NbtList nodeList = tag.getList("nodes", 4);
				Set<BlockPos> set = new HashSet<>();

				for (int j = 0; j < nodeList.size(); j++) {
					set.add(BlockPos.fromLong(nodeList.getLong(j)));
				}

				state.leverToNodes.put(leverPos, set);
			}

			return state;
		}

		@Override
		public NbtCompound writeNbt(NbtCompound nbt) {
			// Nodes
			NbtList nodesList = new NbtList();
			for (Node node : nodes.values()) {
				NbtCompound tag = new NbtCompound();
				tag.putLong("nodePos", node.nodePos.asLong());
				tag.putUuid("owner", node.owner);

				if (node.leverPos != null) {
					tag.putLong("leverPos", node.leverPos.asLong());
				}

				if (node.mode != null) {
					tag.putString("mode", node.mode.name());
				}

				nodesList.add(tag);
			}
			nbt.put("nodes", nodesList);

			// Lever mappings
			NbtList leverList = new NbtList();
			for (Map.Entry<BlockPos, Set<BlockPos>> entry : leverToNodes.entrySet()) {
				NbtCompound tag = new NbtCompound();
				tag.putLong("leverPos", entry.getKey().asLong());

				NbtList nodeList = new NbtList();
				for (BlockPos pos : entry.getValue()) {
					nodeList.add(net.minecraft.nbt.NbtLong.of(pos.asLong()));
				}

				tag.put("nodes", nodeList);
				leverList.add(tag);
			}
			nbt.put("leverLinks", leverList);

			return nbt;
		}

		public Node getNode(BlockPos pos) {
			return nodes.get(pos);
		}

		public void addNode(BlockPos pos, UUID owner) {
			nodes.put(pos, new Node(pos, owner));
			markDirty();
		}

		public void linkNodesToLever(Set<BlockPos> nodePositions, BlockPos leverPos, Mode mode, UUID owner) {
			for (BlockPos nodePos : nodePositions) {
				Node node = nodes.get(nodePos);
				if (node == null) continue;

				if (mode == Mode.OWNER_ONLY && !node.owner.equals(owner)) continue;

				node.leverPos = leverPos;
				node.mode = mode;
				node.owner = owner;

				leverToNodes.computeIfAbsent(leverPos, k -> new HashSet<>()).add(nodePos);
			}

			markDirty();
		}

		public void unlinkNode(BlockPos nodePos) {
			Node node = nodes.get(nodePos);
			if (node == null || node.leverPos == null) return;

			BlockPos leverPos = node.leverPos;

			Set<BlockPos> set = leverToNodes.get(leverPos);
			if (set != null) {
				set.remove(nodePos);
				if (set.isEmpty()) {
					leverToNodes.remove(leverPos);
				}
			}

			node.leverPos = null;
			node.mode = null;

			markDirty();
		}

		public Map<BlockPos, Set<BlockPos>> getLeverToNodes() {
			return leverToNodes;
		}

		public Collection<Node> getNodes() {
			return nodes.values();
		}
	}

	public static void init() {

		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (world.isClient()) return ActionResult.PASS;

			BlockPos pos = hit.getBlockPos();
			ItemStack stack = player.getStackInHand(hand);
			NodeState state = NodeState.get(player.getServer());

			BlockState blockState = world.getBlockState(pos);

			pendingNodeSelection.putIfAbsent(player.getUuid(), new HashSet<>());

			// Node selection (Echo or Amethyst)
			if (stack.isOf(Items.ECHO_SHARD) || stack.isOf(Items.AMETHYST_SHARD)) {
				if (state.getNode(pos) != null) {
					pendingNodeSelection.get(player.getUuid()).add(pos);
					spawnHappyParticles(world, pos);
					player.sendMessage(net.minecraft.text.Text.literal("Node selected."), true);
					return ActionResult.SUCCESS;
				}
			}

			// Tuning (linking) to lever
			if (blockState.getBlock() instanceof LeverBlock) {

				Set<BlockPos> selectedNodes = pendingNodeSelection.get(player.getUuid());
				if (selectedNodes == null || selectedNodes.isEmpty()) return ActionResult.PASS;

				Mode mode;

				if (stack.isOf(Items.AMETHYST_SHARD)) {
					mode = Mode.ANY_PLAYER;
				} else if (stack.isOf(Items.ECHO_SHARD)) {
					mode = Mode.OWNER_ONLY;
				} else {
					return ActionResult.PASS;
				}

				UUID playerId = player.getUuid();

				// Ownership enforcement
				for (BlockPos nodePos : selectedNodes) {
					NodeState.Node node = state.getNode(nodePos);
					if (node == null) continue;
					if (node.mode == Mode.OWNER_ONLY && !node.owner.equals(playerId)) {
						player.sendMessage(net.minecraft.text.Text.literal("Not your node."), true);
						return ActionResult.PASS;
					}
				}

				state.linkNodesToLever(selectedNodes, pos, mode, playerId);

				spawnHappyParticles(world, pos);
				player.sendMessage(net.minecraft.text.Text.literal("Nodes tuned to lever."), true);

				selectedNodes.clear();
				return ActionResult.SUCCESS;
			}

			// Detuning (unlink) with quartz
			if (stack.isOf(Items.QUARTZ)) {

				NodeState.Node node = state.getNode(pos);
				if (node == null) return ActionResult.PASS;

				if (node.mode == Mode.OWNER_ONLY && !node.owner.equals(player.getUuid())) {
					player.sendMessage(net.minecraft.text.Text.literal("Not your node."), true);
					return ActionResult.PASS;
				}

				state.unlinkNode(pos);
				spawnSmokeParticles(world, pos);
				player.sendMessage(net.minecraft.text.Text.literal("Node detuned."), true);

				return ActionResult.SUCCESS;
			}

			return ActionResult.PASS;
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			NodeState state = NodeState.get(server);
			World world = server.getOverworld();

			// Update node activity
			for (NodeState.Node node : state.getNodes()) {

				if (!world.isChunkLoaded(node.nodePos)) continue;

				boolean playerNearby = !world.getPlayers(p ->
						p.squaredDistanceTo(node.nodePos.getX(), node.nodePos.getY(), node.nodePos.getZ()) < 16 * 16
				).isEmpty();

				boolean ownerNearby = world.getPlayers(p -> p.getUuid().equals(node.owner))
						.stream()
						.anyMatch(p -> p.squaredDistanceTo(node.nodePos.getX(), node.nodePos.getY(), node.nodePos.getZ()) < 16 * 16);

				if (node.mode == Mode.ANY_PLAYER) {
					node.active = playerNearby;
				} else if (node.mode == Mode.OWNER_ONLY) {
					node.active = ownerNearby;
				}
			}

			// Aggregate lever logic
			for (Map.Entry<BlockPos, Set<BlockPos>> entry : state.getLeverToNodes().entrySet()) {
				BlockPos leverPos = entry.getKey();
				Set<BlockPos> linkedNodes = entry.getValue();

				if (!world.isChunkLoaded(leverPos)) continue;

				boolean anyActive = false;

				for (BlockPos nodePos : linkedNodes) {
					NodeState.Node node = state.getNode(nodePos);
					if (node != null && node.active) {
						anyActive = true;
						break;
					}
				}

				BlockState leverState = world.getBlockState(leverPos);

				if (leverState.getBlock() instanceof LeverBlock) {
					world.setBlockState(
							leverPos,
							leverState.with(LeverBlock.POWERED, anyActive),
							3
					);
				}
			}
		});
	}

	private static void spawnHappyParticles(World world, BlockPos pos) {
		world.addParticle(ParticleTypes.HAPPY_VILLAGER,
				pos.getX() + 0.5,
				pos.getY() + 1.0,
				pos.getZ() + 0.5,
				0, 0.1, 0);
	}

	private static void spawnSmokeParticles(World world, BlockPos pos) {
		world.addParticle(ParticleTypes.SMOKE,
				pos.getX() + 0.5,
				pos.getY() + 1.0,
				pos.getZ() + 0.5,
				0, 0.05, 0);
	}
}
