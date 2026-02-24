package immersive_aircraft.entity.autopilot;

import immersive_aircraft.entity.autopilot.ObstacleAvoidance.AvoidanceAction;
import net.minecraft.world.phys.Vec3;

/**
 * High-level path planner for the autopilot system.
 *
 * Combines destination navigation with terrain awareness to compute
 * the final target yaw, pitch, and throttle each tick.
 *
 * The planner operates in three phases:
 * 1. CRUISE   - Flying toward the destination at a safe altitude
 * 2. CIRCLING - Orbiting the destination once within arrival radius
 * 3. AVOIDING - Actively detouring around or climbing over terrain
 *
 * The key improvement over the old system is that the planner doesn't
 * just react to immediate obstacles. It considers terrain height far
 * ahead and adjusts the flight plan proactively - either by routing
 * around mountains or by climbing well in advance.
 */
public class PathPlanner {

    /** Flight phases */
    public enum FlightPhase {
        CRUISE,
        CIRCLING,
        AVOIDING
    }

    // Navigation constants
    private static final double ARRIVAL_RADIUS = 20.0;
    private static final double CIRCLE_RADIUS = 30.0;
    private static final double CRUISE_ALTITUDE_OFFSET = 15.0;

    // --- State ---
    private FlightPhase phase = FlightPhase.CRUISE;
    private float circleAngle = 0.0f;

    // --- Computed outputs each tick ---
    private float targetYaw;
    private float targetPitch;
    private float targetThrottle;

    /**
     * Plan the next tick's flight controls.
     *
     * @param pos         current aircraft position
     * @param destX       destination X coordinate
     * @param destY       destination Y coordinate
     * @param destZ       destination Z coordinate
     * @param currentYaw  aircraft's current yaw in degrees
     * @param scanner     terrain scanner with fresh results
     * @param avoidance   obstacle avoidance with evaluated results
     */
    public void plan(Vec3 pos, int destX, int destY, int destZ,
                     float currentYaw,
                     TerrainScanner scanner,
                     ObstacleAvoidance avoidance) {

        Vec3 dest = new Vec3(destX + 0.5, destY, destZ + 0.5);
        double horizontalDist = horizontalDistance(pos, dest);

        // Check if we've arrived at destination
        if (horizontalDist < ARRIVAL_RADIUS && phase != FlightPhase.CIRCLING) {
            phase = FlightPhase.CIRCLING;
        }

        // ===== Phase-specific navigation =====

        if (phase == FlightPhase.CIRCLING) {
            planCircling(pos, dest);
        } else {
            planCruise(pos, dest, horizontalDist, scanner, avoidance);
        }
    }

    /**
     * Plan circling behavior around the destination.
     */
    private void planCircling(Vec3 pos, Vec3 dest) {
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
    }

    /**
     * Plan cruising toward the destination, incorporating obstacle avoidance.
     *
     * This is where the key logic lives: we first compute the "ideal" heading
     * and altitude to reach the destination, then overlay obstacle avoidance
     * corrections.
     */
    private void planCruise(Vec3 pos, Vec3 dest, double horizontalDist,
                            TerrainScanner scanner, ObstacleAvoidance avoidance) {
        // --- Step 1: Compute the safe cruise altitude ---
        // Base: fly at destination altitude + offset, or current altitude if higher
        double desiredAltitude = dest.y + CRUISE_ALTITUDE_OFFSET;

        // Also ensure we're above local terrain with margin
        int groundBelow = scanner.getGroundHeightBelow();
        desiredAltitude = Math.max(desiredAltitude, groundBelow + CRUISE_ALTITUDE_OFFSET + 10);

        // If terrain ahead is high, raise cruise altitude to clear it
        int terrainAhead = scanner.getForwardMaxTerrainHeight();
        if (terrainAhead > Integer.MIN_VALUE) {
            double terrainClearAlt = terrainAhead + CRUISE_ALTITUDE_OFFSET;
            desiredAltitude = Math.max(desiredAltitude, terrainClearAlt);
        }

        // Don't descend while far from destination (maintain safe altitude)
        if (horizontalDist > 50) {
            desiredAltitude = Math.max(desiredAltitude, pos.y);
        }

        // --- Step 2: Compute heading toward destination ---
        double dx = dest.x - pos.x;
        double dz = dest.z - pos.z;
        double dy = desiredAltitude - pos.y;

        float idealYaw = (float) (-Math.toDegrees(Math.atan2(dx, dz)));
        float idealPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDist)));

        // --- Step 3: Apply obstacle avoidance corrections ---
        AvoidanceAction action = avoidance.getCurrentAction();

        switch (action) {
            case NONE:
                // No obstacle, fly the ideal path
                targetYaw = idealYaw;
                targetPitch = idealPitch;
                targetThrottle = 1.0f;
                phase = FlightPhase.CRUISE;
                break;

            case CLIMB:
                // Obstacle ahead but climbable - maintain heading, pitch up
                targetYaw = idealYaw;
                targetPitch = avoidance.getClimbPitch();
                targetThrottle = 1.0f;
                phase = FlightPhase.AVOIDING;
                break;

            case STEER_LEFT:
            case STEER_RIGHT:
                // Obstacle too tall to climb - steer around it
                // Apply yaw offset to current heading (not destination heading)
                // so we actually turn away from the mountain
                targetYaw = idealYaw + avoidance.getSteerYawOffset();
                targetPitch = avoidance.getClimbPitch();  // mild climb while steering
                targetThrottle = 1.0f;
                phase = FlightPhase.AVOIDING;
                break;

            case EMERGENCY_TURN:
                // Very close obstacle - hard turn + climb
                targetYaw = idealYaw + avoidance.getSteerYawOffset();
                targetPitch = avoidance.getClimbPitch();
                targetThrottle = 1.0f;
                phase = FlightPhase.AVOIDING;
                break;
        }
    }

    // --- Accessors ---

    public float getTargetYaw() {
        return targetYaw;
    }

    public float getTargetPitch() {
        return targetPitch;
    }

    public float getTargetThrottle() {
        return targetThrottle;
    }

    public FlightPhase getPhase() {
        return phase;
    }

    public float getCircleAngle() {
        return circleAngle;
    }

    public void setCircleAngle(float angle) {
        this.circleAngle = angle;
    }

    /**
     * Reset the planner state (e.g., when autopilot is disabled or destination changes).
     */
    public void reset() {
        phase = FlightPhase.CRUISE;
        circleAngle = 0.0f;
    }

    /**
     * Compute horizontal distance between two points (ignoring Y).
     */
    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
