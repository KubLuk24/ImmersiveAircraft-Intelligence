package immersive_aircraft.entity;

import immersive_aircraft.Items;
import immersive_aircraft.item.upgrade.VehicleStat;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * An intelligent biplane that can fly autonomously to pre-defined destinations.
 * Crafted by combining a biplane with an eye of ender.
 * The user sets destination coordinates and the plane flies itself, avoiding collisions.
 * Once it arrives at the destination, it circles around it.
 */
public class IntelligentBiplaneEntity extends BiplaneEntity {

    // Synched data for autopilot state
    private static final EntityDataAccessor<Boolean> AUTOPILOT_ACTIVE = SynchedEntityData.defineId(IntelligentBiplaneEntity.class, EntityDataSerializers.BOOLEAN);

    // Destination list stored in NBT
    private final List<Destination> destinations = new ArrayList<>();
    private int currentDestinationIndex = -1;

    // Autopilot flight parameters
    private static final double ARRIVAL_DISTANCE = 30.0;
    private static final double CIRCLING_RADIUS = 40.0;
    private static final float CRUISE_ALTITUDE_OFFSET = 20.0f;
    private static final float COLLISION_CHECK_DISTANCE = 10.0f;
    private static final float CLIMB_RATE = 0.6f;
    private static final float DESCENT_RATE = 0.3f;
    private static final float YAW_RATE = 2.0f;
    private static final float CIRCLING_YAW_RATE = 1.5f;

    // Circling state
    private boolean circling = false;
    private float circlingAngle = 0.0f;

    public IntelligentBiplaneEntity(EntityType<? extends AircraftEntity> entityType, Level world) {
        super(entityType, world);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(AUTOPILOT_ACTIVE, false);
    }

    public boolean isAutopilotActive() {
        return entityData.get(AUTOPILOT_ACTIVE);
    }

    public void setAutopilotActive(boolean active) {
        entityData.set(AUTOPILOT_ACTIVE, active);
        if (active && currentDestinationIndex < 0 && !destinations.isEmpty()) {
            currentDestinationIndex = 0;
            circling = false;
        }
    }

    public List<Destination> getDestinations() {
        return destinations;
    }

    public int getCurrentDestinationIndex() {
        return currentDestinationIndex;
    }

    /**
     * Adds a named destination to the list.
     */
    public void addDestination(String name, double x, double y, double z) {
        destinations.add(new Destination(name, x, y, z));
    }

    /**
     * Removes a destination by index.
     */
    public void removeDestination(int index) {
        if (index >= 0 && index < destinations.size()) {
            destinations.remove(index);
            if (currentDestinationIndex >= destinations.size()) {
                currentDestinationIndex = destinations.isEmpty() ? -1 : 0;
            }
        }
    }

    /**
     * Sets the active destination index.
     */
    public void setCurrentDestination(int index) {
        if (index >= 0 && index < destinations.size()) {
            currentDestinationIndex = index;
            circling = false;
        }
    }

    @Override
    public Item asItem() {
        return Items.INTELLIGENT_BIPLANE.get();
    }

    @Override
    public void tick() {
        super.tick();

        // Server-side autopilot logic
        if (!level().isClientSide && isAutopilotActive() && !destinations.isEmpty() && currentDestinationIndex >= 0) {
            tickAutopilot();
        }
    }

    @Override
    protected void updateController() {
        // When autopilot is active (server-side), the AI controls the aircraft
        if (!level().isClientSide && isAutopilotActive() && !destinations.isEmpty() && currentDestinationIndex >= 0) {
            updateAutopilotController();
            return;
        }

        // Normal player control when autopilot is off or no destinations
        super.updateController();
    }

    /**
     * Main autopilot tick - manages engine and high-level state.
     */
    private void tickAutopilot() {
        // Ensure engine is running
        if (getEngineTarget() < 1.0f) {
            setEngineTarget(1.0f);
        }

        Destination dest = destinations.get(currentDestinationIndex);
        double distXZ = getHorizontalDistanceTo(dest.x, dest.z);

        // Check if we've arrived
        if (!circling && distXZ < ARRIVAL_DISTANCE) {
            circling = true;
            circlingAngle = getYRot();
        }
    }

