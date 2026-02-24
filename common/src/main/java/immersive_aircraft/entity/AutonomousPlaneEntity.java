package immersive_aircraft.entity;

import immersive_aircraft.Items;
import immersive_aircraft.entity.misc.TrailDescriptor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * An autonomous airplane that can fly to a set destination automatically.
 * Players can set destination coordinates and save destinations to its memory.
 * It will circle around the destination once arrived and avoid obstacles.
 */
public class AutonomousPlaneEntity extends AirplaneEntity {

    private static final EntityDataAccessor<Boolean> AUTONOMOUS_MODE =
            SynchedEntityData.defineId(AutonomousPlaneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DEST_X =
            SynchedEntityData.defineId(AutonomousPlaneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DEST_Y =
            SynchedEntityData.defineId(AutonomousPlaneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DEST_Z =
            SynchedEntityData.defineId(AutonomousPlaneEntity.class, EntityDataSerializers.INT);

    private static final double ARRIVAL_DISTANCE = 30.0;
    private static final double CIRCLE_RADIUS = 25.0;
    private static final float CRUISE_ALTITUDE_OFFSET = 20.0f;
    private static final float OBSTACLE_CHECK_DISTANCE = 16.0f;
    private static final float OBSTACLE_AVOIDANCE_STRENGTH = 8.0f;

    private final List<SavedDestination> savedDestinations = new ArrayList<>();
    private boolean circling = false;
    private float circleAngle = 0.0f;

    public AutonomousPlaneEntity(EntityType<? extends AircraftEntity> entityType, Level world) {
        super(entityType, world, true);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(AUTONOMOUS_MODE, false);
        entityData.define(DEST_X, 0);
        entityData.define(DEST_Y, 100);
        entityData.define(DEST_Z, 0);
    }

    public boolean isAutonomousMode() {
        return entityData.get(AUTONOMOUS_MODE);
    }

    public void setAutonomousMode(boolean autonomous) {
        entityData.set(AUTONOMOUS_MODE, autonomous);
        if (autonomous) {
            circling = false;
            circleAngle = getYRot();
            setEngineTarget(1.0f);
        } else {
            circling = false;
        }
    }

    public void setDestination(int x, int y, int z) {
        entityData.set(DEST_X, x);
        entityData.set(DEST_Y, y);
        entityData.set(DEST_Z, z);
        circling = false;
    }

    public int getDestX() {
        return entityData.get(DEST_X);
    }

    public int getDestY() {
        return entityData.get(DEST_Y);
    }

    public int getDestZ() {
        return entityData.get(DEST_Z);
    }

    public void saveDestination(String name) {
        savedDestinations.add(new SavedDestination(name, getDestX(), getDestY(), getDestZ()));
    }

    public void loadDestination(int index) {
        if (index >= 0 && index < savedDestinations.size()) {
            SavedDestination dest = savedDestinations.get(index);
            setDestination(dest.x, dest.y, dest.z);
        }
    }

    public void removeDestination(int index) {
        if (index >= 0 && index < savedDestinations.size()) {
            savedDestinations.remove(index);
        }
    }

    public List<SavedDestination> getSavedDestinations() {
        return savedDestinations;
    }

    @Override
    public InteractionResult interact(@NotNull Player player, @NotNull InteractionHand hand) {
        if (!level().isClientSide && player.isShiftKeyDown() && !hasPassenger(player)) {
            if (isAutonomousMode()) {
                setAutonomousMode(false);
                player.displayClientMessage(Component.literal("Autonomous mode disabled"), true);
                return InteractionResult.CONSUME;
            }
        }
        return super.interact(player, hand);
    }

    @Override
    public void tick() {
        super.tick();

        // Smoke particles
        emitSmokeParticle(
                0.325f * (tickCount % 2 == 0 ? -1.0f : 1.0f), 0.5f, 0.8f,
                0.2f * (tickCount % 2 == 0 ? -1.0f : 1.0f), 0.0f, 0.0f
        );
    }

    @Override
    protected void updateController() {
        if (isAutonomousMode() && isVehicle()) {
            // Autonomous control - engine is always on
            if (getEngineTarget() < 1.0f) {
                setEngineTarget(1.0f);
            }

            // Perform autonomous navigation (steering, obstacle avoidance)
            performAutonomousFlight();

            // Apply thrust
            Vector3f direction = getForwardDirection();
            float thrust = (float) (Math.pow(getEnginePower(), 2.0) * getProperties().get(
                    immersive_aircraft.item.upgrade.VehicleStat.ENGINE_SPEED));

            setDeltaMovement(getDeltaMovement().add(toVec3d(direction.mul(thrust))));
        } else {
            // Normal player control
            super.updateController();
        }
    }

    private void performAutonomousFlight() {
        double destX = getDestX();
        double destY = getDestY();
        double destZ = getDestZ();

        double dx = destX - getX();
        double dz = destZ - getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDistance < ARRIVAL_DISTANCE && !circling) {
            circling = true;
            circleAngle = getYRot();
            notifyPassengers("Arrived at destination, circling...");
        }

        float targetYaw;
        float targetPitch;

        if (circling) {
            // Circle around the destination
            circleAngle += 1.5f;
            if (circleAngle > 180.0f) circleAngle -= 360.0f;

            double circleX = destX + Math.cos(Math.toRadians(circleAngle)) * CIRCLE_RADIUS;
            double circleZ = destZ + Math.sin(Math.toRadians(circleAngle)) * CIRCLE_RADIUS;

            double cdx = circleX - getX();
            double cdz = circleZ - getZ();
            targetYaw = (float) (-Math.toDegrees(Math.atan2(cdx, cdz)));
            targetPitch = calculateTargetPitch(destY, Math.sqrt(cdx * cdx + cdz * cdz));
        } else {
            // Fly toward destination
            targetYaw = (float) (-Math.toDegrees(Math.atan2(dx, dz)));
            targetPitch = calculateTargetPitch(destY + CRUISE_ALTITUDE_OFFSET, horizontalDistance);
        }

        // Obstacle avoidance adjustments
        float avoidancePitch = checkObstacles();
        targetPitch += avoidancePitch;

        // Smoothly steer toward target
        applySteering(targetYaw, Mth.clamp(targetPitch, -30.0f, 30.0f));
    }

    private float calculateTargetPitch(double targetY, double horizontalDistance) {
        double dy = targetY - getY();
        float desiredPitch;

        if (horizontalDistance > 10.0) {
            desiredPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDistance));
        } else {
            // When close, level out
            if (Math.abs(dy) > 5.0) {
                desiredPitch = dy > 0 ? -15.0f : 15.0f;
            } else {
                desiredPitch = 0.0f;
            }
        }

        return Mth.clamp(desiredPitch, -30.0f, 30.0f);
    }

