package com.plxng.tunnelmod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.BlockState;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Optional;

/**
 * Tunnel enchantment (server-side only).
 * Level 1: ขุดปกติ (1x1)
 * Level 2: 3x1 (ซ้าย-ขวา เพิ่มฝั่งละ 1 บล็อก)
 * Level 3: 3x3 (เต็มหน้าตัด รวมมุมด้วย 8 บล็อกเสริมรอบบล็อกที่ขุดจริง)
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

            // facing ของผู้เล่น (แนวนอนเท่านั้น) ใช้ตัดสินว่าอุโมงค์ควรขยายไปแกนไหน
            Direction facing = player.getHorizontalFacing();

            // ถ้าผู้เล่นหันไปทาง east/west (แกน X) ให้อุโมงค์ขยายไปแนว Z (north/south)
            // ถ้าผู้เล่นหันไปทาง north/south (แกน Z) ให้อุโมงค์ขยายไปแนว X (east/west)
            Direction perpendicular = (facing.getAxis() == Direction.Axis.X)
                    ? Direction.NORTH
                    : Direction.EAST;

            // Scale ตาม level:
            //   level 1 -> ขุดปกติ (1x1, ไม่มีบล็อกเสริม)
            //   level 2 -> 3x1 (ซ้าย-ขวา เพิ่มฝั่งละ 1)
            //   level 3 -> 3x3 (เต็มหน้าตัด รวมมุมด้วย 8 บล็อกเสริม)
            PROCESSING.set(true);
            try {
                if (level >= 3) {
                    // เต็มหน้าตัด 3x3: dPerp = ซ้าย-ขวา, dVert = บน-ล่าง (แกน Y ตายตัว ไม่ขึ้นกับ facing)
                    for (int dVert = 1; dVert >= -1; dVert--) {
                        for (int dPerp = -1; dPerp <= 1; dPerp++) {
                            if (dPerp == 0 && dVert == 0) continue; // ข้ามบล็อกกลางที่แตกไปแล้ว
                            BlockPos target = pos.offset(perpendicular, dPerp).up(dVert);
                            if (tool.isEmpty()) break; // เครื่องมือพังกลางทาง หยุดเลย
                            breakExtra(serverWorld, player, tool, target);
                        }
                    }
                } else if (level == 2) {
                    BlockPos left = pos.offset(perpendicular);
                    BlockPos right = pos.offset(perpendicular.getOpposite());
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
