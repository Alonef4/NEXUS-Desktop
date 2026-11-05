# NEXUS Desktop — Prototype 0.1.0

โปรเจกต์ Android สำหรับพัฒนาบนโทรศัพท์ โดยใช้ GitHub Actions สร้าง APK บนคลาวด์

## สิ่งที่ทำได้ในรุ่นนี้
- แสดงข้อมูลรุ่นโทรศัพท์และ Android API
- ตรวจจับ Display ที่ Android รายงาน
- แสดง Display ID, ชื่อจอ, สถานะ, ขนาด, Refresh Rate และ supported modes
- แสดงธง FLAG_PRESENTATION และ FLAG_SECURE
- สแกนใหม่เมื่อกดปุ่ม หรือเมื่อระบบแจ้งว่าจอเปลี่ยน

## ข้อจำกัด
รุ่นนี้เป็นเครื่องมือตรวจสอบ Display เท่านั้น ยังไม่มีการย้าย Task, สร้าง Desktop Window Manager หรือเปลี่ยน Refresh Rate และไม่พยายามข้าม DRM/FLAG_SECURE

## วิธีสร้าง APK โดยใช้โทรศัพท์อย่างเดียว
1. สร้าง repository ใหม่บน GitHub ชื่อ `NEXUS-Desktop`
2. แตก ZIP นี้ แล้วอัปโหลดไฟล์และโฟลเดอร์ทั้งหมดเข้า repository โดยให้ `.github/workflows/build-apk.yml` อยู่ในตำแหน่งเดิม
3. เปิดแท็บ **Actions** ของ repository
4. เลือก **Build NEXUS Desktop APK** แล้วกด **Run workflow** (หรือรอให้ build อัตโนมัติหลัง push)
5. เมื่อเสร็จ เปิดรายการ workflow ล่าสุด แล้วดาวน์โหลด Artifact ชื่อ `NEXUS-Desktop-debug`
6. แตกไฟล์ Artifact จะได้ `app-debug.apk` นำไปติดตั้งบน vivo X200 Pro

## สิ่งที่ต้องส่งกลับมา
เปิด NEXUS Desktop ขณะต่อจอ HDMI แล้วกด “สแกนจออีกครั้ง” จากนั้นส่งภาพหน้าจอผลลัพธ์กลับมา โดยเฉพาะส่วนจำนวนจอ, Display ID, ขนาด และ Refresh Rate

## Build settings
- minSdk 34 (Android 14)
- compileSdk 35
- Kotlin 2.0.21
- Android Gradle Plugin 8.7.3
- Gradle 8.9
- JDK 17
