package eu.ha3.presencefootsteps.world;

import java.util.List;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.block.BlockState;
import net.minecraft.block.Material;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction.Axis;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import eu.ha3.presencefootsteps.sound.Isolator;
import eu.ha3.presencefootsteps.sound.Options;
import eu.ha3.presencefootsteps.sound.State;

public class PFSolver implements Solver {

    private static final Logger logger = LogManager.getLogger("PFSolver");

    private static final double TRAP_DOOR_OFFSET = 0.1;

    private final Isolator isolator;

    public PFSolver(Isolator isolator) {
        this.isolator = isolator;
    }

    @Override
    public void playAssociation(Entity ply, Association assos, State eventType) {
        if (assos.isNotEmitter()) {
            return;
        }

        assos = assos.at(ply);

        if (assos.hasAssociation()) {
            isolator.getAcoustics().playAcoustic(assos, eventType, Options.EMPTY);
        } else {
            isolator.getStepPlayer().playStep(assos);
        }
    }

    @Override
    public Association findAssociation(Entity ply, double verticalOffsetAsMinus, boolean isRightFoot) {

        double rot = Math.toRadians(MathHelper.wrapDegrees(ply.yaw));

        Vec3d pos = ply.getPos();

        float feetDistanceToCenter = 0.2f * (isRightFoot ? -1 : 1);

        return findAssociation(ply, new BlockPos(
            pos.x + Math.cos(rot) * feetDistanceToCenter,
            ply.getBoundingBox().getMin(Axis.Y) - TRAP_DOOR_OFFSET - verticalOffsetAsMinus,
            pos.z + Math.sin(rot) * feetDistanceToCenter
        ));
    }

    private Association findAssociation(Entity player, BlockPos pos) {

        if (!(player instanceof OtherClientPlayerEntity)) {
            Vec3d vel = player.getVelocity();

            if ((vel.x != 0 || vel.y != 0 || vel.z != 0) && Math.abs(vel.y) < 0.02) {
                return Association.NOT_EMITTER; // Don't play sounds on every tiny bounce
            }
        }

        Association worked = findAssociation(player.world, pos);

        // If it didn't work, the player has walked over the air on the border of a block.
        // ------ ------ --> z
        // | o | < player is here
        // wool | air |
        // ------ ------
        // |
        // V z
        if (!worked.isNull()) {
            return worked;
        }

        // Create a trigo. mark contained inside the block the player is over
        double xdang = (player.getX() - pos.getX()) * 2 - 1;
        double zdang = (player.getZ() - pos.getZ()) * 2 - 1;
        // -1 0 1
        // ------- -1
        // | o |
        // | + | 0 --> x
        // | |
        // ------- 1
        // |
        // V z

        // If the player is at the edge of that
        if (Math.max(Math.abs(xdang), Math.abs(zdang)) <= 0.2f) {
            return worked;
        }
        // Find the maximum absolute value of X or Z
        boolean isXdangMax = Math.abs(xdang) > Math.abs(zdang);
        // --------------------- ^ maxofZ-
        // | . . |
        // | . . |
        // | o . . |
        // | . . |
        // | . |
        // < maxofX- maxofX+ >
        // Take the maximum border to produce the sound
        if (isXdangMax) { // If we are in the positive border, add 1, else subtract 1
            worked = findAssociation(player.world, pos.east(xdang > 0 ? 1 : -1));
        } else {
            worked = findAssociation(player.world, pos.south(zdang > 0 ? 1 : -1));
        }

        // If that didn't work, then maybe the footstep hit in the
        // direction of walking
        // Try with the other closest block
        if (!worked.isNull()) {
            return worked;
        }

        // Take the maximum direction and try with the orthogonal direction of it
        if (isXdangMax) {
            return findAssociation(player.world, pos.north(zdang > 0 ? 1 : -1));
        }

        return findAssociation(player.world, pos.east(xdang > 0 ? 1 : -1));
    }

