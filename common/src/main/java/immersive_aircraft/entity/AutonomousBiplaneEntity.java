package immersive_aircraft.entity;

import immersive_aircraft.Items;
import immersive_aircraft.client.KeyBindings;
import immersive_aircraft.client.gui.AutopilotScreen;
import immersive_aircraft.item.upgrade.VehicleStat;
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
 *
 * Features:
 * - Takeoff safety: scans surroundings before engaging, refuses if blocked.
 * - Intelligent obstacle avoidance: considers climb capability from upgrades;
 *   steers around obstacles when climbing isn't viable.
 * - Destination memory: stores up to 5 preset destinations.
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
    private static final EntityDataAccessor<CompoundTag> PRESETS =
            SynchedEntityData.defineId(AutonomousBiplaneEntity.class, EntityDataSerializers.COMPOUND_TAG);

    private static final double ARRIVAL_RADIUS = 20.0;
    private static final double CIRCLE_RADIUS = 30.0;
    private static final double CRUISE_ALTITUDE_OFFSET = 15.0;
    private static final double OBSTACLE_CHECK_DISTANCE = 16.0;
    private static final double TAKEOFF_CLEARANCE = 20.0;
    private static final int TAKEOFF_SCAN_DIRECTIONS = 8;
    private static final float BASE_ENGINE_SPEED = 0.03f;
    private static final float HIGH_CLIMB_THRESHOLD = 2.0f;
    public static final int MAX_PRESETS = 5;

    private float circleAngle = 0.0f;
    private boolean isCircling = false;
    private boolean isGainingAltitude = false;
    private float avoidanceYawOffset = 0.0f;

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
        entityData.define(PRESETS, new CompoundTag());
    }

    public boolean isAutopilotEnabled() {
        return entityData.get(AUTOPILOT_ENABLED);
    }

    /**
     * Attempts to enable/disable autopilot. When enabling on the ground,
     * performs a takeoff safety check. If no clear takeoff path exists,
     * autopilot is not engaged.
     *
     * @return true if the state was successfully changed
     */
    public boolean setAutopilotEnabled(boolean enabled) {
        if (enabled && onGround() && !level().isClientSide) {
            if (!canTakeOff()) {
                return false;
            }
            float bestYaw = findBestTakeoffYaw();
            setYRot(bestYaw);
        }
        entityData.set(AUTOPILOT_ENABLED, enabled);
        if (!enabled) {
            isCircling = false;
            isGainingAltitude = false;
            avoidanceYawOffset = 0.0f;
        }
        return true;
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
        isGainingAltitude = false;
        avoidanceYawOffset = 0.0f;
    }

    // --- Preset destination memory (up to 5 slots) ---

    public void savePreset(int slot, int x, int y, int z) {
        if (slot < 0 || slot >= MAX_PRESETS) return;
        CompoundTag presets = entityData.get(PRESETS).copy();
        presets.putInt("p" + slot + "x", x);
        presets.putInt("p" + slot + "y", y);
        presets.putInt("p" + slot + "z", z);
        presets.putBoolean("p" + slot + "set", true);
        entityData.set(PRESETS, presets);
    }

    public boolean hasPreset(int slot) {
        if (slot < 0 || slot >= MAX_PRESETS) return false;
        return entityData.get(PRESETS).getBoolean("p" + slot + "set");
    }

    public int getPresetX(int slot) {
        return entityData.get(PRESETS).getInt("p" + slot + "x");
    }

    public int getPresetY(int slot) {
        return entityData.get(PRESETS).getInt("p" + slot + "y");
    }

    public int getPresetZ(int slot) {
        return entityData.get(PRESETS).getInt("p" + slot + "z");
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
            updateAutopilotController();
            return;
        }

        // Normal player-driven controls
        super.updateController();
    }

    // --- Takeoff safety logic ---

    /**
     * Checks whether there is any direction with sufficient clearance for takeoff.
     * Scans 8 evenly spaced directions and requires at least TAKEOFF_CLEARANCE blocks
     * of clear space in at least one direction.
     */
    private boolean canTakeOff() {
        for (int i = 0; i < TAKEOFF_SCAN_DIRECTIONS; i++) {
            float yaw = i * (360.0f / TAKEOFF_SCAN_DIRECTIONS);
            if (checkDirectionClearance(yaw) >= TAKEOFF_CLEARANCE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the yaw angle with the most clear space ahead for takeoff.
     */
    private float findBestTakeoffYaw() {
        float bestYaw = getYRot();
        int bestClearance = 0;
        for (int i = 0; i < TAKEOFF_SCAN_DIRECTIONS; i++) {
            float yaw = i * (360.0f / TAKEOFF_SCAN_DIRECTIONS);
            int clearance = checkDirectionClearance(yaw);
            if (clearance > bestClearance) {
                bestClearance = clearance;
                bestYaw = yaw;
            }
        }
        return bestYaw;
    }

    /**
     * Returns the number of clear blocks ahead in the given yaw direction at ground level.
     */
    private int checkDirectionClearance(float yaw) {
        double rad = Math.toRadians(-yaw);
        double dx = Math.sin(rad);
        double dz = Math.cos(rad);
        Vec3 pos = position();
        int clearBlocks = 0;

        for (double dist = 1.0; dist <= TAKEOFF_CLEARANCE + 5; dist += 1.0) {
            boolean blocked = false;
            for (int dy = 0; dy <= 2; dy++) {
                BlockPos checkPos = new BlockPos(
                        (int) (pos.x + dx * dist),
                        (int) (pos.y + dy),
                        (int) (pos.z + dz * dist)
                );
                BlockState state = level().getBlockState(checkPos);
                if (!state.isAir() && state.getFluidState().isEmpty()) {
                    blocked = true;
                    break;
                }
            }
            if (blocked) break;
            clearBlocks++;
        }
        return clearBlocks;
    }

    // --- Intelligent obstacle avoidance ---

    /**
     * Estimates the plane's climb capability relative to the base configuration.
     * A ratio > HIGH_CLIMB_THRESHOLD means the plane can climb steeply.
     */
    private float getClimbCapability() {
        float engineSpeed = getProperties().get(VehicleStat.ENGINE_SPEED);
        return engineSpeed / BASE_ENGINE_SPEED;
    }

    /**
     * Checks for obstacles ahead and returns the height (in blocks) of the tallest
     * obstacle found in the forward path. Returns 0 if no obstacle is detected.
     */
    private int getObstacleHeight() {
        Vector3f forward = getForwardDirection();
        Vec3 pos = position();

        int maxObstacleHeight = 0;

        for (double dist = 2.0; dist <= OBSTACLE_CHECK_DISTANCE; dist += 2.0) {
            double checkX = pos.x + forward.x() * dist;
            double checkY = pos.y + forward.y() * dist;
            double checkZ = pos.z + forward.z() * dist;

            for (int dy = -1; dy <= 1; dy++) {
                BlockPos blockPos = new BlockPos((int) checkX, (int) (checkY + dy), (int) checkZ);
                BlockState state = level().getBlockState(blockPos);
                if (!state.isAir() && state.getFluidState().isEmpty()) {
                    int topHeight = findObstacleTop((int) checkX, (int) (checkY + dy), (int) checkZ);
                    int heightAbovePlane = topHeight - (int) pos.y;
                    maxObstacleHeight = Math.max(maxObstacleHeight, heightAbovePlane);
                }
            }
        }
        return maxObstacleHeight;
    }

    /**
     * Scans upward from a detected obstacle block to find the top of the obstacle.
     */
    private int findObstacleTop(int x, int startY, int z) {
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos(x, startY, z);
        int maxScan = 30;
        for (int y = startY; y < startY + maxScan; y++) {
            probe.setY(y);
            BlockState state = level().getBlockState(probe);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                return y;
            }
        }
        return startY + maxScan;
    }

    /**
     * Checks if a direction offset (relative to current yaw) is clear of obstacles.
     */
    private boolean isDirectionClear(float yawOffset, double distance) {
        float checkYaw = getYRot() + yawOffset;
        double rad = Math.toRadians(-checkYaw);
        double sinYaw = Math.sin(rad);
        double cosYaw = Math.cos(rad);
        Vec3 pos = position();

        for (double dist = 2.0; dist <= distance; dist += 2.0) {
            double checkX = pos.x + sinYaw * dist;
            double checkZ = pos.z + cosYaw * dist;

            for (int dy = -1; dy <= 1; dy++) {
                BlockPos blockPos = new BlockPos((int) checkX, (int) (pos.y + dy), (int) checkZ);
                BlockState state = level().getBlockState(blockPos);
                if (!state.isAir() && state.getFluidState().isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Determines the best avoidance yaw offset when an obstacle can't be climbed over.
     * Checks left (-45°) and right (+45°) for clearance, returns 0 if neither is clear.
     */
    private float chooseAvoidanceDirection() {
        boolean leftClear = isDirectionClear(-45.0f, OBSTACLE_CHECK_DISTANCE);
        boolean rightClear = isDirectionClear(45.0f, OBSTACLE_CHECK_DISTANCE);

        if (leftClear && !rightClear) return -45.0f;
        if (rightClear && !leftClear) return 45.0f;
        if (leftClear) {
            // Both clear: pick the side closer to the destination
            Vec3 dest = new Vec3(getDestX() + 0.5, getDestY(), getDestZ() + 0.5);
            Vec3 pos = position();
            float destYaw = (float) (-Math.toDegrees(Math.atan2(dest.x - pos.x, dest.z - pos.z)));
            float diff = normalizeAngle(destYaw - getYRot());
            return diff < 0 ? -45.0f : 45.0f;
        }
        // Neither side is clear — enter altitude gain mode
        return 0.0f;
    }

    // --- Main autopilot controller ---

    /**
     * Directly controls yaw, pitch, engine throttle, and thrust.
     * Handles obstacle avoidance intelligently: considers climb capability from
     * upgrades, steers around obstacles when climbing isn't viable, and can enter
     * an altitude-gain circling mode when fully blocked.
     */
    private void updateAutopilotController() {
        Vec3 dest = new Vec3(getDestX() + 0.5, getDestY(), getDestZ() + 0.5);
        Vec3 pos = position();

        double horizontalDist = Math.sqrt(
                (dest.x - pos.x) * (dest.x - pos.x) + (dest.z - pos.z) * (dest.z - pos.z)
        );

        float targetYaw;
        float targetPitch;
        float targetThrottle;

        if (horizontalDist < ARRIVAL_RADIUS && !isGainingAltitude) {
            isCircling = true;
        }

        // Exit altitude-gain mode once we've climbed enough above detected obstacles
        if (isGainingAltitude) {
            int obstacleH = getObstacleHeight();
            if (obstacleH <= 0 && !onGround()) {
                isGainingAltitude = false;
                avoidanceYawOffset = 0.0f;
            }
        }

        if (isGainingAltitude) {
            // Circle in place to gain altitude before continuing to destination
            circleAngle += 2.0f;
            if (circleAngle >= 360.0f) circleAngle -= 360.0f;

            double circleX = pos.x + Math.sin(Math.toRadians(circleAngle)) * CIRCLE_RADIUS * 0.5;
            double circleZ = pos.z + Math.cos(Math.toRadians(circleAngle)) * CIRCLE_RADIUS * 0.5;

            double dx = circleX - pos.x;
            double dz = circleZ - pos.z;

            targetYaw = (float) (-Math.toDegrees(Math.atan2(dx, dz)));
            targetPitch = -15.0f;
            targetThrottle = 1.0f;
        } else if (isCircling) {
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

        // --- Intelligent obstacle avoidance ---
        if (!isGainingAltitude && !isCircling) {
            int obstacleHeight = getObstacleHeight();
            if (obstacleHeight > 0) {
                float climbCap = getClimbCapability();
                if (climbCap >= HIGH_CLIMB_THRESHOLD || obstacleHeight <= 5) {
                    // High-power plane or low obstacle: climb over it
                    float climbPitch = Math.min(-10.0f, -5.0f * obstacleHeight);
                    climbPitch = Math.max(climbPitch, -45.0f);
                    targetPitch = climbPitch;
                    targetThrottle = 1.0f;
                } else {
                    // Low-power plane with tall obstacle: try to go around
                    if (avoidanceYawOffset == 0.0f) {
                        avoidanceYawOffset = chooseAvoidanceDirection();
                    }
                    if (avoidanceYawOffset != 0.0f) {
                        targetYaw = getYRot() + avoidanceYawOffset;
                        targetPitch = Math.min(targetPitch, -5.0f);
                        targetThrottle = 1.0f;
                    } else {
                        // Fully blocked in all directions: circle to gain altitude
                        isGainingAltitude = true;
                        circleAngle = 0.0f;
                        targetPitch = -15.0f;
                        targetThrottle = 1.0f;
                    }
                }
            } else {
                // No obstacle: decay avoidance offset
                avoidanceYawOffset *= 0.9f;
                if (Math.abs(avoidanceYawOffset) < 1.0f) {
                    avoidanceYawOffset = 0.0f;
                }
            }
        }

        // --- Direct yaw control (proportional, rate-limited) ---
        float yawDiff = normalizeAngle(targetYaw - getYRot());
        float maxYawRate = getProperties().get(VehicleStat.YAW_SPEED);
        float yawChange = yawDiff * 0.1f;
        yawChange = Math.max(-maxYawRate, Math.min(maxYawRate, yawChange));
        setYRot(getYRot() + yawChange);

        // --- Direct pitch control (proportional, rate-limited) ---
        float pitchDiff = normalizeAngle(targetPitch - getXRot());
        float maxPitchRate = getProperties().get(VehicleStat.PITCH_SPEED);
        float pitchChange = pitchDiff * 0.1f;
        pitchChange = Math.max(-maxPitchRate, Math.min(maxPitchRate, pitchChange));
        if (!onGround()) {
            setXRot(getXRot() + pitchChange);
        }
        // Apply stabilizer dampening (same as AircraftEntity)
        setXRot(getXRot() * (1.0f - getProperties().getAdditive(VehicleStat.STABILIZER)));

        // --- Engine throttle ---
        setEngineTarget(targetThrottle);

        // --- Thrust (replicates AirplaneEntity physics) ---
        Vector3f direction = getForwardDirection();
        float thrust = (float) (Math.pow(getEnginePower(), 2.0) * getProperties().get(VehicleStat.ENGINE_SPEED));
        if (onGround() && getEngineTarget() < 1.0) {
            thrust = getProperties().get(VehicleStat.PUSH_SPEED)
                    / (1.0f + (float) getDeltaMovement().length() * 5.0f)
                    * (1.0f - getEnginePower());
        }
        setDeltaMovement(getDeltaMovement().add(toVec3d(direction.mul(thrust))));

        // Set inputs to zero so the visual interpolation stays neutral
        setInputs(0, 0, 0);
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
        tag.putBoolean("IsGainingAltitude", isGainingAltitude);
        tag.put("Presets", entityData.get(PRESETS).copy());
    }

    @Override
    protected void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("AutopilotEnabled")) {
            entityData.set(AUTOPILOT_ENABLED, tag.getBoolean("AutopilotEnabled"));
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
        if (tag.contains("IsGainingAltitude")) {
            isGainingAltitude = tag.getBoolean("IsGainingAltitude");
        }
        if (tag.contains("Presets")) {
            entityData.set(PRESETS, tag.getCompound("Presets").copy());
        }
    }
}
