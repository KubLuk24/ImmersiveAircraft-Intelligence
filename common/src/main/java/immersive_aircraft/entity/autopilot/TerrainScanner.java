package immersive_aircraft.entity.autopilot;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Scans the terrain in multiple directions around the aircraft to build
 * an awareness map of nearby obstacles and ground heights.
 *
 * The scanner checks a fan of rays in front of, beside, and below the aircraft.
 * For each direction it records the maximum terrain height, which the
 * {@link ObstacleAvoidance} and {@link PathPlanner} systems use to decide
 * whether to climb over or steer around terrain.
 */
public class TerrainScanner {

    // How far ahead (in blocks) to scan for terrain
    private static final double FORWARD_SCAN_RANGE = 40.0;
    // How far to the side to scan (for lateral clearance checks)
    private static final double LATERAL_SCAN_RANGE = 30.0;
    // Vertical range to scan above and below the aircraft
    private static final int VERTICAL_SCAN_UP = 30;
    private static final int VERTICAL_SCAN_DOWN = 60;
    // Horizontal step size between scan points along a ray
    private static final double SCAN_STEP = 3.0;

    // --- Cached scan results (updated each tick) ---

    /** Highest terrain block in the forward scan cone */
    private int forwardMaxTerrainHeight = 0;
    /** Highest terrain block to the left of the aircraft */
    private int leftMaxTerrainHeight = 0;
    /** Highest terrain block to the right of the aircraft */
    private int rightMaxTerrainHeight = 0;
    /** Ground height directly below the aircraft */
    private int groundHeightBelow = 0;
    /** Whether there's an immediate obstacle within close range (< 16 blocks) */
    private boolean immediateObstacleAhead = false;
    /** Distance to the nearest obstacle ahead (blocks), or Double.MAX_VALUE if clear */
    private double nearestObstacleDistance = Double.MAX_VALUE;

    /**
     * Perform a full terrain scan around the aircraft.
     *
     * @param level    the world
     * @param pos      current aircraft position
     * @param yawDeg   current aircraft yaw in degrees
     */
    public void scan(Level level, Vec3 pos, float yawDeg) {
        // Convert yaw to radians; Minecraft yaw: 0 = south (+Z), 90 = west (-X)
        double yawRad = Math.toRadians(-yawDeg);
        double forwardX = Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);

        // 1) Scan directly below for ground height
        groundHeightBelow = scanGroundHeight(level, (int) pos.x, (int) pos.y, (int) pos.z);

        // 2) Forward scan: check terrain height in a cone ahead
        forwardMaxTerrainHeight = Integer.MIN_VALUE;
        immediateObstacleAhead = false;
        nearestObstacleDistance = Double.MAX_VALUE;

        // Scan 5 rays in a forward fan: -20°, -10°, 0°, +10°, +20° from heading
        for (int angleOffset = -20; angleOffset <= 20; angleOffset += 10) {
            double radOffset = Math.toRadians(angleOffset);
            double rayDirX = forwardX * Math.cos(radOffset) - forwardZ * Math.sin(radOffset);
            double rayDirZ = forwardX * Math.sin(radOffset) + forwardZ * Math.cos(radOffset);

            ScanRayResult result = scanRay(level, pos, rayDirX, rayDirZ, FORWARD_SCAN_RANGE);
            forwardMaxTerrainHeight = Math.max(forwardMaxTerrainHeight, result.maxTerrainHeight);

            if (result.hasObstacle && result.obstacleDistance < nearestObstacleDistance) {
                nearestObstacleDistance = result.obstacleDistance;
            }
            if (result.hasObstacle && result.obstacleDistance < 16.0) {
                immediateObstacleAhead = true;
            }
        }

        // 3) Left scan: check terrain height to the left
        leftMaxTerrainHeight = Integer.MIN_VALUE;
        for (int angleOffset = -80; angleOffset <= -30; angleOffset += 10) {
            double radOffset = Math.toRadians(angleOffset);
            double rayDirX = forwardX * Math.cos(radOffset) - forwardZ * Math.sin(radOffset);
            double rayDirZ = forwardX * Math.sin(radOffset) + forwardZ * Math.cos(radOffset);

            ScanRayResult result = scanRay(level, pos, rayDirX, rayDirZ, LATERAL_SCAN_RANGE);
            leftMaxTerrainHeight = Math.max(leftMaxTerrainHeight, result.maxTerrainHeight);
        }

