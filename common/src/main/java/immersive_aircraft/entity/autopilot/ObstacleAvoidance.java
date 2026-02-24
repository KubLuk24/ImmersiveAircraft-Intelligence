package immersive_aircraft.entity.autopilot;

import immersive_aircraft.item.upgrade.VehicleStat;
import immersive_aircraft.entity.misc.VehicleProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Determines how the aircraft should react to obstacles detected by the
 * {@link TerrainScanner}.
 *
 * The core decision is: can the aircraft climb over the terrain ahead, or
 * should it steer around it?
 *
 * A default (un-upgraded) biplane has ENGINE_SPEED ~0.03 and can barely climb.
 * A fully upgraded plane (nether engine, steel boiler, enhanced propeller,
 * sturdy pipes) has much higher ENGINE_SPEED and can climb vertically.
 *
 * The algorithm:
 * 1. Compute a "climb power" score from the aircraft's upgrade stats.
 * 2. Compute how high the aircraft would need to climb to clear terrain ahead.
 * 3. If the climb is feasible (small height difference OR powerful engine),
 *    command a climb (pitch up).
 * 4. If the climb is NOT feasible (tall mountain, weak engine), command a
 *    lateral detour (steer toward the side with lower terrain).
 * 5. In emergency (very close obstacle), do an aggressive evasive turn.
 */
public class ObstacleAvoidance {

    /**
     * The avoidance decision made this tick.
     */
    public enum AvoidanceAction {
        /** No obstacle, fly normally */
        NONE,
        /** Climb over the terrain ahead */
        CLIMB,
        /** Steer left to go around */
        STEER_LEFT,
        /** Steer right to go around */
        STEER_RIGHT,
        /** Emergency evasion: hard turn toward the clearest side */
        EMERGENCY_TURN
    }

    // The minimum safe altitude margin above detected terrain
    private static final int SAFE_ALTITUDE_MARGIN = 15;
    // Height difference threshold above which a default plane should NOT try to climb
    // (a weak engine simply cannot gain altitude fast enough)
    private static final int DEFAULT_PLANE_MAX_CLIMB = 20;
    // Engine speed threshold: planes above this can be considered "high power"
    private static final float HIGH_POWER_ENGINE_THRESHOLD = 0.06f;
    // How much yaw offset (degrees) to apply when steering around terrain
    private static final float LATERAL_STEER_ANGLE = 35.0f;
    // How much yaw offset for emergency evasion
    private static final float EMERGENCY_STEER_ANGLE = 60.0f;
    // Pitch to command when climbing over terrain (degrees, negative = nose up)
    private static final float CLIMB_PITCH = -15.0f;
    // Pitch for a powerful plane doing an aggressive climb
    private static final float AGGRESSIVE_CLIMB_PITCH = -25.0f;

    private AvoidanceAction currentAction = AvoidanceAction.NONE;
    private float steerYawOffset = 0.0f;
    private float climbPitch = 0.0f;

    /**
     * Evaluate the terrain scan results and decide on an avoidance action.
     *
     * @param scanner    the terrain scanner with fresh results
     * @param pos        current aircraft position
     * @param properties the aircraft's properties (stats including upgrades)
     * @param currentYaw the aircraft's current yaw in degrees
     */
    public void evaluate(TerrainScanner scanner, Vec3 pos, VehicleProperties properties, float currentYaw) {
        int aircraftY = (int) pos.y;
        int forwardTerrainHeight = scanner.getForwardMaxTerrainHeight();
        int leftTerrainHeight = scanner.getLeftMaxTerrainHeight();
        int rightTerrainHeight = scanner.getRightMaxTerrainHeight();

        // How high above current altitude the terrain ahead reaches
        int heightDifference = forwardTerrainHeight - aircraftY + SAFE_ALTITUDE_MARGIN;

        // Determine the aircraft's climb capability from its engine power stat
        float engineSpeed = properties.get(VehicleStat.ENGINE_SPEED);
        boolean isHighPower = engineSpeed >= HIGH_POWER_ENGINE_THRESHOLD;

        // Max climb the engine can reasonably handle:
        // A high-power plane can climb much more aggressively
        int maxReasonableClimb = isHighPower
                ? DEFAULT_PLANE_MAX_CLIMB * 3  // ~60 blocks for upgraded planes
                : DEFAULT_PLANE_MAX_CLIMB;     // ~20 blocks for default planes

        // === Decision logic ===

        if (!scanner.isImmediateObstacleAhead()
                && scanner.getNearestObstacleDistance() > 30.0
                && heightDifference <= 0) {
            // No obstacle ahead, or terrain is well below us - fly normally
            currentAction = AvoidanceAction.NONE;
            steerYawOffset = 0.0f;
            climbPitch = 0.0f;
            return;
        }

        // Emergency: obstacle is very close (< 10 blocks)
        if (scanner.getNearestObstacleDistance() < 10.0) {
            currentAction = AvoidanceAction.EMERGENCY_TURN;
            // Turn toward the side with lower terrain
            if (leftTerrainHeight < rightTerrainHeight) {
                steerYawOffset = -EMERGENCY_STEER_ANGLE;
            } else {
                steerYawOffset = EMERGENCY_STEER_ANGLE;
            }
            // Also pitch up to gain some altitude during the emergency
            climbPitch = CLIMB_PITCH;
            return;
        }

        // Can we climb over it?
        if (heightDifference > 0 && heightDifference <= maxReasonableClimb) {
            // Terrain is above us but within our climb capability
            currentAction = AvoidanceAction.CLIMB;
            steerYawOffset = 0.0f;
            // Use aggressive pitch for powerful planes, gentler for default
            climbPitch = isHighPower ? AGGRESSIVE_CLIMB_PITCH : CLIMB_PITCH;
            return;
        }

        // Terrain is too high to climb over - steer around it
        // Pick the direction with lower terrain
        if (heightDifference > maxReasonableClimb) {
            if (leftTerrainHeight <= rightTerrainHeight) {
                currentAction = AvoidanceAction.STEER_LEFT;
                steerYawOffset = -LATERAL_STEER_ANGLE;
            } else {
                currentAction = AvoidanceAction.STEER_RIGHT;
                steerYawOffset = LATERAL_STEER_ANGLE;
            }
            // Mild climb while steering to maintain some altitude
            climbPitch = CLIMB_PITCH * 0.5f;
            return;
        }

        // Moderate terrain ahead (within ~30 blocks ahead) that we can handle
        // with a gradual climb
        if (scanner.isImmediateObstacleAhead()) {
            currentAction = AvoidanceAction.CLIMB;
            steerYawOffset = 0.0f;
            climbPitch = isHighPower ? AGGRESSIVE_CLIMB_PITCH : CLIMB_PITCH;
            return;
        }

        // Default: gentle climb to be safe
        currentAction = AvoidanceAction.CLIMB;
        steerYawOffset = 0.0f;
        climbPitch = CLIMB_PITCH * 0.5f;
    }

    // --- Accessors ---

    public AvoidanceAction getCurrentAction() {
        return currentAction;
    }

    /**
     * Returns the yaw offset (degrees) to apply.
     * Positive = turn right, Negative = turn left, 0 = no yaw correction.
     */
    public float getSteerYawOffset() {
        return steerYawOffset;
    }

    /**
     * Returns the desired climb pitch (degrees, negative = nose up).
     * 0 means no pitch correction needed.
     */
    public float getClimbPitch() {
        return climbPitch;
    }
}
