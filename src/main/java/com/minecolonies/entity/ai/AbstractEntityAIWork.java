package com.minecolonies.entity.ai;

import com.minecolonies.colony.buildings.BuildingWorker;
import com.minecolonies.colony.jobs.Job;
import com.minecolonies.entity.ai.state.AIStateBase;
import com.minecolonies.inventory.InventoryCitizen;
import com.minecolonies.util.InventoryFunctions;
import com.minecolonies.util.InventoryUtils;
import com.minecolonies.util.Utils;
import net.minecraft.block.Block;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChunkCoordinates;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import static com.minecolonies.entity.ai.state.AIStateBase.IDLE;
import static com.minecolonies.entity.ai.state.AIStateBase.INIT;
import static com.minecolonies.entity.ai.state.AIStateBase.NEEDS_AXE;
import static com.minecolonies.entity.ai.state.AIStateBase.NEEDS_HOE;
import static com.minecolonies.entity.ai.state.AIStateBase.NEEDS_ITEM;
import static com.minecolonies.entity.ai.state.AIStateBase.NEEDS_PICKAXE;
import static com.minecolonies.entity.ai.state.AIStateBase.NEEDS_ROD;
import static com.minecolonies.entity.ai.state.AIStateBase.NEEDS_SHOVEL;

/**
 * This is the base class of all worker AIs.
 * Every AI implements this class with it's job type.
 * There are some utilities within the class:
 * - The AI will clear a full inventory at the building chest.
 * - The AI will animate mining a block (with delay)
 * - The AI will request items and tools automatically
 * (and collect them from the building chest)
 *
 * @param <J> the job type this AI has to do.
 */
public abstract class AbstractEntityAIWork<J extends Job> extends AbstractAISkeleton<J>
{
    public static final String PICKAXE = "pickaxe";
    public static final String SHOVEL = "shovel";
    public static final String AXE = "axe";
    public static final String HOE = "hoe";
    public static final String ROD = "rod";
    private static final int DEFAULT_RANGE_FOR_DELAY = 3;
    private static final Logger log = Utils.generateLoggerForClass(AbstractEntityAIWork.class);
    private static final int DELAY_RECHECK = 10;

    protected static Random itemRand = new Random();


    /**
     * A list of ItemStacks with needed items and their quantity.
     * This list is a diff between @see #itemsNeeded and
     * the players inventory and their hut combined.
     * So look here for what is currently still needed
     * to fulfill the workers needs.
     * <p>
     * Will be cleared on restart, be aware!
     */
    protected List<ItemStack> itemsCurrentlyNeeded = new ArrayList<>();
    /**
     * The list of all items and their quantity that were requested by the worker.
     * Warning: This list does not change, if you need to see what is currently missing,
     * look at @see #itemsCurrentlyNeeded for things the miner needs.
     * <p>
     * Will be cleared on restart, be aware!
     */
    protected List<ItemStack> itemsNeeded = new ArrayList<>();
    protected boolean needsShovel = false;
    protected boolean needsAxe = false;
    protected boolean needsHoe = false;
    protected boolean needsPickaxe = false;
    protected boolean needsRod = false;

    protected int needsPickaxeLevel = -1;
    private ErrorState errorState = ErrorState.NONE;
    private ChunkCoordinates currentWorkingLocation = null;
    /**
     * The time in ticks until the next action is made
     */
    private int delay = 0;
    private ChunkCoordinates currentStandingLocation = null;


    /**
     * Creates the abstract part of the AI.
     * Always use this constructor!
     *
     * @param job the job to fulfill
     */
    public AbstractEntityAIWork(J job)
    {
        super(job);
        super.registerTargets(
                /**
                 * Init safety checks and transition to IDLE
                 */
                new AITarget(INIT, this::initSafetyChecks),
                /**
                 * Update chestbelt and nametag
                 * Will be executed every time
                 * and does not stop execution
                 */
                new AITarget(this::updateVisualState),
                /**
                 * If waitingForSomething returns true
                 * stop execution to wait for it.
                 * this keeps the current state
                 * (returning null would not stop execution)
                 */
                new AITarget(this::waitingForSomething, () -> state),
                /**
                 * Check if any items are needed.
                 * If yes, transition to NEEDS_ITEM.
                 * and wait for new items.
                 */
                new AITarget(() -> !itemsCurrentlyNeeded.isEmpty(),
                             this::waitForNeededItems),
                /**
                 * Wait for different tools.
                 */
                new AITarget(() -> this.needsShovel, this::waitForShovel),
                new AITarget(() -> this.needsAxe, this::waitForAxe),
                new AITarget(() -> this.needsHoe, this::waitForHoe),
                new AITarget(() -> this.needsRod, this::waitForRod),
                new AITarget(() -> this.needsPickaxe, this::waitForPickaxe)
                             );
    }

