package com.plxng.tunnelmod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.BlockState;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.Optional;

/**
 * Tunnel enchantment (server-side only).
 * Level 1: ขุดปกติ (1x1)
 * Level 2: 3x1 (แนวขวางเดียว ตามหน้าที่ผู้เล่นหัน)
 * Level 3: 3x3 (เต็มหน้าตัด ตามหน้าที่ผู้เล่นหัน + particle ring effect)
 *
 * หมายเหตุ: enchantment ตัวนี้ประกาศผ่าน datapack json
 * (data/tunnelmod/enchantment/tunnel.json) ตามระบบ data-driven enchantment
 * ของ 1.20.5+ ส่วน "พฤติกรรม" การขุดขยายเขียนเป็นโค้ด Java ตรงนี้
 * เพราะ vanilla effect components ไม่มีตัวไหนรองรับ custom block breaking แบบนี้
 */
public class TunnelMod implements ModInitializer {

    public static final String MOD_ID = "tunnelmod";

    public static final RegistryKey<Enchantment> TUNNEL_KEY =
            RegistryKey.of(RegistryKeys.ENCHANTMENT, Identifier.of(MOD_ID, "tunnel"));

    // สีของ particle ring (ฟ้าอมม่วงแบบคริสตัล/lapis ให้ดูพรีเมียม) ปรับได้ตามชอบ
    // NOTE: constructor ของ DustParticleEffect(Vector3f color, float scale) อาจต่างกันเล็กน้อย
    // ตาม yarn mappings version ถ้า compile ไม่ผ่านตรงนี้ ให้เช็ค autocomplete ใน IDE
    private static final DustParticleEffect TUNNEL_DUST =
            new DustParticleEffect(new Vector3f(0.45f, 0.75f, 1.0f), 1.4f);

    // กัน infinite recursion: world.breakBlock() ที่เราเรียกเองจะ trigger
    // PlayerBlockBreakEvents.AFTER ซ้ำอีกรอบ ต้องกันไม่ให้มันไปขุดต่อเนื่องไม่รู้จบ
    private static final ThreadLocal<Boolean> PROCESSING = ThreadLocal.withInitial(() -> false);

