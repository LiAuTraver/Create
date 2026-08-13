package com.simibubi.create.content.trains.signal;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;

public abstract class SingleBlockEntityEdgePoint extends TrackEdgePoint {

	public ResourceKey<Level> blockEntityDimension;
	public BlockPos blockEntityPos;

	public BlockPos getBlockEntityPos() {
		return blockEntityPos;
	}

	public ResourceKey<Level> getBlockEntityDimension() {
		return blockEntityDimension;
	}

	@Override
	public void blockEntityAdded(BlockEntity blockEntity, boolean front) {
		this.blockEntityPos = blockEntity.getBlockPos();
		this.blockEntityDimension = blockEntity.getLevel()
			.dimension();
	}

	@Override
	public void blockEntityRemoved(BlockPos blockEntityPos, boolean front) {
		removeFromAllGraphs();
	}

	@Override
	public void invalidate(LevelAccessor level) {
		if (blockEntityPos == null) {
			// blockEntityPos is null if blockEntityAdded() was never called.
			// ^^^ fixed(maybe?), but kept the check otherwise NPE.
			Create.LOGGER.warn(
				"SingleBlockEntityEdgePoint.invalidate() called with null blockEntityPos. " +
				"This should never happen and it means the code messed up and the game would probably crash soon"
			);
			return;
		}
		if (blockEntityDimension == null) {
			// blockEntityPos is known but dimension wasn't captured
			// we still need the block entity to re-register itself on the next tick
			// ^^^ fixed(maybe?), keep here as well.
			Create.LOGGER.warn(
				"SingleBlockEntityEdgePoint.invalidate() called with null blockEntityDimension." +
				"This should never happen and it means the code messed up and the game would probably crash soon"
			);
			TrackTargetingBehaviour<?> behaviour =
				BlockEntityBehaviour.get(level, blockEntityPos, TrackTargetingBehaviour.TYPE);
			if (behaviour != null)
				behaviour.invalidateEdgePoint(null);
			return;
		}
		invalidateAt(level, blockEntityPos);
	}

	@Override
	public boolean canMerge() {
		return false;
	}

	@Override
	public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean migration, DimensionPalette dimensions) {
		super.read(nbt, registries, migration, dimensions);
		if (migration)
			return;
		blockEntityPos = NBTHelper.readBlockPos(nbt, "BlockEntityPos");
		blockEntityDimension = dimensions.decode(nbt.contains("BlockEntityDimension") ? nbt.getInt("BlockEntityDimension") : -1);
	}

	@Override
	public void write(CompoundTag nbt, HolderLookup.Provider registries, DimensionPalette dimensions) {
		super.write(nbt, registries, dimensions);
//		assert blockEntityPos != null ;
//		assert blockEntityDimension != null;
		nbt.put("BlockEntityPos", NbtUtils.writeBlockPos(blockEntityPos));
		nbt.putInt("BlockEntityDimension", dimensions.encode(blockEntityDimension));
	}

}