    /**
     * Check for null on important variables to prevent crashes.
     *
     * @return IDLE if all ready, else stay in INIT
     */
    private AIStateBase initSafetyChecks()
    {
        //Something fatally wrong? Wait for re-init...
        if (null == getOwnBuilding())
        {
            //TODO: perhaps destroy this task? will see...
            return INIT;
        }
        return IDLE;
    }

    /**
     * Can be overridden in implementations to return the exact building type.
     *
     * @return the building associated with this AI's worker.
     */
    protected BuildingWorker getOwnBuilding()
    {
        return worker.getWorkBuilding();
    }

    /**
     * Updates the visual state of the worker.
     * Updates render meta data.
     * Updates the current state on the nametag.
     *
     * @return null to execute more targets.
     */
    private AIStateBase updateVisualState()
    {
        //Update the current state the worker is in.
        job.setNameTag(this.state.toString());
        //Update torch, seeds etc. in chestbelt etc.
        updateRenderMetaData();
        return null;
    }

    /**
     * Here the AI can check if the chestBelt has to be re rendered and do it.
     */
    protected void updateRenderMetaData()
    {}

    /**
     * Looks for needed items as long as not all of them are there.
     * Also waits for DELAY_RECHECK.
     *
     * @return NEEDS_ITEM
     */
    private AIStateBase waitForNeededItems()
    {
        lookForNeededItems();
        delay = DELAY_RECHECK;
        return NEEDS_ITEM;
    }

    /**
     * Utility method to search for items currently needed.
     * Poll this until all items are there.
     */
    private void lookForNeededItems()
    {
        syncNeededItemsWithInventory();
        if (itemsCurrentlyNeeded.isEmpty())
        {
            itemsNeeded.clear();
            job.clearItemsNeeded();
            return;
        }
        if (worker.isWorkerAtSiteWithMove(getOwnBuilding().getLocation(), DEFAULT_RANGE_FOR_DELAY))
        {
            delay += DELAY_RECHECK;
            ItemStack first = itemsCurrentlyNeeded.get(0);
            //Takes one Stack from the hut if existent
            if (isInHut(first))
            {
                return;
            }
            requestWithoutSpam(first.getDisplayName());
        }
    }

    /**
     * Updates the itemsCurrentlyNeeded with current values.
     */
    private void syncNeededItemsWithInventory()
    {
        job.clearItemsNeeded();
        itemsNeeded.forEach(job::addItemNeeded);
        InventoryUtils.getInventoryAsList(worker.getInventory()).forEach(job::removeItemNeeded);
        itemsCurrentlyNeeded = new ArrayList<>(job.getItemsNeeded());
    }

    /**
     * Finds the first @see ItemStack the type of {@code is}.
     * It will be taken from the chest and placed in the workers inventory.
     * Make sure that the worker stands next the chest to not break immersion.
     * Also make sure to have inventory space for the stack.
     *
     * @param is the type of item requested (amount is ignored)
     * @return true if a stack of that type was found
     */
    private boolean isInHut(final ItemStack is)
    {
        final BuildingWorker buildingMiner = getOwnBuilding();
        return is != null &&
               InventoryFunctions
                       .matchFirstInInventory(
                               buildingMiner.getTileEntity(),
                               (stack) -> stack != null && is.isItemEqual(stack),
                               this::takeItemStackFromChest
                                             );
    }

    /**
     * Request an Item without spamming the chat.
     *
     * @param chat the Item Name
     */
    private void requestWithoutSpam(String chat)
    {
        chatSpamFilter.requestWithoutSpam(chat);
    }