    private String findForGolem(World world, BlockPos pos, String substrate) {
        List<Entity> golems = world.getEntities(Entity.class, new Box(pos), e -> !(e instanceof PlayerEntity));

        if (!golems.isEmpty()) {
            String golem = isolator.getGolemMap().getAssociation(golems.get(0).getType(), substrate);

            if (Emitter.isEmitter(golem)) {
                logger.debug("Golem detected: " + golem);

                return golem;
            }
        }

        return Emitter.UNASSIGNED;
    }

    private Association findAssociation(World world, BlockPos pos) {

        BlockState in = world.getBlockState(pos);

        BlockPos up = pos.up();
        BlockState above = world.getBlockState(up);
        // Try to see if the block above is a carpet...

        String association = findForGolem(world, up, "carpet");

        if (!Emitter.isEmitter(association)) {
            association = isolator.getBlockMap().getAssociation(above, "carpet");
        }

        if (Emitter.isEmitter(association)) {
            logger.debug("Carpet detected: " + association);
            pos = up;
            in = above;
        } else {
            // This condition implies that if the carpet is NOT_EMITTER, solving will
            // CONTINUE with the actual block surface the player is walking on
            Material mat = in.getMaterial();

            if (mat == Material.AIR || mat == Material.PART) {
                BlockPos down = pos.down();
                BlockState below = world.getBlockState(down);

                association = isolator.getBlockMap().getAssociation(below, "bigger");

                if (Emitter.isResult(association)) {
                    logger.debug("Fence detected: " + association);
                    pos = down;
                    in = below;
                }
            }

            if (!Emitter.isResult(association)) {
                association = findForGolem(world, pos, Lookup.EMPTY_SUBSTRATE);

                if (!Emitter.isEmitter(association)) {
                    association = isolator.getBlockMap().getAssociation(in, Lookup.EMPTY_SUBSTRATE);
                }
            }

            if (Emitter.isEmitter(association)) {
                // This condition implies that foliage over a NOT_EMITTER block CANNOT PLAY
                // This block most not be executed if the association is a carpet
                String foliage = isolator.getBlockMap().getAssociation(above, "foliage");

                if (Emitter.isEmitter(foliage)) {
                    logger.debug("Foliage detected: " + foliage);
                    association += "," + foliage;
                }
            }
        }

        // Player has stepped on a non-emitter block as defined in the blockmap
        if (Emitter.isNonEmitter(association)) {
            return Association.NOT_EMITTER;
        }

        if (Emitter.isResult(association)) {
            return new Association(in, pos).associated().with(association);
        }

        if (in.isAir()) {
            return Association.NOT_EMITTER;
        }

        BlockSoundGroup sounds = in.getSoundGroup();
        String substrate = String.format(Locale.ENGLISH, "%.2f_%.2f", sounds.volume, sounds.pitch);

        // Check for primitive in register
        String primitive = isolator.getPrimitiveMap().getAssociation(sounds, substrate);

        if (Emitter.isNonEmitter(primitive)) {
            return Association.NOT_EMITTER;
        }

        return new Association(in, pos).with(primitive);
    }

    @Override
    public boolean playStoppingConditions(Entity ply) {
        if (!hasStoppingConditions(ply)) {
            return false;
        }

        float volume = Math.min(1, (float) ply.getVelocity().length() * 0.35F);
        Options options = Options.singular("gliding_volume", volume);
        State state = ply.isSubmergedInWater() ? State.SWIM : State.WALK;

        isolator.getAcoustics().playAcoustic(ply, "_SWIM", state, options);

        return true;
    }

    @Override
    public boolean hasStoppingConditions(Entity ply) {
        return ply.isSubmergedInWater();
    }

    @Override
    public Association findAssociation(World world, BlockPos pos, String strategy) {
        if (!"find_messy_foliage".equals(strategy)) {
            return Association.NOT_EMITTER;
        }

        BlockState above = world.getBlockState(pos.up());

        String foliage = isolator.getBlockMap().getAssociation(above, "foliage");

        if (!Emitter.isEmitter(foliage)) {
            return Association.NOT_EMITTER;
        }

        // we discard the normal block association, and mark the foliage as detected
        if ("MESSY_GROUND".equals(isolator.getBlockMap().getAssociation(above, "messy"))) {
            return new Association().associated().with(foliage);
        }

        return Association.NOT_EMITTER;
    }
}