    /**
     * Autopilot controller that replaces player input.
     * Handles steering, pitch, and collision avoidance.
     */
    private void updateAutopilotController() {
        Destination dest = destinations.get(currentDestinationIndex);

        if (circling) {
            updateCircling(dest);
        } else {
            updateFlyToDestination(dest);
        }
    }

    /**
     * Fly toward the destination, adjusting yaw and pitch.
     */
    private void updateFlyToDestination(Destination dest) {
        // Calculate target yaw to destination
        double dx = dest.x - getX();
        double dz = dest.z - getZ();
        float targetYaw = (float) (-Math.toDegrees(Math.atan2(dx, dz)));

        // Steer toward destination
        float yawDiff = normalizeAngle(targetYaw - getYRot());
        float yawAdjust = Math.max(-YAW_RATE, Math.min(YAW_RATE, yawDiff));

        // Apply collision avoidance adjustment
        float avoidanceYaw = getCollisionAvoidanceYaw();
        if (avoidanceYaw != 0) {
            yawAdjust = avoidanceYaw;
        }

        setYRot(getYRot() + yawAdjust);

        // Altitude management
        float targetAltitude = (float) dest.y + CRUISE_ALTITUDE_OFFSET;
        float altDiff = targetAltitude - (float) getY();
        float pitchTarget;
        if (altDiff > 5.0f) {
            pitchTarget = CLIMB_RATE * Math.min(1.0f, altDiff / 20.0f);
        } else if (altDiff < -5.0f) {
            pitchTarget = -DESCENT_RATE * Math.min(1.0f, -altDiff / 20.0f);
        } else {
            pitchTarget = 0;
        }

        // Collision avoidance: climb if obstacle ahead
        if (isObstacleAhead()) {
            pitchTarget = CLIMB_RATE;
        }

        // Smooth pitch toward target
        float currentPitch = getXRot();
        float pitchDiff = pitchTarget * 30.0f - currentPitch; // Scale to degrees
        float pitchAdjust = Math.max(-2.0f, Math.min(2.0f, pitchDiff * 0.1f));
        setXRot(currentPitch + pitchAdjust);

        // Apply thrust
        applyAutopilotThrust();
    }

    /**
     * Circle around the destination point.
     */
    private void updateCircling(Destination dest) {
        // Increment circling angle
        circlingAngle += CIRCLING_YAW_RATE;
        if (circlingAngle > 360.0f) circlingAngle -= 360.0f;

        // Calculate a point on the circle
        double circleX = dest.x + Math.sin(Math.toRadians(circlingAngle)) * CIRCLING_RADIUS;
        double circleZ = dest.z + Math.cos(Math.toRadians(circlingAngle)) * CIRCLING_RADIUS;

        // Steer toward the circle point
        double dx = circleX - getX();
        double dz = circleZ - getZ();
        float targetYaw = (float) (-Math.toDegrees(Math.atan2(dx, dz)));

        float yawDiff = normalizeAngle(targetYaw - getYRot());
        float yawAdjust = Math.max(-YAW_RATE, Math.min(YAW_RATE, yawDiff));

        // Apply collision avoidance
        float avoidanceYaw = getCollisionAvoidanceYaw();
        if (avoidanceYaw != 0) {
            yawAdjust = avoidanceYaw;
        }

        setYRot(getYRot() + yawAdjust);

        // Maintain altitude
        float targetAltitude = (float) dest.y + CRUISE_ALTITUDE_OFFSET;
        float altDiff = targetAltitude - (float) getY();
        float pitchTarget;
        if (altDiff > 3.0f) {
            pitchTarget = CLIMB_RATE * Math.min(1.0f, altDiff / 15.0f);
        } else if (altDiff < -3.0f) {
            pitchTarget = -DESCENT_RATE * Math.min(1.0f, -altDiff / 15.0f);
        } else {
            pitchTarget = 0;
        }

        if (isObstacleAhead()) {
            pitchTarget = CLIMB_RATE;
        }

        float currentPitch = getXRot();
        float pitchDiff = pitchTarget * 30.0f - currentPitch;
        float pitchAdjust = Math.max(-2.0f, Math.min(2.0f, pitchDiff * 0.1f));
        setXRot(currentPitch + pitchAdjust);

        applyAutopilotThrust();
    }