    /**
     * Wait for a needed shovel.
     *
     * @return NEEDS_SHOVEL
     */
    private AIStateBase waitForShovel()
    {
        checkForShovel();
        delay += DELAY_RECHECK;
        return NEEDS_SHOVEL;
    }

    /**
     * Ensures that we have a shovel available.
     * Will set {@code needsShovel} accordingly.
     *
     * @return true if we have a shovel
     */
    protected final boolean checkForShovel()
    {
        needsShovel = checkForTool(SHOVEL);
        return needsShovel;
    }

    private boolean checkForTool(String tool)
    {
        boolean needsTool = InventoryFunctions
                .matchFirstInInventory(
                        worker.getInventory(),
                        stack -> Utils.isTool(stack, tool),
                        InventoryFunctions::doNothing);
        if (!needsTool)
        {
            return false;
        }
        delay += DELAY_RECHECK;
        if (worker.isWorkerAtSiteWithMove(getOwnBuilding().getLocation(), DEFAULT_RANGE_FOR_DELAY))
        {
            if (isToolInHut(tool))
            {
                return false;
            }
            requestWithoutSpam(tool);
        }
        return true;
    }

    private boolean isToolInHut(String tool)
    {
        BuildingWorker buildingMiner = getOwnBuilding();
        return InventoryFunctions
                .matchFirstInInventory(
                        buildingMiner.getTileEntity(),
                        stack -> Utils.isTool(stack, tool),
                        this::takeItemStackFromChest);

    }

    /**
     * Wait for a needed axe.
     *
     * @return NEEDS_AXE
     */
    private AIStateBase waitForAxe()
    {
        checkForAxe();
        delay += DELAY_RECHECK;
        return NEEDS_AXE;
    }

    /**
     * Ensures that we have an axe available.
     * Will set {@code needsAxe} accordingly.
     *
     * @return true if we have an axe
     */
    protected final boolean checkForAxe()
    {
        needsAxe = checkForTool(AXE);
        return needsAxe;
    }

    /**
     * Wait for a needed hoe.
     *
     * @return NEEDS_HOE
     */
    private AIStateBase waitForHoe()
    {
        checkForHoe();
        delay += DELAY_RECHECK;
        return NEEDS_HOE;
    }

    /**
     * Ensures that we have a hoe available.
     * Will set {@code needsHoe} accordingly.
     *
     * @return true if we have a hoe
     */
    protected final boolean checkForHoe()
    {
        needsHoe = checkForTool(HOE);
        return needsHoe;
    }

    /**
     * Wait for a needed rod.
     *
     * @return NEEDS_ROD
     */
    private AIStateBase waitForRod()
    {
        needsRod = checkForRod();
        delay += DELAY_RECHECK;
        return NEEDS_ROD;
    }

    /**
     * Ensures that we have a rod available in the inventory or chest
     * <p>
     * TODO: Rework and split up @marvin
     *
     * @return true if we have a shovel
     */
    protected final boolean checkForRod()
    {
        boolean needsTool = !InventoryFunctions
                .matchFirstInInventory(
                        worker.getInventory(),
                        stack -> (stack != null && stack.getItem().equals(Items.fishing_rod)),
                        InventoryFunctions::doNothing);
        if (!needsTool)
        {
            return false;
        }
        delay += DELAY_RECHECK;
        if (worker.isWorkerAtSiteWithMove(getOwnBuilding().getLocation(), DEFAULT_RANGE_FOR_DELAY))
        {
            if (isRodInHut(new ItemStack(Items.fishing_rod)))
            {
                return false;
            }
            requestWithoutSpam(ROD);
        }
        return true;
    }

    /**
     * Checks if the rod is in the hut
     *
     * @param is is the searched item
     * @return true if he found a rod
     */
    private boolean isRodInHut(ItemStack is)
    {
        BuildingWorker buildingMiner = getOwnBuilding();
        return InventoryFunctions
                .matchFirstInInventory(
                        buildingMiner.getTileEntity(),
                        stack -> stack != null && is.isItemEqual(stack),
                        this::takeItemStackFromChest);

    }

