# 경험치 백업 기능 개발 계획

**작성일:** 2025-11-22  
**우선순위:** MEDIUM  
**예상 작업 시간:** 1-2일

---

## 📋 개요

현재 InventoryLogger는 인벤토리(아이템), 엔더 상자, Curios, 배낭을 백업하지만 **플레이어 경험치(Experience)**는 백업하지 않습니다. 이 문서는 경험치 백업 기능 추가를 위한 상세 검토 및 개발 계획입니다.

---

## 🎯 요구사항

### 기능 목표
플레이어의 경험치를 인벤토리 백업과 함께 자동으로 저장하고 복원할 수 있어야 합니다.

### 사용 시나리오

#### 시나리오 1: 사망 시 경험치 복구
```
1. 플레이어가 레벨 30 + 50% 진행도 상태에서 사망
2. 시스템이 사망 시점 인벤토리 + 경험치 백업
3. 관리자가 `/inventory set Player 2025-11-22-10-30-45-death` 실행
4. ✅ 인벤토리와 함께 경험치도 레벨 30 + 50%로 복원
```

#### 시나리오 2: 버그 복구
```
1. 서버 버그로 플레이어 경험치가 0으로 초기화됨
2. 관리자가 최근 백업 확인: 레벨 42 + 75%
3. `/inventory set Player 2025-11-22-09-00-00` 실행
4. ✅ 경험치 레벨 42 + 75%로 복원
```

#### 시나리오 3: 미리보기에서 경험치 확인
```
1. `/inventory view Player 2025-11-22-10-30-45`
2. GUI 하단에 "경험치: Lv.30 (50%)" 표시
3. ✅ 백업 시점의 경험치 정보 확인 가능
```

---

## 🔍 마인크래프트 경험치 시스템 분석

### 경험치 구성 요소

마인크래프트 플레이어 경험치는 **3가지 값**으로 구성됩니다:

```java
// net.minecraft.world.entity.player.Player

// 1. 경험치 레벨 (Experience Level)
public int experienceLevel;       // 예: 30 (레벨 30)

// 2. 현재 레벨 진행도 (Experience Progress)
public float experienceProgress;  // 예: 0.5f (50% 진행)

// 3. 총 경험치 포인트 (Total Experience)
public int totalExperience;       // 예: 825 (누적 경험치)
```

### 경험치 계산 방식

#### **레벨별 필요 경험치**
```java
// Level 0-16: 2*level + 7
// Level 17-31: 5*level - 38
// Level 32+: 9*level - 158

// 예시:
레벨 0→1: 7 XP
레벨 10→11: 27 XP
레벨 20→21: 62 XP
레벨 30→31: 112 XP
레벨 50→51: 292 XP
```

#### **총 경험치 계산**
```java
레벨 30 도달에 필요한 총 XP:
= 7+9+11+...+62+...+112
= 1,395 XP

레벨 30 + 50% 진행:
= 1,395 + (112 * 0.5)
= 1,395 + 56
= 1,451 XP
```

### 경험치 API

```java
ServerPlayer player = ...;

// 📖 읽기
int level = player.experienceLevel;
float progress = player.experienceProgress;
int total = player.totalExperience;

// ✏️ 쓰기 (주의: 동기화 필요)
player.experienceLevel = 30;
player.experienceProgress = 0.5f;
player.totalExperience = 1451;

// 🔄 클라이언트 동기화 (필수!)
player.refreshDisplayName(); // 이름표 업데이트
// 또는
player.connection.send(new ClientboundSetExperiencePacket(
    player.experienceProgress,
    player.totalExperience, 
    player.experienceLevel
));
```

---

## 🏗️ 설계

### 1. 데이터 구조 확장

#### **InventoryData.java 수정**

```java
public class InventoryData implements Serializable {
    List<ItemData> data = new ArrayList<>();
    Map<String, String> backpackSnapshots = new HashMap<>();
    
    // ✨ 신규 추가: 경험치 데이터
    private ExperienceData experienceData;
    
    // Getter/Setter
    public ExperienceData getExperienceData() {
        return experienceData;
    }
    
    public void setExperienceData(ExperienceData experienceData) {
        this.experienceData = experienceData;
    }
}
```

