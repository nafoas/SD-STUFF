package dev.aicompanion.entity;

import dev.aicompanion.CompanionManager;
import dev.aicompanion.ModConfig;
import dev.aicompanion.ai.CompanionBrain;
import dev.aicompanion.ai.PersonaProfile;
import dev.aicompanion.game.tasks.Task;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.state.property.Properties;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.ai.goal.LongDoorInteractGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.item.AxeItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The companion's body. It runs reflexes (fighting, fleeing, eating, picking up items) every tick,
 * carries out whatever task the action layer gave it, and falls back to personality-driven idle behavior.
 */
public class CompanionEntity extends PathAwareEntity {
    private static final TrackedData<String> SKIN = DataTracker.registerData(CompanionEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<Boolean> SLIM = DataTracker.registerData(CompanionEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    public static final double REACH = 4.5;

    private final SimpleInventory inventory = new SimpleInventory(36);
    private String characterId = "";
    private String characterName = "";
    @Nullable private UUID ownerUuid;
    private String ownerName = "";

    @Nullable private Task task;
    @Nullable private CompletableFuture<String> taskFuture;

    // Reflex state
    @Nullable private LivingEntity combatTarget;
    @Nullable private Vec3d fleeTarget;
    private int fleeTicks;
    private int attackCooldown;
    private int lastEatAge;

    @Nullable private LivingEntity loggedCombatTarget;
    @Nullable private java.util.Map<Item, Integer> lastInventory;
    private int lastMobDamageLog = -1000;

    // Block breaking state
    @Nullable private BlockPos breakingPos;
    private float breakProgress;

    public CompanionEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        setPersistent();
        setCanPickUpLoot(false);
        setCustomNameVisible(true);
        if (getNavigation() instanceof MobNavigation nav) {
            nav.setCanPathThroughDoors(true);
            nav.setCanSwim(true);
        }
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        dataTracker.startTracking(SKIN, "");
        dataTracker.startTracking(SLIM, false);
    }

    @Override
    protected void initGoals() {
        goalSelector.add(0, new SwimGoal(this));
        goalSelector.add(1, new LongDoorInteractGoal(this, true));
        goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
        goalSelector.add(9, new LookAroundGoal(this));
    }

    // ---------------------------------------------------------------- identity

    public void setCharacter(String id, String name) {
        characterId = id;
        characterName = name;
        setCustomName(Text.literal(name));
        setSkin(ModConfig.get().skinFor(name), ModConfig.get().slimFor(name));
    }

    public void setSkin(String skin, boolean slim) {
        dataTracker.set(SKIN, skin == null ? "" : skin);
        dataTracker.set(SLIM, slim);
    }

    public String getSkin() {
        return dataTracker.get(SKIN);
    }

    public boolean isSlim() {
        return dataTracker.get(SLIM);
    }

    public String getCharacterId() {
        return characterId;
    }

    public String getCharacterName() {
        return characterName;
    }

    public void setOwner(PlayerEntity player) {
        ownerUuid = player.getUuid();
        ownerName = player.getGameProfile().getName();
    }

    @Nullable
    public ServerPlayerEntity getOwnerPlayer() {
        if (ownerUuid == null || getServer() == null) return null;
        return getServer().getPlayerManager().getPlayer(ownerUuid);
    }

    public String getOwnerName() {
        return ownerName;
    }

    @Nullable
    public CompanionBrain brain() {
        return CompanionManager.brain(characterId);
    }

    public PersonaProfile profile() {
        CompanionBrain brain = brain();
        return brain == null ? PersonaProfile.neutral() : brain.profile();
    }

    public SimpleInventory getInventory() {
        return inventory;
    }

    public ServerWorld serverWorld() {
        return (ServerWorld) getWorld();
    }

    public double workSpeed() {
        return profile().workPace();
    }

    // ---------------------------------------------------------------- tasks

    /** Starts a task, cancelling whatever was running. The future completes with the task's result message. */
    public void startTask(Task newTask, CompletableFuture<String> future) {
        cancelTask("Interrupted by a new action.");
        task = newTask;
        taskFuture = future;
        log("task_start", "Started " + newTask.describe(), false);
        if (newTask.isContinuous()) {
            future.complete("Started: " + newTask.describe() + ". This continues until another action replaces it.");
        }
    }

    public void cancelTask(String reason) {
        if (task != null) {
            task.stop(this);
            if (taskFuture != null) taskFuture.complete("Stopped: " + reason);
        }
        task = null;
        taskFuture = null;
    }

    @Nullable
    public String currentTaskDescription() {
        return task == null ? null : task.describe();
    }

    /** Fighting or running from something right now (reflexes are in charge). */
    public boolean isInCombat() {
        return combatTarget != null || fleeTicks > 0;
    }

    public boolean isBusy() {
        return task != null && !task.isContinuous();
    }

    // ---------------------------------------------------------------- tick

    @Override
    protected void mobTick() {
        super.mobTick();
        if (getWorld().isClient) return;
        if (age % 4 == 0) pickUpNearbyItems();
        if (age % 40 == 0) equipBestArmor();
        if (age % 100 == 0) watchInventory();
        if (age % 80 == 0 && getHealth() < getMaxHealth()) heal(1.0f);
        if (attackCooldown > 0) attackCooldown--;

        if (runReflexes()) return;

        if (task != null) {
            Task.Result result;
            try {
                result = task.tick(this);
            } catch (Exception e) {
                result = new Task.Result(false, "Something went wrong: " + e.getMessage());
            }
            if (result != null) {
                Task finished = task;
                CompletableFuture<String> future = taskFuture;
                task = null;
                taskFuture = null;
                finished.stop(this);
                if (future != null && !future.isDone()) future.complete((result.success() ? "Done: " : "Failed: ") + result.message());
                log("task_end", (result.success() ? "Finished " : "Failed ") + finished.describe() + ": " + result.message(), finished.isMajor());
                CompanionBrain brain = brain();
                if (brain != null && finished.isContinuous()) brain.onBodyEvent((result.success() ? "Finished " : "Gave up on ") + finished.describe() + ": " + result.message(), false);
            }
            return;
        }
        idleBehavior();
    }

    /** Returns true when a reflex took control of the body this tick. */
    private boolean runReflexes() {
        PersonaProfile p = profile();

        // Creepers about to blow up: everyone runs, however brave.
        CreeperEntity creeper = nearest(CreeperEntity.class, 5, c -> c.getFuseSpeed() > 0 || c.isIgnited());
        if (creeper != null) {
            flee(creeper.getPos());
        }

        if (fleeTicks > 0) {
            fleeTicks--;
            if (fleeTarget != null && (getNavigation().isIdle() || fleeTicks % 20 == 0)) {
                getNavigation().startMovingTo(fleeTarget.x, fleeTarget.y, fleeTarget.z, 1.3);
            }
            if (fleeTicks == 0) fleeTarget = null;
            return true;
        }

        if (combatTarget != null && (!combatTarget.isAlive() || combatTarget.squaredDistanceTo(this) > 24 * 24)) {
            log("combat", (combatTarget.isAlive() ? "Lost track of " : "Defeated ") + describeEntity(combatTarget), false);
            combatTarget = null;
            loggedCombatTarget = null;
        }

        // Who is threatening us or our friends?
        LivingEntity attacker = getAttacker();
        if (attacker != null && attacker.isAlive() && age - getLastAttackedTime() < 200 && combatTarget == null) {
            if (attacker instanceof PlayerEntity) {
                if (ModConfig.get().allowPvp && p.bravery() >= 6 && opinionOf(attacker.getName().getString()) < 0) combatTarget = attacker;
            } else if (p.bravery() >= 3) {
                combatTarget = attacker;
            } else {
                flee(attacker.getPos());
                return true;
            }
        }
        if (combatTarget == null && p.defendsFriends()) {
            combatTarget = nearest(HostileEntity.class, 12, h -> h.getTarget() instanceof PlayerEntity pl && opinionOf(pl.getName().getString()) >= 0);
        }
        if (combatTarget == null && p.huntsMonsters() && (task == null || p.bravery() >= 9)) {
            combatTarget = nearest(HostileEntity.class, task == null ? 10 : 6, h -> !(h instanceof CreeperEntity));
        }

        if (combatTarget != null) {
            HostileEntity nearbyHostile = nearest(HostileEntity.class, 10, h -> true);
            if (getHealth() < getMaxHealth() * p.retreatHealthFraction() && nearbyHostile != null) {
                combatTarget = null;
                flee(nearbyHostile.getPos());
                return true;
            }
            fight(combatTarget);
            return true;
        }

        // Eat when hurt and safe.
        if (getHealth() < getMaxHealth() - 4 && age - lastEatAge > 60) {
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.getStack(i);
                if (stack.isFood() && stack.getItem().getFoodComponent() != null) {
                    heal(stack.getItem().getFoodComponent().getHunger());
                    getWorld().playSound(null, getBlockPos(), SoundEvents.ENTITY_GENERIC_EAT, SoundCategory.NEUTRAL, 1.0f, 1.0f);
                    stack.decrement(1);
                    lastEatAge = age;
                    break;
                }
            }
        }
        return false;
    }