    @Override
    public void onInitialize() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClient) return;
            if (PROCESSING.get()) return;
            if (!(world instanceof ServerWorld serverWorld)) return;

            ItemStack tool = player.getMainHandStack();
            int level = getTunnelLevel(serverWorld, tool);
            if (level <= 0) return;

            // ใช้ทิศทางที่ผู้เล่น "หันหน้า" แบบเต็ม 6 ทิศ (รวมก้ม-เงย) แทนที่จะดูแค่แนวนอน
            // เพื่อให้ 3x3 หมุนตามจริงว่าผู้เล่นกำลังขุดไปทางไหน (หน้า, ก้มลง, เงยขึ้น)
            Vec3d look = player.getRotationVec(1.0f);
            Direction miningFacing = Direction.getFacing(look.x, look.y, look.z);

            // หา 2 แกนที่ตั้งฉากกับทิศที่ขุด เพื่อสร้างหน้าตัด 3x3 ในระนาบที่ถูกต้อง
            Direction axisA;
            Direction axisB;
            if (miningFacing.getAxis() == Direction.Axis.Y) {
                // ผู้เล่นกำลังขุดขึ้น/ลง -> หน้าตัดเป็นระนาบแนวนอน (X-Z)
                axisA = Direction.EAST;
                axisB = Direction.NORTH;
            } else if (miningFacing.getAxis() == Direction.Axis.X) {
                // ผู้เล่นหันไปทาง east/west -> หน้าตัดคือ Z (ซ้าย-ขวา) + Y (บน-ล่าง)
                axisA = Direction.NORTH;
                axisB = Direction.UP;
            } else {
                // ผู้เล่นหันไปทาง north/south -> หน้าตัดคือ X (ซ้าย-ขวา) + Y (บน-ล่าง)
                axisA = Direction.EAST;
                axisB = Direction.UP;
            }

            PROCESSING.set(true);
            try {
                if (level >= 3) {
                    // effect พิเศษเฉพาะ level 3: particle ring ที่หมุนตามหน้าตัดจริง (smooth, ไม่กระจายสุ่ม)
                    spawnTunnelRingEffect(serverWorld, pos, axisA, axisB);

                    // เต็มหน้าตัด 3x3 ตามแนวที่ผู้เล่นหันจริง ๆ
                    for (int dB = 1; dB >= -1; dB--) {
                        for (int dA = -1; dA <= 1; dA++) {
                            if (dA == 0 && dB == 0) continue; // ข้ามบล็อกกลางที่แตกไปแล้ว
                            if (tool.isEmpty()) break; // เครื่องมือพังกลางทาง หยุดเลย
                            BlockPos target = pos.offset(axisA, dA).offset(axisB, dB);
                            breakExtra(serverWorld, player, tool, target);
                        }
                    }
                } else if (level == 2) {
                    BlockPos left = pos.offset(axisA);
                    BlockPos right = pos.offset(axisA.getOpposite());
                    breakExtra(serverWorld, player, tool, left);
                    if (!tool.isEmpty()) {
                        breakExtra(serverWorld, player, tool, right);
                    }
                }
                // level 1: ไม่ทำอะไรเพิ่ม
            } finally {
                PROCESSING.set(false);
            }
        });
    }

    /**
     * particle ring effect แบบ smooth: วางจุด particle เป็นวงกลมเรขาคณิตแม่นยำ
     * (ไม่ใช้ random spread แบบเดิม) ตามระนาบของหน้าตัดที่กำลังขุด ดูเนียนและ
     * หมุนตามทิศทางที่ผู้เล่นหันจริง ๆ
     */
    private void spawnTunnelRingEffect(ServerWorld world, BlockPos pos, Direction axisA, Direction axisB) {
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;

        double ax = axisA.getOffsetX();
        double ay = axisA.getOffsetY();
        double az = axisA.getOffsetZ();
        double bx = axisB.getOffsetX();
        double by = axisB.getOffsetY();
        double bz = axisB.getOffsetZ();

        int pointsPerRing = 16;
        double[] radii = {0.4, 0.75, 1.1};

        for (double radius : radii) {
            for (int i = 0; i < pointsPerRing; i++) {
                double angle = (2 * Math.PI * i) / pointsPerRing;
                double offA = Math.cos(angle) * radius;
                double offB = Math.sin(angle) * radius;

                double px = cx + ax * offA + bx * offB;
                double py = cy + ay * offA + by * offB;
                double pz = cz + az * offA + bz * offB;

                // count=1, delta=0, speed=0 -> particle โผล่ตรงจุดที่คำนวณเป๊ะ ๆ
                // (ไม่กระจายสุ่มแบบ spread เดิม) ทำให้ได้วงแหวนที่เนียน คมชัด
                world.spawnParticles(TUNNEL_DUST, px, py, pz, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    private int getTunnelLevel(ServerWorld world, ItemStack tool) {
        Optional<RegistryEntry.Reference<Enchantment>> entry =
                world.getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(TUNNEL_KEY);
        if (entry.isEmpty()) return 0;
        return EnchantmentHelper.getLevel(entry.get(), tool);
    }

    private void breakExtra(ServerWorld world, PlayerEntity player, ItemStack tool, BlockPos pos) {
        BlockState state = world.getBlockState(pos);

        if (state.isAir()) return;
        if (state.getHardness(world, pos) < 0) return; // bedrock / unbreakable
        if (!tool.isSuitableFor(state)) return;         // เครื่องมือขุดบล็อกนี้ไม่ได้ (เช่น จอบขุด obsidian)
        if (tool.isEmpty()) return;
        if (tool.isDamageable() && tool.getDamage() >= tool.getMaxDamage() - 1) return; // กันเครื่องมือพังกลางทาง

        // true = drop item ตามปกติ (fortune/silk touch ที่ติดอยู่บนเครื่องมือจะมีผลตามปกติ
        // เพราะ breakBlock คำนวณ loot จาก state + player context ให้)
        world.breakBlock(pos, true, player);

        if (tool.isDamageable()) {
            // NOTE: ItemStack#damage(...) เปลี่ยน signature มาหลายรอบข้าม mapping version
            // (1.20.5+ บางรุ่นต้องการ ServerWorld + ServerPlayerEntity + breakCallback)
            // ถ้า compile ไม่ผ่านตรงบรรทัดนี้ ให้เช็ค signature จริงจาก yarn mappings
            // ที่ใช้อยู่ผ่าน IDE autocomplete แล้วปรับ call ให้ตรง
            tool.damage(1, player, EquipmentSlot.MAINHAND);
        }
    }
}