    /**
     * Apply thrust when in autopilot mode (mimics AirplaneEntity.updateController engine behavior).
     */
    private void applyAutopilotThrust() {
        Vector3f direction = getForwardDirection();
        float thrust = (float) (Math.pow(getEnginePower(), 2.0) * getProperties().get(VehicleStat.ENGINE_SPEED));
        setDeltaMovement(getDeltaMovement().add(toVec3d(direction.mul(thrust))));
    }

    /**
     * Check for obstacles ahead using block raycasting and return a yaw adjustment.
     */
    private float getCollisionAvoidanceYaw() {
        Vector3f forward = getForwardDirection();
        Vec3 pos = position();

        // Check ahead
        for (float dist = 2.0f; dist <= COLLISION_CHECK_DISTANCE; dist += 2.0f) {
            double checkX = pos.x + forward.x() * dist;
            double checkY = pos.y + forward.y() * dist;
            double checkZ = pos.z + forward.z() * dist;
            BlockPos blockPos = BlockPos.containing(checkX, checkY, checkZ);

            if (isBlockSolid(blockPos)) {
                // Try to steer right to avoid
                return YAW_RATE * 2.0f;
            }

            // Also check slightly above and below
            if (isBlockSolid(blockPos.above()) || isBlockSolid(blockPos.below())) {
                return YAW_RATE * 1.5f;
            }
        }
        return 0;
    }