    /**
     * Wait for a needed pickaxe.
     *
     * @return NEEDS_PICKAXE
     */
    private AIStateBase waitForPickaxe()
    {
        checkForPickaxe(needsPickaxeLevel);
        delay += DELAY_RECHECK;
        return NEEDS_PICKAXE;
    }

    /**
     * Ensures that we have a pickaxe available.
     * Will set {@code needsPickaxe} accordingly.
     *
     * @param minlevel the minimum pickaxe level needed.
     * @return true if we have a pickaxe
     */
    protected final boolean checkForPickaxe(int minlevel)
    {
        //Check for a pickaxe
        needsPickaxe = InventoryFunctions
                .matchFirstInInventory(
                        worker.getInventory(),
                        stack -> Utils.checkIfPickaxeQualifies(
                                minlevel, Utils.getMiningLevel(stack, PICKAXE)),
                        InventoryFunctions::doNothing);

        delay += DELAY_RECHECK;
        if (needsPickaxe && walkToBuilding())
        {
            if (isPickaxeInHut(minlevel))
            {
                return true;
            }
            requestWithoutSpam("Pickaxe at least level " + minlevel);
        }
        return needsPickaxe;
    }

    /**
     * Walk the worker to it's building chest.
     * Please return immediately if this returns true.
     *
     * @return false if the worker is at his building
     */
    protected final boolean walkToBuilding()
    {
        return walkToBlock(getOwnBuilding().getLocation());
    }

    /**
     * Sets the block the AI is currently walking to.
     *
     * @param stand where to walk to
     */
    protected final boolean walkToBlock(ChunkCoordinates stand)
    {
        if (!Utils.isWorkerAtSite(worker, stand.posX, stand.posY, stand.posZ, DEFAULT_RANGE_FOR_DELAY))
        {
            workOnBlock(null, stand, 1);
            return true;
        }
        return false;
    }

    /**
     * Sets the block the AI is currently working on.
     * This block will receive animation hits on delay.
     *
     * @param target  the block that will be hit
     * @param stand   the block the worker will walk to
     * @param timeout the time in ticks to hit the block
     */
    protected final void workOnBlock(ChunkCoordinates target, ChunkCoordinates stand, int timeout)
    {
        this.currentWorkingLocation = target;
        this.currentStandingLocation = stand;
        this.delay = timeout;
    }

    /**
     * Looks for a pickaxe to mine a block of {@code minLevel}.
     * The pickaxe will be taken from the chest.
     * Make sure that the worker stands next the chest to not break immersion.
     * Also make sure to have inventory space for the pickaxe.
     *
     * @param minlevel the needed pickaxe level
     * @return true if a pickaxe was found
     */
    private boolean isPickaxeInHut(int minlevel)
    {
        BuildingWorker buildingMiner = getOwnBuilding();
        return InventoryFunctions
                .matchFirstInInventory(
                        buildingMiner.getTileEntity(),
                        stack -> Utils.checkIfPickaxeQualifies(
                                minlevel,
                                Utils.getMiningLevel(
                                        stack,
                                        PICKAXE)),
                        this::takeItemStackFromChest);
    }

    @Override
    public void updateTask()
    {
        super.updateTask();

        //Inventory is full, walk to building and dump inventory
        if (this.errorState == ErrorState.INVENTORY_FULL)
        {
            if (dumpOneMoreSlot())
            {
                delay += DELAY_RECHECK;
                return;
            }
            //We do not need to dump more, use inv check below to resolve condition
        }
        //Check for full inventory
        if (worker.isInventoryFull() || wantInventoryDumped())
        {
            this.errorState = ErrorState.INVENTORY_FULL;
            return;
        }
        this.errorState = ErrorState.NONE;
        workOnTask();
    }

    /**
     * Has to be overridden by classes to specify when to dump inventory.
     * Always dump on inventory full.
     *
     * @return true if inventory needs to be dumped now
     */
    protected boolean wantInventoryDumped()
    {
        return false;
    }

    /**
     * This method will be overridden by AI implementations.
     * It will serve as a tick function.
     */
    protected abstract void workOnTask();

    /**
     * Dump the workers inventory into his building chest.
     * Only useful tools are kept!
     * Only dumps one block at a time!
     */
    private boolean dumpOneMoreSlot()
    {
        return dumpOneMoreSlot(this::neededForWorker);
    }

