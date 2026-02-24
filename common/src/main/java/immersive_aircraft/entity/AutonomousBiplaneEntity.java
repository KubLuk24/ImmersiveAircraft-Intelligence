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
    private static final double OBSTACLE_CHECK_DISTANCE = 30.0;
    private static final double TAKEOFF_CLEARANCE = 20.0;
    private static final int TAKEOFF_SCAN_DIRECTIONS = 8;
    private static final float BASE_ENGINE_SPEED = 0.03f;
    private static final float HIGH_CLIMB_THRESHOLD = 2.0f;
    private static final int OBSTACLE_CLIMB_MAX_HEIGHT = 5;
    public static final int MAX_PRESETS = 5;

    private float circleAngle = 0.0f;
    private boolean isCircling = false;
    private boolean isGainingAltitude = false;
    private boolean isTakingOff = false;
    private float takeoffYaw = 0.0f;
    private int takeoffTicks = 0;
    private static final int TAKEOFF_MIN_TICKS = 60; // ~3 seconds of takeoff roll before avoidance kicks in
    private float avoidanceTargetYaw = Float.NaN;
    private int avoidanceCommitTicks = 0;
    private static final int AVOIDANCE_MIN_COMMIT_TICKS = 40;
    // Cone scan angles (relative to forward) for obstacle detection
    private static final float[] CONE_SCAN_OFFSETS = {0f, 30f, -30f, 60f, -60f, 90f, -90f};
    // Distance multipliers for each cone angle (closer angles scan further)
    private static final float[] CONE_DISTANCE_MULTIPLIERS = {1.0f, 0.85f, 0.85f, 0.65f, 0.65f, 0.45f, 0.45f};

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
            isTakingOff = true;
            takeoffYaw = bestYaw;
            takeoffTicks = 0;
        }
        entityData.set(AUTOPILOT_ENABLED, enabled);
        if (!enabled) {
            isCircling = false;
            isGainingAltitude = false;
            isTakingOff = false;
            takeoffTicks = 0;
            avoidanceTargetYaw = Float.NaN;
            avoidanceCommitTicks = 0;
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
        isTakingOff = false;
        takeoffTicks = 0;
        avoidanceTargetYaw = Float.NaN;
        avoidanceCommitTicks = 0;
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
        return (engineSpeed / BASE_ENGINE_SPEED) / 4;
    }

    /**
     * Checks for obstacles in a given absolute yaw direction and returns the height
     * (in blocks above the plane) of the tallest obstacle found. Returns 0 if clear.
     * Scans a 3-wide corridor perpendicular to the check direction at each step.
     */
    private int getObstacleHeightInDirection(float absoluteYaw, double checkDistance) {
        double rad = Math.toRadians(-absoluteYaw);
        double sinYaw = Math.sin(rad);
        double cosYaw = Math.cos(rad);
        // Perpendicular direction (90 degrees rotated)
        double perpSin = Math.cos(rad);  // sin(yaw + 90)
        double perpCos = -Math.sin(rad); // cos(yaw + 90)
        Vec3 pos = position();

        int maxObstacleHeight = 0;

        for (double dist = 2.0; dist <= checkDistance; dist += 2.0) {
            double centerX = pos.x + sinYaw * dist;
            double centerZ = pos.z + cosYaw * dist;

            // Scan a 3-wide corridor: center, left-1, right-1
            for (int lateral = -1; lateral <= 1; lateral++) {
                double checkX = centerX + perpSin * lateral;
                double checkZ = centerZ + perpCos * lateral;

                for (int dy = -2; dy <= 3; dy++) {
                    BlockPos blockPos = new BlockPos((int) checkX, (int) (pos.y + dy), (int) checkZ);
                    BlockState state = level().getBlockState(blockPos);
                    if (!state.isAir() && state.getFluidState().isEmpty()) {
                        int topHeight = findObstacleTop((int) checkX, (int) (pos.y + dy), (int) checkZ);
                        int heightAbovePlane = topHeight - (int) pos.y;
                        maxObstacleHeight = Math.max(maxObstacleHeight, heightAbovePlane);
                    }
                }
            }
        }
        return maxObstacleHeight;
    }

    /**
     * Checks for obstacles ahead using a full forward cone scan.
     * Scans at 0°, ±30°, ±60°, ±90° with decreasing range for wider angles.
     * Returns the height of the tallest obstacle found across all cone rays.
     */
    private int getObstacleHeight() {
        float yaw = getYRot();
        int maxHeight = 0;

        for (int i = 0; i < CONE_SCAN_OFFSETS.length; i++) {
            float scanYaw = yaw + CONE_SCAN_OFFSETS[i];
            double scanDist = OBSTACLE_CHECK_DISTANCE * CONE_DISTANCE_MULTIPLIERS[i];
            int h = getObstacleHeightInDirection(scanYaw, scanDist);
            maxHeight = Math.max(maxHeight, h);
        }

        return maxHeight;
    }

    /**
     * Checks if the direct path toward the destination is clear of obstacles.
     * Uses a cone scan centered on the destination direction.
     */
    private boolean isPathToDestinationClear() {
        Vec3 dest = new Vec3(getDestX() + 0.5, getDestY(), getDestZ() + 0.5);
        Vec3 pos = position();
        float destYaw = (float) (-Math.toDegrees(Math.atan2(dest.x - pos.x, dest.z - pos.z)));

        // Check a narrow cone toward destination: 0°, ±15°, ±30°
        float[] destConeOffsets = {0f, 15f, -15f, 30f, -30f};
        float[] destConeDistMul = {1.0f, 0.8f, 0.8f, 0.6f, 0.6f};

        for (int i = 0; i < destConeOffsets.length; i++) {
            int h = getObstacleHeightInDirection(destYaw + destConeOffsets[i],
                    OBSTACLE_CHECK_DISTANCE * destConeDistMul[i]);
            if (h > 0) return false;
        }
        return true;
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
     * Scans a 3-wide corridor AND verifies sub-angles (±15°) to ensure the plane
     * won't hit anything after turning into this direction.
     */
    private boolean isDirectionClear(float yawOffset, double distance) {
        // Check main direction and ±15° sub-angles for a cone-like clearance check
        float[] subOffsets = {0f, -15f, 15f};
        float[] subDistMul = {1.0f, 0.7f, 0.7f};

        for (int s = 0; s < subOffsets.length; s++) {
            float checkYaw = getYRot() + yawOffset + subOffsets[s];
            double checkDist = distance * subDistMul[s];
            double rad = Math.toRadians(-checkYaw);
            double sinYaw = Math.sin(rad);
            double cosYaw = Math.cos(rad);
            double perpSin = Math.cos(rad);
            double perpCos = -Math.sin(rad);
            Vec3 pos = position();

            for (double dist = 2.0; dist <= checkDist; dist += 2.0) {
                double centerX = pos.x + sinYaw * dist;
                double centerZ = pos.z + cosYaw * dist;

                for (int lateral = -1; lateral <= 1; lateral++) {
                    double checkX = centerX + perpSin * lateral;
                    double checkZ = centerZ + perpCos * lateral;

                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos blockPos = new BlockPos((int) checkX, (int) (pos.y + dy), (int) checkZ);
                        BlockState state = level().getBlockState(blockPos);
                        if (!state.isAir() && state.getFluidState().isEmpty()) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Determines the best avoidance absolute yaw when an obstacle can't be climbed over.
     * Scans multiple angles left and right to find the clearest path,
     * preferring the side closer to the destination.
     */
    private float chooseAvoidanceAbsoluteYaw() {
        Vec3 dest = new Vec3(getDestX() + 0.5, getDestY(), getDestZ() + 0.5);
        Vec3 pos = position();
        float destYaw = (float) (-Math.toDegrees(Math.atan2(dest.x - pos.x, dest.z - pos.z)));

        // Try angles from 30 to 120 degrees in both directions
        float bestYaw = Float.NaN;
        float bestScore = Float.NEGATIVE_INFINITY;

        for (float offset = 30.0f; offset <= 120.0f; offset += 15.0f) {
            for (float sign : new float[]{-1.0f, 1.0f}) {
                float candidateYaw = getYRot() + offset * sign;
                if (isDirectionClear(offset * sign, OBSTACLE_CHECK_DISTANCE)) {
                    // Score: prefer directions closer to destination
                    float angleToDest = Math.abs(normalizeAngle(candidateYaw - destYaw));
                    float score = 180.0f - angleToDest; // Higher = closer to dest direction
                    if (score > bestScore) {
                        bestScore = score;
                        bestYaw = candidateYaw;
                    }
                }
            }
        }

        return bestYaw; // NaN if nothing is clear
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

        // --- Takeoff mode: simple straight climb, no obstacle avoidance ---
        if (isTakingOff) {
            takeoffTicks++;
            targetYaw = takeoffYaw;
            targetThrottle = 1.0f;

            if (onGround()) {
                // Still on ground: level pitch, full throttle, maintain heading
                targetPitch = 0.0f;
            } else {
                // Airborne: gentle climb
                targetPitch = -10.0f;
            }

            // Exit takeoff mode once airborne for enough ticks and above ground level
            if (!onGround() && takeoffTicks > TAKEOFF_MIN_TICKS) {
                isTakingOff = false;
                takeoffTicks = 0;
            }

            // Apply controls and return early — skip all other logic
            applyAutopilotControls(targetYaw, targetPitch, targetThrottle);
            return;
        }

        if (horizontalDist < ARRIVAL_RADIUS && !isGainingAltitude) {
            isCircling = true;
        }

        // Exit altitude-gain mode once we've climbed enough above detected obstacles
        if (isGainingAltitude) {
            int obstacleH = getObstacleHeight();
            if (obstacleH <= 0 && !onGround()) {
                isGainingAltitude = false;
                avoidanceTargetYaw = Float.NaN;
                avoidanceCommitTicks = 0;
            }
        }

        // Tick down avoidance commitment
        if (avoidanceCommitTicks > 0) {
            avoidanceCommitTicks--;
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

        // --- Intelligent obstacle avoidance (only when airborne) ---
        if (!isGainingAltitude && !isCircling && !onGround()) {
            // Check if we're currently in avoidance mode
            boolean inAvoidance = !Float.isNaN(avoidanceTargetYaw);

            if (inAvoidance) {
                // We're committed to an avoidance maneuver
                // Only exit if: commitment timer expired AND path to destination is clear
                if (avoidanceCommitTicks <= 0 && isPathToDestinationClear()) {
                    // Safe to return to destination heading
                    avoidanceTargetYaw = Float.NaN;
                    // targetYaw already set to destination direction above
                } else {
                    // Still avoiding: re-check if our avoidance direction is still clear
                    int obstacleInAvoidDir = getObstacleHeightInDirection(avoidanceTargetYaw, OBSTACLE_CHECK_DISTANCE);
                    if (obstacleInAvoidDir > 0) {
                        // Our avoidance direction is now also blocked, pick a new one
                        float newAvoidYaw = chooseAvoidanceAbsoluteYaw();
                        if (!Float.isNaN(newAvoidYaw)) {
                            avoidanceTargetYaw = newAvoidYaw;
                            avoidanceCommitTicks = AVOIDANCE_MIN_COMMIT_TICKS;
                        } else {
                            // Fully blocked: enter altitude gain mode
                            isGainingAltitude = true;
                            circleAngle = 0.0f;
                            avoidanceTargetYaw = Float.NaN;
                            avoidanceCommitTicks = 0;
                        }
                    }
                    // Use avoidance yaw
                    if (!Float.isNaN(avoidanceTargetYaw)) {
                        targetYaw = avoidanceTargetYaw;
                        targetPitch = Math.min(targetPitch, -5.0f);
                        targetThrottle = 1.0f;
                    }
                }
            } else {
                // Not in avoidance: check for obstacles using forward + side sensors
                int obstacleHeight = getObstacleHeight();
                if (obstacleHeight > 0) {
                    float climbCap = getClimbCapability();
                    if (obstacleHeight <= OBSTACLE_CLIMB_MAX_HEIGHT || climbCap >= HIGH_CLIMB_THRESHOLD) {
                        // Obstacle is climbable (trees, small hills) or plane has high power:
                        // always prefer climbing over navigation through forests
                        // Scale pitch aggressiveness by velocity: faster = can pitch harder
                        double speed = getDeltaMovement().horizontalDistance();
                        float speedFactor = (float) Math.min(1.0 + speed * 3.0, 2.5); // 1.0 at rest, up to 2.5 at high speed
                        float climbPitch = Math.min(-10.0f, -5.0f * obstacleHeight * speedFactor);
                        climbPitch = Math.max(climbPitch, -45.0f);
                        targetPitch = climbPitch;
                        targetThrottle = 1.0f;
                    } else {
                        // Very tall obstacle (mountain) with low-power plane: steer around
                        float avoidYaw = chooseAvoidanceAbsoluteYaw();
                        if (!Float.isNaN(avoidYaw)) {
                            avoidanceTargetYaw = avoidYaw;
                            avoidanceCommitTicks = AVOIDANCE_MIN_COMMIT_TICKS;
                            targetYaw = avoidanceTargetYaw;
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
                }
            }
        }

        applyAutopilotControls(targetYaw, targetPitch, targetThrottle);
    }

    /**
     * Applies the computed yaw, pitch, throttle and thrust to the aircraft.
     * Extracted to avoid duplication between takeoff and normal flight modes.
     */
    private void applyAutopilotControls(float targetYaw, float targetPitch, float targetThrottle) {
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
        tag.putBoolean("IsTakingOff", isTakingOff);
        tag.putFloat("TakeoffYaw", takeoffYaw);
        tag.putInt("TakeoffTicks", takeoffTicks);
        tag.putFloat("AvoidanceTargetYaw", Float.isNaN(avoidanceTargetYaw) ? Float.MAX_VALUE : avoidanceTargetYaw);
        tag.putInt("AvoidanceCommitTicks", avoidanceCommitTicks);
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
        if (tag.contains("IsTakingOff")) {
            isTakingOff = tag.getBoolean("IsTakingOff");
        }
        if (tag.contains("TakeoffYaw")) {
            takeoffYaw = tag.getFloat("TakeoffYaw");
        }
        if (tag.contains("TakeoffTicks")) {
            takeoffTicks = tag.getInt("TakeoffTicks");
        }
        if (tag.contains("AvoidanceTargetYaw")) {
            float stored = tag.getFloat("AvoidanceTargetYaw");
            avoidanceTargetYaw = (stored == Float.MAX_VALUE) ? Float.NaN : stored;
        }
        if (tag.contains("AvoidanceCommitTicks")) {
            avoidanceCommitTicks = tag.getInt("AvoidanceCommitTicks");
        }
        if (tag.contains("Presets")) {
            entityData.set(PRESETS, tag.getCompound("Presets").copy());
        }
    }
}
