# Tunnel Enchantment Mod (Fabric, 1.21.1)

Custom enchantment "Tunnel" (level I-III) แบบ server-side:
เวลาผู้เล่นขุดบล็อก จะขุดบล็อกซ้าย-ขวาเพิ่มให้ = **3 กว้าง x 1 สูง**
(อยู่ระดับความสูงเดียวกับบล็อกที่ขุดจริง ไม่ขยายขึ้น-ลง)

## โครงสร้างสำคัญ

- `src/main/resources/data/tunnelmod/enchantment/tunnel.json`
  ประกาศ enchantment แบบ data-driven (ระบบใหม่ของ 1.20.5+)
  - `max_level: 3`
  - ใช้กับไอเทมที่อยู่ใน tag `#minecraft:enchantable/mining` (จอบ/เสียม/ขวานทั่วไป)
  - anvil cost, min/max cost ปรับได้ตามต้องการ

- `src/main/java/com/plxng/tunnelmod/TunnelMod.java`
  โค้ดจริงที่ทำให้เกิดพฤติกรรมขุด 3x1 เพราะ vanilla enchantment effect
  components ไม่มีตัวไหนรองรับ custom block-breaking แบบนี้ ต้องเขียนเอง

## Build

ต้องมี JDK 21 และเน็ตที่เข้าถึง `maven.fabricmc.net` / `maven.minecraftforge.net` /
Maven Central ได้ (ใช้ตอน gradle ดึง mappings/fabric-api)

```bash
./gradlew build
```

jar ที่ได้จะอยู่ที่ `build/libs/tunnelmod-1.0.0.jar`

หากยังไม่มี gradle wrapper jar ในโปรเจกต์นี้ ให้รันที่เครื่องที่มี Gradle
ติดตั้งอยู่ก่อนครั้งแรกด้วย `gradle wrapper` เพื่อ generate `gradlew`/`gradlew.bat`

## ติดตั้งบน server (MC-Hardcore)

1. เอา jar ที่ build ได้ ไปวางในโฟลเดอร์ `mods` ของ server
   (จากที่เคยคุยกันไว้ คือ `C:\Users\Plxng\Desktop\MCHardCoreServer\mods`)
2. ต้องมี **Fabric API** เวอร์ชันที่ตรงกับ `fabric_version` ใน `gradle.properties`
   วางไว้ใน mods folder ด้วย (mod นี้ depend fabric-api)
3. Server-side only: ผู้เล่นไม่จำเป็นต้องลง mod นี้ที่ client
   (ใช้ `PlayerBlockBreakEvents.AFTER` ซึ่งทำงานฝั่ง server ล้วน)
4. รีสตาร์ท server แล้วใช้ `/enchant` หรือ enchanting table/anvil ปกติ
   เพื่อใส่ enchant "Tunnel" ลงบนเครื่องมือ

## จุดที่ควรทดสอบ/ปรับก่อนใช้จริง

- **`ItemStack#damage(...)` signature**: ในโค้ดใช้
  `tool.damage(1, player, EquipmentSlot.MAINHAND)` แต่ signature ของ method นี้
  เปลี่ยนไปมาหลาย mapping version ช่วง 1.20.5-1.21.x ถ้า compile ไม่ผ่าน
  ให้เปิด IDE (IntelliJ) ดู autocomplete ของ method นี้ตาม yarn mappings จริง
  ที่ gradle sync มาให้ แล้วปรับ call ให้ตรง
- **ทิศทางขยาย (perpendicular direction)**: ปัจจุบันคำนวณจาก
  `player.getHorizontalFacing()` แบบง่าย ถ้าอยากให้แม่นตามมุมกล้องจริง
  (เช่นตอนก้มขุดเฉียง ๆ) อาจต้องปรับ logic เพิ่ม
- **Fortune/Silk Touch**: ใช้ `world.breakBlock(pos, true, player)` ซึ่งคำนวณ
  drop ตาม context ของ player อยู่แล้ว ควรทำงานร่วมกับ enchant อื่นได้ปกติ
  แต่ควรทดสอบจริงเพื่อความชัวร์
- **max_level กับความกว้าง**: ตอนนี้ level 1-3 ให้ผล 3 กว้างเท่ากันหมด
  (เพราะโจทย์คือ "Tunnel III = 3x1" คงที่) ถ้าอยากให้ level น้อยกว่าขุดแคบกว่า
  มีคอมเมนต์ในโค้ดบอกจุดที่ต้องแก้ไว้แล้ว
