package immersive_aircraft.entity;

import immersive_aircraft.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class AutonomousBiplaneEntity extends BiplaneEntity {
    private static final EntityDataAccessor<Boolean> AUTOPILOT_ENABLED = SynchedEntityData.defineId(AutonomousBiplaneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> TARGET_X = SynchedEntityData.defineId(AutonomousBiplaneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TARGET_Z = SynchedEntityData.defineId(AutonomousBiplaneEntity.class, EntityDataSerializers.INT);

    public AutonomousBiplaneEntity(EntityType<? extends AircraftEntity> entityType, Level world) {
        super(entityType, world);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(AUTOPILOT_ENABLED, false);
        entityData.define(TARGET_X, 0);
        entityData.define(TARGET_Z, 0);
    }

    @Override
    public Item asItem() {
        return Items.AUTONOMOUS_BIPLANE.get();
    }

    public boolean isAutopilotEnabled() {
        return entityData.get(AUTOPILOT_ENABLED);
    }

    public void setAutopilotEnabled(boolean enabled) {
        entityData.set(AUTOPILOT_ENABLED, enabled);
    }

    public void setTarget(int x, int z) {
        entityData.set(TARGET_X, x);
        entityData.set(TARGET_Z, z);
    }

    public int getTargetX() {
        return entityData.get(TARGET_X);
    }

    public int getTargetZ() {
        return entityData.get(TARGET_Z);
    }

    @Override
    public void setInputs(float x, float y, float z) {
        if (isAutopilotEnabled() && getControllingPassenger() != null) {
            updateAutopilotInputs();
        } else {
            super.setInputs(x, y, z);
        }
    }

    private void updateAutopilotInputs() {
        double tx = getTargetX();
        double tz = getTargetZ();
        double dx = tx - getX();
        double dz = tz - getZ();
        double distSq = dx * dx + dz * dz;

        float targetYaw = (float) (Mth.atan2(dz, dx) * (180 / Math.PI)) - 90;

        // Circling if close
        if (distSq < 100 * 100) { // 100 blocks radius
             targetYaw += 90; // Fly tangent
        }

        float yawDiff = Mth.wrapDegrees(targetYaw - getYRot());

        // Yaw control (x input)
        // X > 0 decreases Yaw (Left).
        // If yawDiff > 0, we want to increase Yaw (Right). So X should be < 0.
        float inputX = -Mth.clamp(yawDiff * 0.05f, -1.0f, 1.0f);

        // Pitch control (z input)
        // Z > 0 increases XRot (pushes nose down).
        // Z < 0 decreases XRot (pulls nose up).

        double targetY = 120; // Cruising altitude

        // Obstacle avoidance
        Vec3 forward = toVec3d(getForwardDirection()).normalize();
        Vec3 pos = position();
        boolean obstacleAhead = false;
        for (int i = 0; i < 50; i+=5) {
             BlockPos p = BlockPos.containing(pos.add(forward.scale(i)));
             if (!level().isEmptyBlock(p)) {
                 obstacleAhead = true;
                 break;
             }
        }

        float inputZ = 0;

        if (obstacleAhead) {
            inputZ = -1.0f; // Pull up hard
        } else {
            // Altitude control
            if (getY() < targetY) {
                inputZ = -0.5f; // Climb
            } else if (getY() > targetY + 10) {
                inputZ = 0.2f; // Descend gently
            }
        }

        // Level out pitch if no explicit input
        if (inputZ == 0) {
             // If XRot is negative (up), we want positive Z to go down.
             inputZ = Mth.clamp(-getXRot() * 0.05f, -0.5f, 0.5f);
        }

        // Apply
        super.setInputs(inputX, 0, inputZ);

        // Engine
        setEngineTarget(1.0f);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("AutopilotEnabled", isAutopilotEnabled());
        tag.putInt("TargetX", getTargetX());
        tag.putInt("TargetZ", getTargetZ());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setAutopilotEnabled(tag.getBoolean("AutopilotEnabled"));
        setTarget(tag.getInt("TargetX"), tag.getInt("TargetZ"));
    }
}
