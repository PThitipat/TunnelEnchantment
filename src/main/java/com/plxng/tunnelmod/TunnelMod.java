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
 * Level I-III: เจาะบล็อกซ้าย-ขวาเพิ่มจากบล็อกที่ผู้เล่นแตกจริง
 * ผลลัพธ์ = 3 กว้าง x 1 สูง (แนวเดียวกับบล็อกที่ขุด ไม่ขยายขึ้น-ลง)
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

            // ระดับ 1-3 ตอนนี้ยังคงเป็น 3 กว้าง (ซ้าย 1 + กลาง 1(ของจริง) + ขวา 1) เสมอ
            // ถ้าอยากให้ level มีผลต่อความกว้าง (เช่น lvl1 = 1 กว้าง, lvl3 = 3 กว้าง)
            // ให้เปลี่ยน loop ด้านล่างเป็น for (int i = 1; i <= level; i++)
            BlockPos left = pos.offset(perpendicular);
            BlockPos right = pos.offset(perpendicular.getOpposite());

            PROCESSING.set(true);
            try {
                breakExtra(serverWorld, player, tool, left);
                // เช็คซ้ำว่าเครื่องมือยังไม่พัง/หมดสภาพก่อนขุดบล็อกที่ 2
                if (!tool.isEmpty()) {
                    breakExtra(serverWorld, player, tool, right);
                }
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