    private void fight(LivingEntity target) {
        if (target != loggedCombatTarget) {
            loggedCombatTarget = target;
            log("combat", "Fighting " + describeEntity(target), false);
        }
        equipBestWeapon();
        getLookControl().lookAt(target, 30, 30);
        if (squaredDistanceTo(target) > 2.4 * 2.4) {
            if (age % 10 == 0 || getNavigation().isIdle()) getNavigation().startMovingTo(target, 1.2);
        } else if (attackCooldown <= 0) {
            swingHand(Hand.MAIN_HAND);
            tryAttack(target);
            attackCooldown = 12;
        }
    }

    public void flee(Vec3d from) {
        if (fleeTicks <= 0) log("combat", "Ran away from danger (health " + Math.round(getHealth()) + "/20)", false);
        Vec3d away = NoPenaltyTargeting.findFrom(this, 16, 7, from);
        fleeTarget = away != null ? away : getPos().add(getPos().subtract(from).normalize().multiply(10));
        fleeTicks = 60;
        combatTarget = null;
        getNavigation().startMovingTo(fleeTarget.x, fleeTarget.y, fleeTarget.z, 1.3);
    }

    private void idleBehavior() {
        PersonaProfile p = profile();
        ServerPlayerEntity owner = getOwnerPlayer();
        if (p.followsOwnerWhenIdle() && owner != null && owner.getWorld() == getWorld() && opinionOf(owner.getName().getString()) > -3) {
            double d = squaredDistanceTo(owner);
            if (d > 6 * 6 && (age % 10 == 0 || getNavigation().isIdle())) getNavigation().startMovingTo(owner, 1.0);
            else if (d < 3 * 3) getNavigation().stop();
            return;
        }
        // On free days even homebodies stroll around, and further.
        boolean freeDay = brain() != null && brain().memory().dayPlan.kind.equals("free");
        int curiosity = freeDay ? Math.min(10, p.curiosity() + 4) : p.curiosity();
        if (getNavigation().isIdle() && curiosity >= 5 && random.nextInt(Math.max(40, 600 - curiosity * 50)) == 0) {
            Vec3d spot = NoPenaltyTargeting.find(this, 4 + curiosity * 2, 4);
            if (spot != null) getNavigation().startMovingTo(spot.x, spot.y, spot.z, 0.8);
        }
    }