        // 4) Right scan: check terrain height to the right
        rightMaxTerrainHeight = Integer.MIN_VALUE;
        for (int angleOffset = 30; angleOffset <= 80; angleOffset += 10) {
            double radOffset = Math.toRadians(angleOffset);
            double rayDirX = forwardX * Math.cos(radOffset) - forwardZ * Math.sin(radOffset);
            double rayDirZ = forwardX * Math.sin(radOffset) + forwardZ * Math.cos(radOffset);

            ScanRayResult result = scanRay(level, pos, rayDirX, rayDirZ, LATERAL_SCAN_RANGE);
            rightMaxTerrainHeight = Math.max(rightMaxTerrainHeight, result.maxTerrainHeight);
        }
    }

    /**
     * Casts a horizontal ray and finds the maximum terrain height along it.
     * Also detects if any block is at or above the aircraft's current Y position.
     */
    private ScanRayResult scanRay(Level level, Vec3 pos, double dirX, double dirZ, double range) {
        int maxHeight = Integer.MIN_VALUE;
        boolean hasObstacle = false;
        double obstacleDistance = Double.MAX_VALUE;

        for (double dist = SCAN_STEP; dist <= range; dist += SCAN_STEP) {
            int checkX = (int) (pos.x + dirX * dist);
            int checkZ = (int) (pos.z + dirZ * dist);

            // Find the highest non-air block in the column at this XZ position
            int columnTop = scanColumnTop(level, checkX, (int) pos.y, checkZ);
            maxHeight = Math.max(maxHeight, columnTop);

            // Check if terrain is at or above aircraft altitude (with some clearance)
            if (columnTop >= (int) pos.y - 3) {
                hasObstacle = true;
                if (dist < obstacleDistance) {
                    obstacleDistance = dist;
                }
            }
        }
        return new ScanRayResult(maxHeight, hasObstacle, obstacleDistance);
    }

    /**
     * Scans a column to find the highest solid block near the aircraft's altitude.
     * Only scans from a reasonable range below to above the aircraft to stay efficient.
     */
    private int scanColumnTop(Level level, int x, int aircraftY, int z) {
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos(x, 0, z);
        int maxSolidY = level.getMinBuildHeight();

        // Scan from above the aircraft downwards - first solid block is the top
        int startY = Math.max(level.getMinBuildHeight(), aircraftY - VERTICAL_SCAN_DOWN);
        int endY = Math.min(level.getMaxBuildHeight(), aircraftY + VERTICAL_SCAN_UP);

        for (int y = endY; y >= startY; y--) {
            probe.setY(y);
            BlockState state = level.getBlockState(probe);
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                return y;
            }
        }
        return maxSolidY;
    }

    /**
     * Scans directly below a position to find the ground height.
     */
    private int scanGroundHeight(Level level, int x, int startY, int z) {
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos(x, 0, z);
        for (int y = startY; y > level.getMinBuildHeight(); y--) {
            probe.setY(y);
            BlockState state = level.getBlockState(probe);
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                return y;
            }
        }
        return level.getMinBuildHeight();
    }

    // --- Accessors for scan results ---

    public int getForwardMaxTerrainHeight() {
        return forwardMaxTerrainHeight;
    }

    public int getLeftMaxTerrainHeight() {
        return leftMaxTerrainHeight;
    }

    public int getRightMaxTerrainHeight() {
        return rightMaxTerrainHeight;
    }

    public int getGroundHeightBelow() {
        return groundHeightBelow;
    }

    public boolean isImmediateObstacleAhead() {
        return immediateObstacleAhead;
    }

    public double getNearestObstacleDistance() {
        return nearestObstacleDistance;
    }

    /**
     * Internal record holding the results of a single ray scan.
     */
    private record ScanRayResult(int maxTerrainHeight, boolean hasObstacle, double obstacleDistance) {}
}
