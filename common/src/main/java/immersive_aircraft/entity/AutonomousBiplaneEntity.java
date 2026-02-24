package immersive_aircraft.entity;

import immersive_aircraft.Items;
import immersive_aircraft.client.KeyBindings;
import immersive_aircraft.client.gui.AutopilotScreen;
import immersive_aircraft.entity.autopilot.DestinationMemory;
import immersive_aircraft.entity.autopilot.ObstacleAvoidance;
import immersive_aircraft.entity.autopilot.PathPlanner;
import immersive_aircraft.entity.autopilot.TakeoffChecker;
import immersive_aircraft.entity.autopilot.TerrainScanner;
import immersive_aircraft.item.upgrade.VehicleStat;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

/**
 * An autonomous biplane that can fly itself to a destination using autopilot.
 *
 * The autopilot uses a modular navigation system:
 * - {@link TerrainScanner}: scans terrain in multiple directions each tick
 * - {@link ObstacleAvoidance}: decides whether to climb over or steer around obstacles
 * - {@link PathPlanner}: computes target yaw/pitch/throttle combining destination and avoidance
 * - {@link TakeoffChecker}: verifies runway clearance before engaging autopilot
 * - {@link DestinationMemory}: stores up to 5 preset destinations
 *
 * The key improvement: instead of blindly pitching up when an obstacle is
 * detected ahead, the system checks whether the plane's engine is powerful
 * enough to actually climb over the terrain. If not, it steers around it.
 */
public class AutonomousBiplaneEntity extends BiplaneEntity {
    // --- Synched entity data (shared between server and client) ---
    private static final EntityDataAccessor<Boolean> AUTOPILOT_ENABLED =
            SynchedEntityData.defineId(AutonomousBiplaneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DEST_X =
            SynchedEntityData.defineId(AutonomousBiplaneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DEST_Y =
            SynchedEntityData.defineId(AutonomousBiplaneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DEST_Z =
            SynchedEntityData.defineId(AutonomousBiplaneEntity.class, EntityDataSerializers.INT);

    // How often to run the full terrain scan (every N ticks).
    // Scanning every tick would be expensive; every 5 ticks is responsive enough.
    private static final int SCAN_INTERVAL = 5;

    // --- Modular autopilot subsystems ---
    private final TerrainScanner terrainScanner = new TerrainScanner();
    private final ObstacleAvoidance obstacleAvoidance = new ObstacleAvoidance();
    private final PathPlanner pathPlanner = new PathPlanner();
    private final TakeoffChecker takeoffChecker = new TakeoffChecker();
    private final DestinationMemory destinationMemory = new DestinationMemory();

    // Tick counter for throttling expensive scans
    private int scanTickCounter = 0;

    public AutonomousBiplaneEntity(EntityType<? extends AircraftEntity> entityType, Level world) {
        super(entityType, world);
    }

    // =========================================================================
    // Synched data & basic getters/setters
    // =========================================================================

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
            pathPlanner.reset();
        }
    }

    public int getDestX() { return entityData.get(DEST_X); }
    public int getDestY() { return entityData.get(DEST_Y); }
    public int getDestZ() { return entityData.get(DEST_Z); }

    public void setDestination(int x, int y, int z) {
        entityData.set(DEST_X, x);
        entityData.set(DEST_Y, y);
        entityData.set(DEST_Z, z);
        pathPlanner.reset();
    }

    /** Access the destination memory for preset save/load. */
    public DestinationMemory getDestinationMemory() {
        return destinationMemory;
    }

    @Override
    public Item asItem() {
        return Items.AUTONOMOUS_BIPLANE.get();
    }

    // =========================================================================
    // Takeoff check
    // =========================================================================

    /**
     * Attempts to enable autopilot. If the aircraft is on the ground, first
     * checks that there is a clear runway to take off from.
     *
     * @return true if autopilot was successfully enabled
     */
    public boolean tryEnableAutopilot() {
        if (!level().isClientSide && onGround()) {
            TakeoffChecker.TakeoffResult result = takeoffChecker.check(level(), position());
            if (!result.canTakeOff()) {
                // Notify the pilot that takeoff is blocked
                if (!getPassengers().isEmpty()
                        && getPassengers().get(0) instanceof ServerPlayer player) {
                    player.displayClientMessage(
                            Component.translatable("immersive_aircraft.autopilot.blocked"), true);
                }
                return false;
            }
            // Orient the aircraft toward the best takeoff direction
            setYRot(result.bestYaw());
        }
        setAutopilotEnabled(true);
        return true;
    }

    // =========================================================================
    // Tick & controller
    // =========================================================================

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

        // Client-side: open autopilot screen when keybind is pressed
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
            // Use the modular autopilot system instead of manual controls
            updateAutopilotController();
            return;
        }