    public int opinionOf(String player) {
        CompanionBrain brain = brain();
        return brain == null ? 0 : brain.memory().opinionOf(player);
    }

    @Nullable
    private <T extends LivingEntity> T nearest(Class<T> type, double radius, java.util.function.Predicate<T> filter) {
        List<T> found = getWorld().getEntitiesByClass(type, getBoundingBox().expand(radius), e -> e != this && e.isAlive() && filter.test(e));
        return found.stream().min(Comparator.comparingDouble(this::squaredDistanceTo)).orElse(null);
    }

    // ---------------------------------------------------------------- items

    private void watchInventory() {
        java.util.Map<Item, Integer> now = new java.util.HashMap<>();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack st = inventory.getStack(i);
            if (!st.isEmpty()) now.merge(st.getItem(), st.getCount(), Integer::sum);
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack st = getEquippedStack(slot);
            if (!st.isEmpty()) now.merge(st.getItem(), st.getCount(), Integer::sum);
        }
        if (lastInventory != null) {
            java.util.List<String> changes = new java.util.ArrayList<>();
            java.util.Set<Item> all = new java.util.HashSet<>(now.keySet());
            all.addAll(lastInventory.keySet());
            for (Item item : all) {
                int d = now.getOrDefault(item, 0) - lastInventory.getOrDefault(item, 0);
                if (d != 0) changes.add((d > 0 ? "+" : "") + d + " " + dev.aicompanion.game.Ids.name(item));
            }
            if (!changes.isEmpty()) log("items", String.join(", ", changes), false);
        }
        lastInventory = now;
    }

    private static String describeEntity(LivingEntity e) {
        if (e instanceof PlayerEntity p) return p.getName().getString();
        if (e instanceof CompanionEntity c) return c.getCharacterName();
        return "a " + e.getType().getName().getString().toLowerCase();
    }

    /** Adds an entry to the companion's activity log (no-op if it has no brain). */
    public void log(String kind, String text, boolean major) {
        CompanionBrain brain = brain();
        if (brain != null) brain.log(kind, text, major);
    }

    private void pickUpNearbyItems() {
        Box box = getBoundingBox().expand(1.5, 0.5, 1.5);
        for (ItemEntity item : getWorld().getEntitiesByClass(ItemEntity.class, box, e -> e.isAlive() && !e.cannotPickup())) {
            ItemStack stack = item.getStack();
            int before = stack.getCount();
            ItemStack remainder = inventory.addStack(stack.copy());
            int taken = before - remainder.getCount();
            if (taken <= 0) continue;
            sendPickup(item, taken);
            if (item.getOwner() instanceof PlayerEntity giver && giver != null) {
                CompanionBrain brain = brain();
                if (brain != null) brain.onGift(giver.getName().getString(), taken + " " + dev.aicompanion.game.Ids.name(stack.getItem()));
            }
            if (remainder.isEmpty()) item.discard();
            else item.setStack(remainder);
        }
    }

    public int count(Item item) {
        int total = inventory.count(item);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = getEquippedStack(slot);
            if (s.isOf(item)) total += s.getCount();
        }
        return total;
    }

    /** Removes up to amount of an item from the inventory and hands. Returns how many were removed. */
    public int remove(Item item, int amount) {
        int left = amount;
        for (int i = 0; i < inventory.size() && left > 0; i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isOf(item)) {
                int take = Math.min(left, s.getCount());
                s.decrement(take);
                left -= take;
            }
        }
        for (Hand hand : Hand.values()) {
            ItemStack s = getStackInHand(hand);
            if (left > 0 && s.isOf(item)) {
                int take = Math.min(left, s.getCount());
                s.decrement(take);
                left -= take;
            }
        }
        return amount - left;
    }

    /** Adds items to the inventory, dropping whatever does not fit. */
    public void give(ItemStack stack) {
        ItemStack remainder = inventory.addStack(stack);
        if (!remainder.isEmpty()) dropStack(remainder);
    }

    /** Moves the best matching stack into the main hand. */
    public void hold(ItemStack wanted) {
        ItemStack current = getMainHandStack();
        if (ItemStack.areEqual(current, wanted)) return;
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i) == wanted) {
                inventory.setStack(i, current.isEmpty() ? ItemStack.EMPTY : current);
                equipStack(EquipmentSlot.MAINHAND, wanted);
                return;
            }
        }
    }

    public void equipBestToolFor(BlockState state) {
        ItemStack best = getMainHandStack();
        float bestSpeed = best.getMiningSpeedMultiplier(state) + (canHarvestWith(best, state) ? 100 : 0);
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty()) continue;
            float speed = s.getMiningSpeedMultiplier(state) + (canHarvestWith(s, state) ? 100 : 0);
            if (speed > bestSpeed) {
                best = s;
                bestSpeed = speed;
            }
        }
        hold(best);
    }

    public void equipBestWeapon() {
        ItemStack best = getMainHandStack();
        double bestDamage = weaponDamage(best);
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            double dmg = weaponDamage(s);
            if (dmg > bestDamage) {
                best = s;
                bestDamage = dmg;
            }
        }
        hold(best);
    }

    private static double weaponDamage(ItemStack s) {
        if (s.getItem() instanceof SwordItem sword) return sword.getAttackDamage() + 1;
        if (s.getItem() instanceof AxeItem axe) return axe.getAttackDamage();
        return 0;
    }

    private void equipBestArmor() {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.getStack(i);
            if (!(s.getItem() instanceof ArmorItem armor)) continue;
            EquipmentSlot slot = armor.getSlotType();
            ItemStack worn = getEquippedStack(slot);
            int wornProtection = worn.getItem() instanceof ArmorItem w ? w.getProtection() : -1;
            if (armor.getProtection() > wornProtection) {
                inventory.setStack(i, worn);
                equipStack(slot, s);
            }
        }
    }

    public static boolean canHarvestWith(ItemStack tool, BlockState state) {
        return !state.isToolRequired() || tool.isSuitableFor(state);
    }

    // ---------------------------------------------------------------- blocks

    public boolean canReach(BlockPos pos) {
        return getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= REACH * REACH;
    }

    /**
     * Mines the block at pos a little more. Returns true once it is broken (drops land nearby and get picked up).
     * Uses the same timing as a player with the held tool.
     */
    public boolean mineStep(BlockPos pos) {
        BlockState state = getWorld().getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty() && state.getBlock() instanceof net.minecraft.block.FluidBlock) {
            stopBreaking();
            return true;
        }
        if (!pos.equals(breakingPos)) {
            stopBreaking();
            breakingPos = pos;
            breakProgress = 0;
            equipBestToolFor(state);
        }
        getLookControl().lookAt(Vec3d.ofCenter(pos));
        float hardness = state.getHardness(getWorld(), pos);
        if (hardness < 0) return false; // unbreakable
        ItemStack tool = getMainHandStack();
        float speed = Math.max(1.0f, tool.getMiningSpeedMultiplier(state));
        float delta = hardness == 0 ? 1.0f : speed / hardness / (canHarvestWith(tool, state) ? 30f : 100f);
        breakProgress += delta;
        if (age % 5 == 0) {
            swingHand(Hand.MAIN_HAND);
            getWorld().playSound(null, pos, state.getSoundGroup().getHitSound(), SoundCategory.BLOCKS, 0.25f, 0.5f);
        }
        getWorld().setBlockBreakingInfo(getId(), pos, Math.min(9, (int) (breakProgress * 10)));
        if (breakProgress >= 1.0f) {
            ServerWorld world = serverWorld();
            if (canHarvestWith(tool, state)) {
                // Drops go straight into the bag, as if picked up immediately.
                for (ItemStack drop : net.minecraft.block.Block.getDroppedStacks(state, world, pos, world.getBlockEntity(pos), this, tool)) {
                    give(drop);
                }
            }
            world.breakBlock(pos, false, this);
            dev.aicompanion.world.BlockOwnership.get(world).clear(pos);
            dev.aicompanion.world.BuildAwareness.invalidate(pos);
            if (tool.isDamageable()) tool.damage(1, this, e -> e.sendEquipmentBreakStatus(EquipmentSlot.MAINHAND));
            stopBreaking();
            return true;
        }
        return false;
    }

    public void stopBreaking() {
        if (breakingPos != null) getWorld().setBlockBreakingInfo(getId(), breakingPos, -1);
        breakingPos = null;
        breakProgress = 0;
    }

    /** Places a block using one matching item from the inventory. Doors and beds get their other half automatically. */
    public boolean placeBlock(BlockPos pos, BlockState state) {
        if (state.contains(Properties.DOUBLE_BLOCK_HALF) && state.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            pos = pos.down();
            state = state.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        }
        if (state.contains(Properties.BED_PART) && state.get(Properties.BED_PART) == BedPart.HEAD) {
            pos = pos.offset(state.get(Properties.HORIZONTAL_FACING).getOpposite());
            state = state.with(Properties.BED_PART, BedPart.FOOT);
        }
        if (getWorld().getBlockState(pos).equals(state)) return true;
        Item item = state.getBlock().asItem();
        if (remove(item, 1) < 1) return false;
        getLookControl().lookAt(Vec3d.ofCenter(pos));
        swingHand(Hand.MAIN_HAND);
        if (state.contains(Properties.DOUBLE_BLOCK_HALF)) {
            getWorld().setBlockState(pos, state, net.minecraft.block.Block.NOTIFY_LISTENERS);
            getWorld().setBlockState(pos.up(), state.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), net.minecraft.block.Block.NOTIFY_ALL);
        } else if (state.contains(Properties.BED_PART)) {
            getWorld().setBlockState(pos, state, net.minecraft.block.Block.NOTIFY_LISTENERS);
            getWorld().setBlockState(pos.offset(state.get(Properties.HORIZONTAL_FACING)), state.with(Properties.BED_PART, BedPart.HEAD), net.minecraft.block.Block.NOTIFY_ALL);
        } else {
            getWorld().setBlockState(pos, state);
        }
        dev.aicompanion.world.BlockOwnership owners = dev.aicompanion.world.BlockOwnership.get(serverWorld());
        String self = dev.aicompanion.world.BlockOwnership.companionOwner(characterId);
        owners.set(pos, self);
        dev.aicompanion.world.BuildAwareness.invalidate(pos);
        if (state.contains(Properties.DOUBLE_BLOCK_HALF)) owners.set(pos.up(), self);
        if (state.contains(Properties.BED_PART)) owners.set(pos.offset(state.get(Properties.HORIZONTAL_FACING)), self);
        getWorld().playSound(null, pos, state.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1.0f, 0.8f);
        return true;
    }

    // ---------------------------------------------------------------- interaction, damage, death

    @Override
    protected ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (getWorld().isClient) return ActionResult.SUCCESS;
        boolean isOwner = player.getUuid().equals(ownerUuid);
        if (isOwner || opinionOf(player.getName().getString()) >= 3 || profile().generosity() >= 8) {
            player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, playerInv, p) -> new GenericContainerScreenHandler(net.minecraft.screen.ScreenHandlerType.GENERIC_9X4, syncId, playerInv, inventory, 4),
                    Text.literal(characterName + "'s bag")));
        } else {
            player.sendMessage(Text.literal(characterName + " won't let you look in their bag."), true);
        }
        return ActionResult.CONSUME;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        boolean hurt = super.damage(source, amount);
        if (hurt && !getWorld().isClient && source.getAttacker() instanceof PlayerEntity player) {
            CompanionBrain brain = brain();
            if (brain != null) brain.onHitByPlayer(player.getName().getString());
        } else if (hurt && !getWorld().isClient && age - lastMobDamageLog > 100) {
            lastMobDamageLog = age;
            String by = source.getAttacker() instanceof LivingEntity le ? describeEntity(le) : source.getName();
            log("damage", "Hurt by " + by + " (health " + Math.round(getHealth()) + "/20)", false);
        }
        return hurt;
    }

    @Override
    public void onDeath(DamageSource source) {
        super.onDeath(source);
        if (getWorld().isClient) return;
        cancelTask("died");
        CompanionBrain brain = brain();
        if (brain != null) brain.onDeath(source.getDeathMessage(this).getString());
    }

    @Override
    protected void dropInventory() {
        super.dropInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack s = inventory.removeStack(i);
            if (!s.isEmpty()) dropStack(s);
        }
    }

    @Override
    protected void dropEquipment(DamageSource source, int lootingMultiplier, boolean allowDrops) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = getEquippedStack(slot);
            if (!s.isEmpty()) {
                dropStack(s);
                equipStack(slot, ItemStack.EMPTY);
            }
        }
    }

    @Override
    public boolean canImmediatelyDespawn(double distanceSquared) {
        return false;
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!getWorld().isClient) cancelTask("removed");
        super.remove(reason);
    }

    // ---------------------------------------------------------------- saving

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putString("CharacterId", characterId);
        nbt.putString("CharacterName", characterName);
        if (ownerUuid != null) nbt.putUuid("Owner", ownerUuid);
        nbt.putString("OwnerName", ownerName);
        nbt.putString("Skin", getSkin());
        nbt.putBoolean("Slim", isSlim());
        DefaultedList<ItemStack> items = DefaultedList.ofSize(inventory.size(), ItemStack.EMPTY);
        for (int i = 0; i < inventory.size(); i++) items.set(i, inventory.getStack(i));
        NbtCompound inv = new NbtCompound();
        Inventories.writeNbt(inv, items);
        nbt.put("Bag", inv);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        characterId = nbt.getString("CharacterId");
        characterName = nbt.getString("CharacterName");
        if (nbt.containsUuid("Owner")) ownerUuid = nbt.getUuid("Owner");
        ownerName = nbt.getString("OwnerName");
        setSkin(nbt.getString("Skin"), nbt.getBoolean("Slim"));
        DefaultedList<ItemStack> items = DefaultedList.ofSize(inventory.size(), ItemStack.EMPTY);
        Inventories.readNbt(nbt.getCompound("Bag"), items);
        for (int i = 0; i < items.size(); i++) inventory.setStack(i, items.get(i));
    }
}
