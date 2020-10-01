package com.minecolonies.coremod.colony.crafting;

import com.google.common.reflect.TypeToken;
import com.minecolonies.api.colony.requestsystem.StandardFactoryController;
import com.minecolonies.api.colony.requestsystem.factory.IFactoryController;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.crafting.IRecipeStorageFactory;
import com.minecolonies.api.crafting.RecipeStorage;
import com.minecolonies.api.crafting.RecipeStorage.RecipeStorageType;
import com.minecolonies.api.util.constant.TypeConstants;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static com.minecolonies.api.util.constant.NbtTagConstants.TAG_TOKEN;

/**
 * Factory implementation taking care of creating new instances, serializing and deserializing RecipeStorages.
 */
public class RecipeStorageFactory implements IRecipeStorageFactory
{
    /**
     * Compound tag for the grid size.
     */
    private static final String TAG_GRID = "grid";

    /**
     * Tag to store the blockstate.
     */
    private static final String BLOCK_TAG = "block";

    /**
     * Compound tag for the input.
     */
    private static final String INPUT_TAG = "input";

    /**
     * Compound tag for the alternate outputs.
     */
    private static final String ALTOUTPUT_TAG = "altoutput";

    /**
     * Compound tag for Source
     */
    private static final String SOURCE_TAG = "source";

    /**
     * Compound tag for Type
     */
    private static final String TYPE_TAG = "type";

    @NotNull
    @Override
    public TypeToken<RecipeStorage> getFactoryOutputType()
    {
        return TypeConstants.RECIPE;
    }

    @NotNull
    @Override
    public TypeToken<? extends IToken<?>> getFactoryInputType()
    {
        return TypeConstants.ITOKEN;
    }

    @NotNull
    @Override
    public RecipeStorage getNewInstance(
      @NotNull final IToken<?> token,
      @NotNull final List<ItemStack> input,
      final int gridSize,
      @NotNull final ItemStack primaryOutput,
      final Block intermediate, 
      final String source,
      final RecipeStorageType type,
      final List<ItemStack> altOutputs)
    {
        return new RecipeStorage(token, input, gridSize, primaryOutput, intermediate, source, type, altOutputs);
    }

    @NotNull
    @Override
    public CompoundNBT serialize(@NotNull final IFactoryController controller, @NotNull final RecipeStorage recipeStorage)
    {
        final CompoundNBT compound = new CompoundNBT();
        @NotNull final ListNBT inputTagList = new ListNBT();
        for (@NotNull final ItemStack stack : recipeStorage.getInput())
        {
            @NotNull final CompoundNBT neededRes = new CompoundNBT();
            stack.write(neededRes);
            inputTagList.add(neededRes);
        }
        compound.put(INPUT_TAG, inputTagList);
        recipeStorage.getPrimaryOutput().write(compound);

        if (recipeStorage.getIntermediate() != null)
        {
            compound.put(BLOCK_TAG, NBTUtil.writeBlockState(recipeStorage.getIntermediate().getDefaultState()));
        }
        compound.putInt(TAG_GRID, recipeStorage.getGridSize());
        compound.put(TAG_TOKEN, StandardFactoryController.getInstance().serialize(recipeStorage.getToken()));
        if(recipeStorage.getRecipeSource() != null)
        {
            compound.putString(SOURCE_TAG, recipeStorage.getRecipeSource());
        }
        compound.putString(TYPE_TAG, recipeStorage.getRecipeType().toString());
        return compound;
    }

    @NotNull
    @Override
    public RecipeStorage deserialize(@NotNull final IFactoryController controller, @NotNull final CompoundNBT nbt)
    {
        final List<ItemStack> input = new ArrayList<>();
        final ListNBT inputTagList = nbt.getList(INPUT_TAG, Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < inputTagList.size(); ++i)
        {
            final CompoundNBT inputTag = inputTagList.getCompound(i);
            input.add(ItemStack.read(inputTag));
        }

        final ItemStack primaryOutput = ItemStack.read(nbt);
        final Block intermediate = NBTUtil.readBlockState(nbt.getCompound(BLOCK_TAG)).getBlock();
        final int gridSize = nbt.getInt(TAG_GRID);
        final IToken<?> token = StandardFactoryController.getInstance().deserialize(nbt.getCompound(TAG_TOKEN));

        final String source = nbt.contains(SOURCE_TAG) ? nbt.getString(SOURCE_TAG) : null; 

        final RecipeStorageType type = nbt.contains(TYPE_TAG) ? Enum.valueOf(RecipeStorageType.class, nbt.getString(TYPE_TAG)) : null; 

        final ListNBT altOutputTagList = nbt.getList(ALTOUTPUT_TAG, Constants.NBT.TAG_COMPOUND);

        final List<ItemStack> altOutputs = new ArrayList<>();
        for (int i = 0; i < altOutputTagList.size(); ++i)
        {
            final CompoundNBT altOutputTag = altOutputTagList.getCompound(i);
            altOutputs.add(ItemStack.read(altOutputTag));
        }

        return this.getNewInstance(token, input, gridSize, primaryOutput, intermediate, source, type, altOutputs.isEmpty() ? null : altOutputs);
    }

    @Override
    public void serialize(IFactoryController controller, RecipeStorage input, PacketBuffer packetBuffer)
    {
        packetBuffer.writeInt(input.getInput().size());
        input.getInput().forEach(stack -> packetBuffer.writeItemStack(stack));
        packetBuffer.writeItemStack(input.getPrimaryOutput());

        packetBuffer.writeBoolean(input.getIntermediate() != null);
        if (input.getIntermediate() != null)
        {
            packetBuffer.writeInt(Block.getStateId(input.getIntermediate().getDefaultState()));
        }

        packetBuffer.writeInt(input.getGridSize());

        packetBuffer.writeEnumValue(input.getRecipeType());

        packetBuffer.writeInt(input.getAlternateOutputs().size());
        input.getAlternateOutputs().forEach(stack -> packetBuffer.writeItemStack(stack));

        controller.serialize(packetBuffer, input.getToken());
    }

    @Override
    public RecipeStorage deserialize(IFactoryController controller, PacketBuffer buffer) throws Throwable
    {
        final List<ItemStack> input = new ArrayList<>();
        final int inputSize = buffer.readInt();
        for (int i = 0; i < inputSize; ++i)
        {
            input.add(buffer.readItemStack());
        }

        final ItemStack primaryOutput = buffer.readItemStack();
        final Block intermediate = buffer.readBoolean() ? Block.getStateById(buffer.readInt()).getBlock() : null;
        final int gridSize = buffer.readInt();
        final RecipeStorageType type = buffer.readEnumValue(RecipeStorageType.class);

        final List<ItemStack> altOutputs = new ArrayList<>();
        final int altOutputSize = buffer.readInt();
        for (int i = 0; i < altOutputSize; ++i)
        {
            altOutputs.add(buffer.readItemStack());
        }


        final IToken<?> token = controller.deserialize(buffer);
        return this.getNewInstance(token, input, gridSize, primaryOutput, intermediate, null, type, altOutputs.isEmpty() ? null : altOutputs);
    }

    @Override
    public short getSerializationId()
    {
        return 26;
    }
}
