package immersive_aircraft.entity.autopilot;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Checks the surroundings of the aircraft before takeoff to find a clear
 * runway direction, or determines that takeoff is not possible.
 *
 * The checker scans 8 directions (N, NE, E, SE, S, SW, W, NW) looking
 * for a corridor that is wide enough and tall enough for the biplane to
 * accelerate and lift off.
 *
 * If no direction is clear, the autopilot should refuse to engage.
 */
public class TakeoffChecker {

    // How far ahead to check for runway clearance (blocks)
    private static final int RUNWAY_LENGTH = 30;
    // How wide the runway corridor should be (blocks on each side)
    private static final int RUNWAY_HALF_WIDTH = 2;
    // How tall the clearance corridor should be (blocks above ground)
    private static final int RUNWAY_HEIGHT = 5;

    /** Result of a takeoff check */
    public record TakeoffResult(boolean canTakeOff, float bestYaw, String blockReason) {}

    /**
     * Check if the aircraft can take off from its current position.
     *
     * @param level the world
     * @param pos   current aircraft position
     * @return a result indicating whether takeoff is possible and the best direction
     */
    public TakeoffResult check(Level level, Vec3 pos) {
        int baseX = (int) pos.x;
        int baseY = (int) pos.y;
        int baseZ = (int) pos.z;

        // 8 directions to check: N(0°), NE(45°), E(90°), SE(135°), S(180°), SW(225°), W(270°), NW(315°)
        float[] yawAngles = {180f, 135f, 90f, 45f, 0f, -45f, -90f, -135f};
        String[] dirNames = {"North", "NE", "East", "SE", "South", "SW", "West", "NW"};
        int[][] directions = {
                {0, -1},   // North (-Z)
                {1, -1},   // NE
                {1, 0},    // East (+X)
                {1, 1},    // SE
                {0, 1},    // South (+Z)
                {-1, 1},   // SW
                {-1, 0},   // West (-X)
                {-1, -1}   // NW
        };

        float bestYaw = 0.0f;
        int bestClearance = -1;

        for (int i = 0; i < directions.length; i++) {
            int clearDist = checkRunway(level, baseX, baseY, baseZ, directions[i][0], directions[i][1]);
            if (clearDist > bestClearance) {
                bestClearance = clearDist;
                bestYaw = yawAngles[i];
            }
        }

        if (bestClearance >= RUNWAY_LENGTH) {
            return new TakeoffResult(true, bestYaw, null);
        } else {
            return new TakeoffResult(false, 0.0f,
                    "No clear runway found! Best direction has only " + bestClearance + " blocks of clearance.");
        }
    }

    /**
     * Check how many blocks of clear runway exist in the given direction.
     *
     * @return number of clear blocks in this direction (up to RUNWAY_LENGTH)
     */
    private int checkRunway(Level level, int baseX, int baseY, int baseZ, int dx, int dz) {
        for (int dist = 1; dist <= RUNWAY_LENGTH; dist++) {
            int cx = baseX + dx * dist;
            int cz = baseZ + dz * dist;

            // Check a cross-section perpendicular to the runway direction
            for (int height = 0; height < RUNWAY_HEIGHT; height++) {
                for (int width = -RUNWAY_HALF_WIDTH; width <= RUNWAY_HALF_WIDTH; width++) {
                    // Perpendicular offset: if moving in X, offset in Z; if moving in Z, offset in X
                    int px = cx + (dz != 0 ? width : 0);
                    int pz = cz + (dx != 0 ? width : 0);
                    int py = baseY + height;

                    BlockPos checkPos = new BlockPos(px, py, pz);
                    BlockState state = level.getBlockState(checkPos);
                    if (!state.isAir() && state.getFluidState().isEmpty()) {
                        // Found an obstacle; this direction is clear up to (dist-1) blocks
                        return dist - 1;
                    }
                }
            }
        }
        return RUNWAY_LENGTH;
    }
}