#### **ExperienceData.java (신규 클래스)**

```java
package com.pocky.invbackups.data;

import java.io.Serializable;
import java.util.Objects;

/**
 * Stores player experience data for backup/restore
 */
public class ExperienceData implements Serializable {
    
    /**
     * Experience level (green number above hotbar)
     */
    private int experienceLevel;
    
    /**
     * Progress towards next level (0.0 to 1.0)
     */
    private float experienceProgress;
    
    /**
     * Total experience points accumulated
     */
    private int totalExperience;
    
    public ExperienceData() {
        this(0, 0.0f, 0);
    }
    
    public ExperienceData(int level, float progress, int total) {
        this.experienceLevel = level;
        this.experienceProgress = Math.max(0.0f, Math.min(1.0f, progress));
        this.totalExperience = total;
    }
    
    /**
     * Create from ServerPlayer
     */
    public static ExperienceData fromPlayer(net.minecraft.server.level.ServerPlayer player) {
        return new ExperienceData(
            player.experienceLevel,
            player.experienceProgress,
            player.totalExperience
        );
    }
    
    /**
     * Apply to ServerPlayer
     */
    public void applyToPlayer(net.minecraft.server.level.ServerPlayer player) {
        player.experienceLevel = this.experienceLevel;
        player.experienceProgress = this.experienceProgress;
        player.totalExperience = this.totalExperience;
        
        // Sync to client
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetExperiencePacket(
            player.experienceProgress,
            player.totalExperience,
            player.experienceLevel
        ));
    }
    
    /**
     * Check if player has any experience
     */
    public boolean hasExperience() {
        return experienceLevel > 0 || experienceProgress > 0 || totalExperience > 0;
    }
    
    /**
     * Get formatted display string
     */
    public String getDisplayString() {
        if (!hasExperience()) {
            return "Lv.0 (0%)";
        }
        int progressPercent = (int)(experienceProgress * 100);
        return String.format("Lv.%d (%d%%)", experienceLevel, progressPercent);
    }
    
    // Getters and Setters
    public int getExperienceLevel() {
        return experienceLevel;
    }
    
    public void setExperienceLevel(int experienceLevel) {
        this.experienceLevel = experienceLevel;
    }
    
    public float getExperienceProgress() {
        return experienceProgress;
    }
    
    public void setExperienceProgress(float experienceProgress) {
        this.experienceProgress = experienceProgress;
    }
    
    public int getTotalExperience() {
        return totalExperience;
    }
    
    public void setTotalExperience(int totalExperience) {
        this.totalExperience = totalExperience;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExperienceData that = (ExperienceData) o;
        return experienceLevel == that.experienceLevel &&
               Float.compare(that.experienceProgress, experienceProgress) == 0 &&
               totalExperience == that.totalExperience;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(experienceLevel, experienceProgress, totalExperience);
    }
    
    @Override
    public String toString() {
        return String.format("ExperienceData{level=%d, progress=%.2f, total=%d}", 
            experienceLevel, experienceProgress, totalExperience);
    }
}
```

### 2. 백업 로직 수정

#### **InventoryData.encode() 수정**

```java
public static InventoryData encode(HolderLookup.Provider registryAccess, 
                                   Map<Integer, ItemStack> map,
                                   ServerPlayer player) { // ✨ player 매개변수 추가
    List<ItemData> result = new ArrayList<>();
    Map<String, String> backpackSnapshots = new HashMap<>();

    map.forEach((i, s) -> {
        if (!s.isEmpty()) {
            CompoundTag tag = (CompoundTag) s.save(registryAccess);
            result.add(new ItemData(i, tag.toString()));
            
            if (SophisticatedBackpacksHelper.isSophisticatedBackpack(s)) {
                UUID backpackUuid = SophisticatedBackpacksHelper.getBackpackUuid(s);
                if (backpackUuid != null) {
                    CompoundTag snapshot = SophisticatedBackpacksHelper.getBackpackSnapshot(backpackUuid);
                    if (snapshot != null && !snapshot.isEmpty()) {
                        backpackSnapshots.put(backpackUuid.toString(), snapshot.toString());
                    }
                }
            }
        }
    });

    InventoryData data = new InventoryData();
    data.setData(result);
    data.setBackpackSnapshots(backpackSnapshots);
    
    // ✨ 경험치 데이터 추가
    if (player != null) {
        data.setExperienceData(ExperienceData.fromPlayer(player));
    }

    return data;
}
```