    /**
     * Dumps one inventory slot into the building chest.
     *
     * @param keepIt used to test it that stack should be kept
     * @return true if is has to dump more.
     */
    private boolean dumpOneMoreSlot(Predicate<ItemStack> keepIt)
    {

        return walkToBuilding()
               || InventoryFunctions.matchFirstInInventory(
                worker.getInventory(), (i, stack) -> {
                    if (stack == null || keepIt.test(stack)){ return false; }
                    ItemStack returnStack = InventoryUtils.setStack(getOwnBuilding().getTileEntity(), stack);
                    if (returnStack == null)
                    {
                        worker.getInventory().decrStackSize(i, stack.stackSize);
                        return true;
                    }
                    worker.getInventory().decrStackSize(
                            i,
                            stack.stackSize
                            - returnStack.stackSize);
                    //Check that we are not inserting
                    // into a
                    // full inventory.
                    return stack.stackSize != returnStack.stackSize;
                });
    }

    /**
     * This method will return true if the AI is waiting for something.
     * In that case, don't execute any more AI code, until it returns false.
     * Call this exactly once per tick to get the delay right.
     * The worker will move and animate correctly while he waits.
     *
     * @return true if we have to wait for something
     * @see #currentStandingLocation @see #currentWorkingLocation
     * @see #DEFAULT_RANGE_FOR_DELAY @see #delay
     */
    private boolean waitingForSomething()
    {
        if (delay > 0)
        {
            if (currentStandingLocation != null &&
                !worker.isWorkerAtSiteWithMove(currentStandingLocation, DEFAULT_RANGE_FOR_DELAY))
            {
                //Don't decrease delay as we are just walking...
                return true;
            }
            worker.hitBlockWithToolInHand(currentWorkingLocation);
            delay--;
            return true;
        }
        clearWorkTarget();
        return false;
    }

    /**
     * Remove the current working block and it's delay.
     */
    protected final void clearWorkTarget()
    {
        this.currentStandingLocation = null;
        this.currentWorkingLocation = null;
        this.delay = 0;
    }

    /**
     * Takes whatever is in that slot of the workers chest and puts it in his inventory.
     * If the inventory is full, only the fitting part will be moved.
     *
     * @param slot the slot in the buildings inventory
     */
    private void takeItemStackFromChest(int slot)
    {
        InventoryUtils.takeStackInSlot(getOwnBuilding().getTileEntity(), worker.getInventory(), slot);
    }

    /**
     * Override this method if you want to keep some items in inventory.
     * When the inventory is full, everything get's dumped into the building chest.
     * But you can use this method to hold some stacks back.
     *
     * @param stack the stack to decide on
     * @return true if the stack should remain in inventory
     */
    protected boolean neededForWorker(ItemStack stack)
    {
        return false;
    }

    protected final void setDelay(int timeout)
    {
        this.delay = timeout;
    }

    protected final boolean holdEfficientTool(Block target)
    {
        int bestSlot = getMostEfficientTool(target);
        if (bestSlot >= 0)
        {
            worker.setHeldItem(bestSlot);
            return true;
        }
        return false;
    }

    protected final int getMostEfficientTool(Block target)
    {
        String tool = target.getHarvestTool(0);
        int required = target.getHarvestLevel(0);
        int bestSlot = -1;
        int bestLevel = Integer.MAX_VALUE;
        InventoryCitizen inventory = worker.getInventory();
        for (int i = 0; i < inventory.getSizeInventory(); i++)
        {
            ItemStack item = inventory.getStackInSlot(i);
            int level = Utils.getMiningLevel(item, tool);
            if (level >= required && level < bestLevel)
            {
                bestSlot = i;
                bestLevel = level;
            }
        }
        return bestSlot;
    }

    /**
     * A displayable status showing why execution is not passed to the AI code.
     * TODO: We have to find a better name than ErrorState as the states
     * are no errors per se but are things to be resolved before
     * AI execution can be resumed.
     */
    private enum ErrorState
    {
        NONE,
        NEEDS_ITEM,
        NEEDS_SHOVEL,
        NEEDS_PICKAXE,
        NEEDS_ROD,
        INVENTORY_FULL,
    }

}