    private float checkObstacles() {
        // Check blocks ahead and below for obstacles
        Vector3f forward = getForwardDirection();
        Vec3 pos = position();
        float avoidance = 0.0f;

        // Check several points ahead
        for (float dist = 4.0f; dist <= OBSTACLE_CHECK_DISTANCE; dist += 4.0f) {
            double checkX = pos.x + forward.x * dist;
            double checkY = pos.y;
            double checkZ = pos.z + forward.z * dist;

            // Check at current altitude and slightly below
            for (int yOffset = 0; yOffset >= -3; yOffset--) {
                BlockPos checkPos = new BlockPos((int) checkX, (int) checkY + yOffset, (int) checkZ);
                BlockState state = level().getBlockState(checkPos);
                if (!state.isAir() && !state.liquid()) {
                    // Found an obstacle, need to climb
                    float urgency = 1.0f - (dist / OBSTACLE_CHECK_DISTANCE);
                    avoidance = Math.min(avoidance, -OBSTACLE_AVOIDANCE_STRENGTH * (1.0f + urgency));
                    break;
                }
            }

            // Also check above to avoid ceilings
            for (int yOffset = 1; yOffset <= 3; yOffset++) {
                BlockPos checkPos = new BlockPos((int) checkX, (int) checkY + yOffset, (int) checkZ);
                BlockState state = level().getBlockState(checkPos);
                if (!state.isAir() && !state.liquid()) {
                    // Ceiling detected, pitch down slightly
                    float urgency = 1.0f - (dist / OBSTACLE_CHECK_DISTANCE);
                    avoidance = Math.max(avoidance, OBSTACLE_AVOIDANCE_STRENGTH * 0.5f * (1.0f + urgency));
                    break;
                }
            }
        }

        return avoidance;
    }