#### **모든 백업 호출 수정**

```java
// PlayerDeadEvent.java
private void saveInventory(ServerPlayer player, boolean isPlayerDead) {
    if (InventoryUtil.isEmpty(player)) return;
    
    // ✨ player 매개변수 전달
    InventoryData.encode(
        player.level().registryAccess(), 
        InventoryUtil.collectInventory(player),
        player  // ← 추가
    ).save(player.getUUID(), isPlayerDead);
}

// 다른 이벤트들도 동일하게 수정
// - PlayerConnectionEvent.java
// - ContainerCloseEvent.java
// - TickHandler.java
```

### 3. 복원 로직 수정

#### **InventoryCommand.java - setInventory() 수정**

```java
private int setInventory(CommandSourceStack source, String targetName, String date) throws CommandSyntaxException {
    // ... 기존 코드 ...
    
    // 인벤토리 복원
    Inventory inventory = invData.getInventory(targetPlayer);
    targetPlayer.getInventory().replaceWith(inventory);
    
    // ✨ 경험치 복원
    ExperienceData expData = invData.getExperienceData();
    if (expData != null && expData.hasExperience()) {
        expData.applyToPlayer(targetPlayer);
        
        ChatUI.showSuccess(executor, 
            Component.translatable("invbackups.success.experience_restored",
                expData.getDisplayString()).getString());
    }
    
    // ... 나머지 코드 ...
}
```

### 4. GUI 표시 개선

#### **미리보기 GUI에 경험치 정보 표시**

```java
// ChestCopyableMenu 생성 시
private static void openBackupPreview(ServerPlayer viewer, 
                                      PlayerResolver.ResolvedPlayer target, 
                                      String backupName) {
    // ... 기존 코드 ...
    
    // ✨ 경험치 정보 아이템 추가 (slot 52)
    ExperienceData expData = invData.getExperienceData();
    if (expData != null && expData.hasExperience()) {
        ItemStack expInfo = new ItemStack(Items.EXPERIENCE_BOTTLE);
        expInfo.set(DataComponents.CUSTOM_NAME,
            Component.literal("💫 " + TranslationHelper.translate(viewer, "invbackups.gui.experience"))
                .withStyle(ChatFormatting.AQUA));
        
        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal(TranslationHelper.translate(viewer, "invbackups.gui.experience.level", 
                expData.getExperienceLevel()))
            .withStyle(ChatFormatting.GREEN));
        lore.add(Component.literal(TranslationHelper.translate(viewer, "invbackups.gui.experience.progress",
                (int)(expData.getExperienceProgress() * 100)))
            .withStyle(ChatFormatting.YELLOW));
        lore.add(Component.literal(TranslationHelper.translate(viewer, "invbackups.gui.experience.total",
                expData.getTotalExperience()))
            .withStyle(ChatFormatting.GRAY));
        
        expInfo.set(DataComponents.LORE, new ItemLore(lore));
        chestContainer.setItem(52, expInfo);
    }
    
    // ... 나머지 코드 ...
}
```

### 5. 번역 추가

#### **en_us.json**
```json
{
  "invbackups.success.experience_restored": "Experience restored: %s",
  "invbackups.gui.experience": "Experience",
  "invbackups.gui.experience.level": "Level: %d",
  "invbackups.gui.experience.progress": "Progress: %d%%",
  "invbackups.gui.experience.total": "Total XP: %d"
}
```

#### **ko_kr.json**
```json
{
  "invbackups.success.experience_restored": "경험치가 복원되었습니다: %s",
  "invbackups.gui.experience": "경험치",
  "invbackups.gui.experience.level": "레벨: %d",
  "invbackups.gui.experience.progress": "진행도: %d%%",
  "invbackups.gui.experience.total": "총 경험치: %d"
}
```