    /**
     * Check if there's an obstacle directly ahead (for pitch adjustments).
     */
    private boolean isObstacleAhead() {
        Vector3f forward = getForwardDirection();
        Vec3 pos = position();

        for (float dist = 2.0f; dist <= COLLISION_CHECK_DISTANCE; dist += 2.0f) {
            double checkX = pos.x + forward.x() * dist;
            double checkY = pos.y + forward.y() * dist;
            double checkZ = pos.z + forward.z() * dist;
            BlockPos blockPos = BlockPos.containing(checkX, checkY, checkZ);

            if (isBlockSolid(blockPos) || isBlockSolid(blockPos.above())) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlockSolid(BlockPos pos) {
        BlockState state = level().getBlockState(pos);
        return state.isSolidRender(level(), pos);
    }

    private double getHorizontalDistanceTo(double targetX, double targetZ) {
        double dx = targetX - getX();
        double dz = targetZ - getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private float normalizeAngle(float angle) {
        while (angle > 180.0f) angle -= 360.0f;
        while (angle < -180.0f) angle += 360.0f;
        return angle;
    }

    // --- Player interaction for setting destinations ---

    @Override
    public InteractionResult interact(@NotNull Player player, @NotNull InteractionHand hand) {
        // Only handle intelligent biplane interactions when at full health (let repair take priority)
        if (getHealth() >= 1.0f && player.isSecondaryUseActive() && !hasPassenger(player)) {
            // Sneak + eye of ender adds current player position as destination
            if (player.getItemInHand(hand).getItem() == net.minecraft.world.item.Items.ENDER_EYE) {
                if (!level().isClientSide) {
                    String name = "Destination " + (destinations.size() + 1);
                    BlockPos playerPos = player.blockPosition();
                    addDestination(name, playerPos.getX(), playerPos.getY(), playerPos.getZ());
                    if (currentDestinationIndex < 0) {
                        currentDestinationIndex = 0;
                    }
                    player.displayClientMessage(Component.translatable("immersive_aircraft.intelligent_biplane.destination_added",
                            name, playerPos.getX(), playerPos.getY(), playerPos.getZ()), false);
                    if (!player.getAbilities().instabuild) {
                        player.getItemInHand(hand).shrink(1);
                    }
                }
                return InteractionResult.sidedSuccess(level().isClientSide);
            }

            // Sneak + empty hand toggles autopilot on/off
            if (player.getItemInHand(hand).isEmpty()) {
                if (!level().isClientSide) {
                    if (destinations.isEmpty()) {
                        player.displayClientMessage(Component.translatable("immersive_aircraft.intelligent_biplane.no_destinations"), true);
                    } else {
                        setAutopilotActive(!isAutopilotActive());
                        if (isAutopilotActive()) {
                            Destination dest = currentDestinationIndex >= 0 && currentDestinationIndex < destinations.size()
                                    ? destinations.get(currentDestinationIndex) : destinations.get(0);
                            player.displayClientMessage(Component.translatable("immersive_aircraft.intelligent_biplane.autopilot_on", dest.name), false);
                        } else {
                            setEngineTarget(0.0f);
                            player.displayClientMessage(Component.translatable("immersive_aircraft.intelligent_biplane.autopilot_off"), false);
                        }
                    }
                }
                return InteractionResult.sidedSuccess(level().isClientSide);
            }
        }

        // When mounting and autopilot is active, disable autopilot when player takes control
        if (isAutopilotActive() && !player.isSecondaryUseActive() && !hasPassenger(player)) {
            if (!level().isClientSide) {
                setAutopilotActive(false);
                setEngineTarget(0.0f);
                player.displayClientMessage(Component.translatable("immersive_aircraft.intelligent_biplane.autopilot_off"), true);
            }
        }

        return super.interact(player, hand);
    }

    // --- NBT Save/Load ---

    @Override
    protected void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);

        tag.putBoolean("AutopilotActive", isAutopilotActive());
        tag.putInt("CurrentDestination", currentDestinationIndex);
        tag.putBoolean("Circling", circling);
        tag.putFloat("CirclingAngle", circlingAngle);

        ListTag destList = new ListTag();
        for (Destination dest : destinations) {
            CompoundTag destTag = new CompoundTag();
            destTag.putString("Name", dest.name);
            destTag.putDouble("X", dest.x);
            destTag.putDouble("Y", dest.y);
            destTag.putDouble("Z", dest.z);
            destList.add(destTag);
        }
        tag.put("Destinations", destList);
    }

    @Override
    protected void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        setAutopilotActive(tag.getBoolean("AutopilotActive"));
        currentDestinationIndex = tag.getInt("CurrentDestination");
        circling = tag.getBoolean("Circling");
        circlingAngle = tag.getFloat("CirclingAngle");

        destinations.clear();
        ListTag destList = tag.getList("Destinations", 10);
        for (int i = 0; i < destList.size(); i++) {
            CompoundTag destTag = destList.getCompound(i);
            destinations.add(new Destination(
                    destTag.getString("Name"),
                    destTag.getDouble("X"),
                    destTag.getDouble("Y"),
                    destTag.getDouble("Z")
            ));
        }
    }

    @Override
    protected void addItemTag(@NotNull CompoundTag tag) {
        super.addItemTag(tag);

        ListTag destList = new ListTag();
        for (Destination dest : destinations) {
            CompoundTag destTag = new CompoundTag();
            destTag.putString("Name", dest.name);
            destTag.putDouble("X", dest.x);
            destTag.putDouble("Y", dest.y);
            destTag.putDouble("Z", dest.z);
            destList.add(destTag);
        }
        tag.put("Destinations", destList);
    }

    @Override
    protected void readItemTag(@NotNull CompoundTag tag) {
        super.readItemTag(tag);

        destinations.clear();
        ListTag destList = tag.getList("Destinations", 10);
        for (int i = 0; i < destList.size(); i++) {
            CompoundTag destTag = destList.getCompound(i);
            destinations.add(new Destination(
                    destTag.getString("Name"),
                    destTag.getDouble("X"),
                    destTag.getDouble("Y"),
                    destTag.getDouble("Z")
            ));
        }
        if (!destinations.isEmpty() && currentDestinationIndex < 0) {
            currentDestinationIndex = 0;
        }
    }

    /**
     * Represents a named destination coordinate.
     */
    public record Destination(String name, double x, double y, double z) {
    }
}