    private void applySteering(float targetYaw, float targetPitch) {
        float yawSpeed = getProperties().get(immersive_aircraft.item.upgrade.VehicleStat.YAW_SPEED);
        float pitchSpeed = getProperties().get(immersive_aircraft.item.upgrade.VehicleStat.PITCH_SPEED);

        // Calculate yaw difference
        float yawDiff = Mth.wrapDegrees(targetYaw - getYRot());
        float yawInput = Mth.clamp(yawDiff / 30.0f, -1.0f, 1.0f);

        // Apply yaw
        setYRot(getYRot() - yawSpeed * yawInput);

        // Calculate pitch difference
        float pitchDiff = targetPitch - getXRot();
        float pitchInput = Mth.clamp(pitchDiff / 20.0f, -1.0f, 1.0f);

        // Apply pitch
        if (!onGround()) {
            setXRot(getXRot() + pitchSpeed * pitchInput);
        }

        // Apply stabilizer
        setXRot(getXRot() * (1.0f - getProperties().getAdditive(
                immersive_aircraft.item.upgrade.VehicleStat.STABILIZER)));

        // Set roll based on yaw input for visual feedback
        pressingInterpolatedX.update(yawInput);
    }

    private void notifyPassengers(String message) {
        for (var passenger : getPassengers()) {
            if (passenger instanceof Player player) {
                player.displayClientMessage(Component.literal(message), true);
            }
        }
    }

    @Override
    public float getBaseTrailWidth(Matrix4f transform, int index, TrailDescriptor trail) {
        return (float) (Math.sqrt(getDeltaMovement().length()) * (0.5f - (pressingInterpolatedX.getSmooth() * trail.x()) * 0.025f) - 0.25f);
    }

    @Override
    public Item asItem() {
        return Items.AUTONOMOUS_PLANE.get();
    }

    @Override
    public double getZoom() {
        return 3.0;
    }

    @Override
    protected void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);

        tag.putBoolean("AutonomousMode", isAutonomousMode());
        tag.putInt("DestX", getDestX());
        tag.putInt("DestY", getDestY());
        tag.putInt("DestZ", getDestZ());

        ListTag destList = new ListTag();
        for (SavedDestination dest : savedDestinations) {
            CompoundTag destTag = new CompoundTag();
            destTag.putString("Name", dest.name);
            destTag.putInt("X", dest.x);
            destTag.putInt("Y", dest.y);
            destTag.putInt("Z", dest.z);
            destList.add(destTag);
        }
        tag.put("SavedDestinations", destList);
    }

    @Override
    protected void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        setAutonomousMode(tag.getBoolean("AutonomousMode"));
        setDestination(tag.getInt("DestX"), tag.getInt("DestY"), tag.getInt("DestZ"));

        savedDestinations.clear();
        if (tag.contains("SavedDestinations", Tag.TAG_LIST)) {
            ListTag destList = tag.getList("SavedDestinations", Tag.TAG_COMPOUND);
            for (int i = 0; i < destList.size(); i++) {
                CompoundTag destTag = destList.getCompound(i);
                savedDestinations.add(new SavedDestination(
                        destTag.getString("Name"),
                        destTag.getInt("X"),
                        destTag.getInt("Y"),
                        destTag.getInt("Z")
                ));
            }
        }
    }

    /**
     * A saved destination in the autonomous plane's memory.
     */
    public record SavedDestination(String name, int x, int y, int z) {
    }
}