---

## ⚙️ 설정 옵션

### InventoryConfig.java 확장

```java
public class InventoryConfig {
    public static class General {
        // 기존 설정들...
        
        // ✨ 신규 설정
        public final ModConfigSpec.BooleanValue experienceBackupEnabled;
        public final ModConfigSpec.BooleanValue experienceRestoreWithInventory;
        
        General(ModConfigSpec.Builder builder) {
            // ... 기존 코드 ...
            
            experienceBackupEnabled = builder
                .comment("Enable experience backup with inventory",
                         "경험치를 인벤토리와 함께 백업")
                .define("experienceBackupEnabled", true);
            
            experienceRestoreWithInventory = builder
                .comment("Restore experience when restoring inventory",
                         "인벤토리 복원 시 경험치도 함께 복원")
                .define("experienceRestoreWithInventory", true);
        }
    }
}
```

### 설정 파일 예시

```toml
[general]
    # 경험치 백업 활성화
    experienceBackupEnabled = true
    
    # 인벤토리 복원 시 경험치도 함께 복원
    experienceRestoreWithInventory = true
```

---

## 🧪 테스트 계획

### 단위 테스트

```java
@Test
public void testExperienceDataCreation() {
    ExperienceData exp = new ExperienceData(30, 0.5f, 1451);
    assertEquals(30, exp.getExperienceLevel());
    assertEquals(0.5f, exp.getExperienceProgress(), 0.001f);
    assertEquals(1451, exp.getTotalExperience());
}

@Test
public void testExperienceDataDisplayString() {
    ExperienceData exp = new ExperienceData(30, 0.5f, 1451);
    assertEquals("Lv.30 (50%)", exp.getDisplayString());
}

@Test
public void testExperienceDataEquality() {
    ExperienceData exp1 = new ExperienceData(30, 0.5f, 1451);
    ExperienceData exp2 = new ExperienceData(30, 0.5f, 1451);
    assertEquals(exp1, exp2);
}
```

### 통합 테스트

#### 테스트 1: 사망 시 경험치 백업
```
1. 플레이어 경험치를 레벨 30 + 50%로 설정
2. 플레이어 사망 트리거
3. ✅ 백업 파일 생성 확인
4. ✅ JSON 파일에 experienceData 필드 존재 확인
5. ✅ 값이 정확히 저장되었는지 확인
```

#### 테스트 2: 경험치 복원
```
1. 백업 파일에서 경험치 데이터 로드
2. `/inventory set Player backup-file` 실행
3. ✅ 플레이어 경험치 바 표시 확인
4. ✅ F3 디버그 화면에서 값 확인
5. ✅ 클라이언트 동기화 확인 (재접속 후에도 유지)
```

#### 테스트 3: GUI 표시
```
1. `/inventory view Player backup-file` 실행
2. ✅ Slot 52에 경험치 병 아이템 표시
3. ✅ 경험치 정보 툴팁 표시 확인
4. ✅ 한국어/영어 번역 확인
```

#### 테스트 4: 설정 비활성화
```
1. experienceBackupEnabled = false 설정
2. 백업 생성
3. ✅ experienceData 필드가 null 또는 누락
4. experienceRestoreWithInventory = false 설정
5. 백업 복원
6. ✅ 경험치는 복원되지 않음
```

#### 테스트 5: 하위 호환성
```
1. 경험치 필드가 없는 기존 백업 파일 로드
2. ✅ 에러 없이 로드됨 (experienceData = null)
3. ✅ 인벤토리는 정상 복원
4. ✅ 경험치 복원 스킵 (경고 로그)
```

---

## ⚠️ 주의사항

### 1. 하위 호환성
- **기존 백업 파일에는 experienceData 필드가 없음**
- null 체크 필수: `if (expData != null && expData.hasExperience())`
- 기존 백업 로드 시 에러 없이 처리

### 2. 클라이언트 동기화
- 경험치 변경 후 **반드시 클라이언트에 패킷 전송**
- `ClientboundSetExperiencePacket` 사용
- 동기화 실패 시 클라이언트에서 보이는 값이 다를 수 있음

