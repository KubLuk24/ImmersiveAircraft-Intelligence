package immersive_aircraft.entity;

import immersive_aircraft.Items;
import immersive_aircraft.client.KeyBindings;
import immersive_aircraft.client.gui.AutopilotScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

/**
 * An autonomous biplane that can fly itself to a destination using autopilot.
 * The player crafts it with a biplane and an eye of ender.
 * When autopilot is engaged and a player is seated, it flies to the target coordinates,
 * avoids obstacles, and circles the destination upon arrival.
 */
public class AutonomousBiplaneEntity extends BiplaneEntity {
    private static final EntityDataAccessor<Boolean> AUTOPILOT_ENABLED =
            SynchedEntityData.defineId(AutonomousBiplaneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DEST_X =
            SynchedEntityData.defineId(AutonomousBiplaneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DEST_Y =
            SynchedEntityData.defineId(AutonomousBiplaneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DEST_Z =
            SynchedEntityData.defineId(AutonomousBiplaneEntity.class, EntityDataSerializers.INT);

    private static final double ARRIVAL_RADIUS = 20.0;
    private static final double CIRCLE_RADIUS = 30.0;
    private static final double CRUISE_ALTITUDE_OFFSET = 15.0;
    private static final double OBSTACLE_CHECK_DISTANCE = 12.0;

    private float circleAngle = 0.0f;
    private boolean isCircling = false;

    public AutonomousBiplaneEntity(EntityType<? extends AircraftEntity> entityType, Level world) {
        super(entityType, world);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(AUTOPILOT_ENABLED, false);
        entityData.define(DEST_X, 0);
        entityData.define(DEST_Y, 100);
        entityData.define(DEST_Z, 0);
    }

    public boolean isAutopilotEnabled() {
        return entityData.get(AUTOPILOT_ENABLED);
    }

    public void setAutopilotEnabled(boolean enabled) {
        entityData.set(AUTOPILOT_ENABLED, enabled);
        if (!enabled) {
            isCircling = false;
        }
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

    public void setDestination(int x, int y, int z) {
        entityData.set(DEST_X, x);
        entityData.set(DEST_Y, y);
        entityData.set(DEST_Z, z);
        isCircling = false;
    }

    @Override
    public Item asItem() {
        return Items.AUTONOMOUS_BIPLANE.get();
    }

    @Override
    public void tick() {
        // Disable autopilot if no player is seated (server-side check)
        if (!level().isClientSide && isAutopilotEnabled()) {
            boolean hasPlayer = getPassengers().stream()
                    .anyMatch(e -> e instanceof Player);
            if (!hasPlayer) {
                setAutopilotEnabled(false);
            }
        }

        super.tick();

        if (level().isClientSide) {
            for (var entity : getPassengers()) {
                if (entity instanceof Player player && player.isLocalPlayer()) {
                    if (KeyBindings.autopilot.consumeClick()) {
                        Minecraft.getInstance().setScreen(new AutopilotScreen(this));
                    }
                }
            }
        }
    }

    @Override
    protected void updateController() {
        if (!isVehicle()) {
            return;
        }

        boolean hasPlayerPilot = !getPassengers().isEmpty()
                && getPassengers().get(0) instanceof Player;

        if (isAutopilotEnabled() && hasPlayerPilot) {
            computeAndApplyAutopilotInputs();
        }

        // Always call super to apply the standard flight physics
        super.updateController();
    }

    private void computeAndApplyAutopilotInputs() {
        Vec3 dest = new Vec3(getDestX() + 0.5, getDestY(), getDestZ() + 0.5);
        Vec3 pos = position();

        double horizontalDist = Math.sqrt(
                (dest.x - pos.x) * (dest.x - pos.x) + (dest.z - pos.z) * (dest.z - pos.z)
        );

        float targetYaw;
        float targetPitch;
        float targetThrottle;

        if (horizontalDist < ARRIVAL_RADIUS) {
            isCircling = true;
        }

        if (isCircling) {
            circleAngle += 1.5f;
            if (circleAngle >= 360.0f) circleAngle -= 360.0f;

            double circleX = dest.x + Math.sin(Math.toRadians(circleAngle)) * CIRCLE_RADIUS;
            double circleZ = dest.z + Math.cos(Math.toRadians(circleAngle)) * CIRCLE_RADIUS;
            double circleY = dest.y + CRUISE_ALTITUDE_OFFSET;

            double dx = circleX - pos.x;
            double dz = circleZ - pos.z;
            double dy = circleY - pos.y;

            targetYaw = (float) (-Math.toDegrees(Math.atan2(dx, dz)));
            double circleHDist = Math.sqrt(dx * dx + dz * dz);
            targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, circleHDist)));
            targetThrottle = 0.7f;
        } else {
            double targetAltitude = Math.max(dest.y + CRUISE_ALTITUDE_OFFSET, pos.y);
            if (horizontalDist > 50) {
                targetAltitude = Math.max(dest.y + CRUISE_ALTITUDE_OFFSET, getDesiredCruiseAltitude());
            }

            double dx = dest.x - pos.x;
            double dz = dest.z - pos.z;
            double dy = targetAltitude - pos.y;

            targetYaw = (float) (-Math.toDegrees(Math.atan2(dx, dz)));
            targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDist)));
            targetThrottle = 1.0f;
        }

        if (shouldAvoidObstacle()) {
            targetPitch = -20.0f;
            targetThrottle = 1.0f;
        }

        // Calculate steering inputs
        float yawDiff = normalizeAngle(targetYaw - getYRot());
        float yawInput = 0.0f;
        if (Math.abs(yawDiff) > 1.0f) {
            yawInput = Math.max(-1.0f, Math.min(1.0f, yawDiff / 30.0f));
        }

        float pitchDiff = normalizeAngle(targetPitch - getXRot());
        float pitchInput = 0.0f;
        if (Math.abs(pitchDiff) > 0.5f) {
            pitchInput = Math.max(-1.0f, Math.min(1.0f, pitchDiff / 20.0f));
        }

        // Set inputs so the parent's updateController applies them via standard physics
        setInputs(yawInput, 0, pitchInput);

        // Set the engine throttle directly
        float currentTarget = getEngineTarget();
        if (Math.abs(currentTarget - targetThrottle) > 0.01f) {
            setEngineTarget(targetThrottle);
        }
    }

    private double getDesiredCruiseAltitude() {
        int groundHeight = getGroundHeightBelow();
        return Math.max(groundHeight + CRUISE_ALTITUDE_OFFSET + 10, getY());
    }

    private int getGroundHeightBelow() {
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos(
                (int) getX(), (int) getY(), (int) getZ()
        );
        for (int y = (int) getY(); y > level().getMinBuildHeight(); y--) {
            probe.setY(y);
            BlockState state = level().getBlockState(probe);
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                return y;
            }
        }
        return level().getMinBuildHeight();
    }

    private boolean shouldAvoidObstacle() {
        Vector3f forward = getForwardDirection();
        Vec3 pos = position();

        for (double dist = 2.0; dist <= OBSTACLE_CHECK_DISTANCE; dist += 2.0) {
            double checkX = pos.x + forward.x() * dist;
            double checkY = pos.y + forward.y() * dist;
            double checkZ = pos.z + forward.z() * dist;

            for (int dy = -1; dy <= 1; dy++) {
                BlockPos blockPos = new BlockPos((int) checkX, (int) (checkY + dy), (int) checkZ);
                BlockState state = level().getBlockState(blockPos);
                if (!state.isAir() && state.getFluidState().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static float normalizeAngle(float angle) {
        while (angle > 180.0f) angle -= 360.0f;
        while (angle < -180.0f) angle += 360.0f;
        return angle;
    }

    @Override
    protected void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("AutopilotEnabled", isAutopilotEnabled());
        tag.putInt("DestX", getDestX());
        tag.putInt("DestY", getDestY());
        tag.putInt("DestZ", getDestZ());
        tag.putFloat("CircleAngle", circleAngle);
        tag.putBoolean("IsCircling", isCircling);
    }

    @Override
    protected void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("AutopilotEnabled")) {
            setAutopilotEnabled(tag.getBoolean("AutopilotEnabled"));
        }
        if (tag.contains("DestX")) {
            setDestination(tag.getInt("DestX"), tag.getInt("DestY"), tag.getInt("DestZ"));
        }
        if (tag.contains("CircleAngle")) {
            circleAngle = tag.getFloat("CircleAngle");
        }
        if (tag.contains("IsCircling")) {
            isCircling = tag.getBoolean("IsCircling");
        }
    }
}