        // Normal player-driven controls
        super.updateController();
    }

    // =========================================================================
    // Autopilot controller (the brain)
    // =========================================================================

    /**
     * Main autopilot control loop. Runs every tick when autopilot is engaged.
     *
     * Flow:
     * 1. Periodically scan terrain (every SCAN_INTERVAL ticks)
     * 2. Evaluate obstacle avoidance based on scan results
     * 3. Plan the flight path (target yaw, pitch, throttle)
     * 4. Apply flight controls (yaw, pitch, engine, thrust)
     */
    private void updateAutopilotController() {
        Vec3 pos = position();

        // --- Step 1: Terrain scanning (throttled to reduce CPU cost) ---
        scanTickCounter++;
        if (scanTickCounter >= SCAN_INTERVAL) {
            scanTickCounter = 0;
            terrainScanner.scan(level(), pos, getYRot());
            obstacleAvoidance.evaluate(terrainScanner, pos, getProperties(), getYRot());
        }

        // --- Step 2: Plan the flight path ---
        pathPlanner.plan(pos, getDestX(), getDestY(), getDestZ(),
                getYRot(), terrainScanner, obstacleAvoidance);

        float targetYaw = pathPlanner.getTargetYaw();
        float targetPitch = pathPlanner.getTargetPitch();
        float targetThrottle = pathPlanner.getTargetThrottle();

        // --- Step 3: Apply yaw control (proportional, rate-limited) ---
        float yawDiff = normalizeAngle(targetYaw - getYRot());
        float maxYawRate = getProperties().get(VehicleStat.YAW_SPEED);
        float yawChange = yawDiff * 0.1f;
        yawChange = Math.max(-maxYawRate, Math.min(maxYawRate, yawChange));
        setYRot(getYRot() + yawChange);

        // --- Step 4: Apply pitch control (proportional, rate-limited) ---
        float pitchDiff = normalizeAngle(targetPitch - getXRot());
        float maxPitchRate = getProperties().get(VehicleStat.PITCH_SPEED);
        float pitchChange = pitchDiff * 0.1f;
        pitchChange = Math.max(-maxPitchRate, Math.min(maxPitchRate, pitchChange));
        if (!onGround()) {
            setXRot(getXRot() + pitchChange);
        }
        // Apply stabilizer dampening (same as AircraftEntity)
        setXRot(getXRot() * (1.0f - getProperties().getAdditive(VehicleStat.STABILIZER)));

        // --- Step 5: Engine throttle ---
        setEngineTarget(targetThrottle);

        // --- Step 6: Thrust (replicates AirplaneEntity physics) ---
        Vector3f direction = getForwardDirection();
        float thrust = (float) (Math.pow(getEnginePower(), 2.0) * getProperties().get(VehicleStat.ENGINE_SPEED));
        if (onGround() && getEngineTarget() < 1.0) {
            thrust = getProperties().get(VehicleStat.PUSH_SPEED)
                    / (1.0f + (float) getDeltaMovement().length() * 5.0f)
                    * (1.0f - getEnginePower());
        }
        setDeltaMovement(getDeltaMovement().add(toVec3d(direction.mul(thrust))));

        // Zero out manual inputs so visual interpolation stays neutral
        setInputs(0, 0, 0);
    }

    /** Normalize an angle to the range [-180, 180]. */
    private static float normalizeAngle(float angle) {
        while (angle > 180.0f) angle -= 360.0f;
        while (angle < -180.0f) angle += 360.0f;
        return angle;
    }

    // =========================================================================
    // NBT save/load
    // =========================================================================

    @Override
    protected void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("AutopilotEnabled", isAutopilotEnabled());
        tag.putInt("DestX", getDestX());
        tag.putInt("DestY", getDestY());
        tag.putInt("DestZ", getDestZ());
        tag.putFloat("CircleAngle", pathPlanner.getCircleAngle());
        tag.putString("FlightPhase", pathPlanner.getPhase().name());

        // Save destination presets
        tag.put("DestinationPresets", destinationMemory.toNbt());
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
            pathPlanner.setCircleAngle(tag.getFloat("CircleAngle"));
        }

        // Load destination presets
        if (tag.contains("DestinationPresets", Tag.TAG_LIST)) {
            destinationMemory.fromNbt(tag.getList("DestinationPresets", Tag.TAG_COMPOUND));
        }
    }
}