### 3. 진행도 범위
- `experienceProgress`는 **0.0 ~ 1.0 사이**여야 함
- 범위 검증: `Math.max(0.0f, Math.min(1.0f, progress))`

### 4. 엔더 상자 백업
- EnderChestData에는 경험치 추가하지 않음
- 엔더 상자는 아이템만 저장하는 것이 논리적으로 맞음

### 5. 복사(Copy) 명령어
- `/inventory copy` 명령어에서는 경험치 복사하지 않음
- copy는 아이템만 복사하는 것이 의도

---

## 📊 예상 영향

### 디스크 공간
- 백업당 추가 용량: **~50 bytes** (JSON)
- 백업 1000개 기준: **~50 KB** (무시 가능)

```json
{
  "data": [...],
  "backpackSnapshots": {...},
  "experienceData": {
    "experienceLevel": 30,
    "experienceProgress": 0.5,
    "totalExperience": 1451
  }
}
```

### 성능
- **영향 없음**: 경험치는 3개의 primitive 값 (int, float, int)
- 직렬화/역직렬화 오버헤드 무시 가능

### 유지보수
- 새 클래스 1개 추가: `ExperienceData.java`
- 기존 클래스 수정: 
  - `InventoryData.java` (~20 줄)
  - `InventoryCommand.java` (~10 줄)
  - 각 이벤트 클래스 (~5 줄씩)

---

## 📝 구현 체크리스트

### Phase 1: 데이터 구조
- [ ] `ExperienceData.java` 생성
- [ ] `InventoryData.java` 확장
- [ ] `InventoryConfig.java` 설정 추가

### Phase 2: 백업 로직
- [ ] `InventoryData.encode()` 수정
- [ ] `PlayerDeadEvent.java` 수정
- [ ] `PlayerConnectionEvent.java` 수정
- [ ] `ContainerCloseEvent.java` 수정
- [ ] `TickHandler.java` 수정

### Phase 3: 복원 로직
- [ ] `InventoryCommand.setInventory()` 수정
- [ ] 클라이언트 동기화 패킷 전송
- [ ] 성공 메시지 추가

### Phase 4: GUI
- [ ] 미리보기 경험치 표시 (slot 52)
- [ ] 경험치 병 아이콘 + 툴팁

### Phase 5: 번역
- [ ] `en_us.json` 추가
- [ ] `ko_kr.json` 추가
- [ ] `ru_ru.json` 추가

### Phase 6: 테스트
- [ ] 단위 테스트 작성
- [ ] 사망 백업 테스트
- [ ] 복원 테스트
- [ ] GUI 표시 테스트
- [ ] 하위 호환성 테스트

### Phase 7: 문서
- [ ] README 업데이트
- [ ] CHANGELOG 작성

---

## 🚀 예상 타임라인

- **Day 1 (4시간)**
  - Phase 1: 데이터 구조 (1시간)
  - Phase 2: 백업 로직 (2시간)
  - Phase 3: 복원 로직 (1시간)

- **Day 2 (4시간)**
  - Phase 4: GUI (1.5시간)
  - Phase 5: 번역 (0.5시간)
  - Phase 6: 테스트 (1.5시간)
  - Phase 7: 문서 (0.5시간)

**총 예상 시간: 8시간 (1-2일)**

---

## 🎯 성공 기준

- ✅ 경험치가 모든 백업 트리거에서 자동 저장됨
- ✅ `/inventory set`으로 경험치 복원 가능
- ✅ 미리보기 GUI에서 경험치 정보 표시
- ✅ 기존 백업 파일 호환성 유지
- ✅ 설정으로 활성화/비활성화 가능
- ✅ 모든 테스트 통과
- ✅ 3개 언어 번역 완료

---

## 📚 참고 자료

- **Minecraft Wiki - Experience**: https://minecraft.fandom.com/wiki/Experience
- **NeoForge Player API**: https://docs.neoforged.net/docs/networking/
- **ClientboundSetExperiencePacket**: `net.minecraft.network.protocol.game.ClientboundSetExperiencePacket`

---

**작성자:** Pocky  
**프로젝트:** InventoryLogger  
**버전:** v2.1 (예정)
