# 📝 InventoryLogger TODO

**마지막 업데이트:** 2025-11-22

---

## 🗺️ 권장 구현 순서

📄 **상세 로드맵:** `IMPLEMENTATION_ROADMAP.md`

### v2.1 릴리스 (즉시 시작, 2주 목표)

```
1순위 (HIGH) → 실시간 인벤토리 동기화 (6시간)
2순위 (MED)  → 경험치 백업 기능 (8시간)
3순위 (LOW)  → GUI 현지화 개선 (2시간)
────────────────────────────────────────────
총 작업시간: 16시간 (2일)
```

### v3.0 메이저 업그레이드 (v2.1 안정화 후)

```
4순위 (MED)  → SQL 마이그레이션 (5주)
5순위 (LOW)  → 백업 압축 옵션 (4시간, 선택적)
6순위 (VLOW) → 웹 대시보드 (2주+, 미래)
```

**핵심 원칙:** 사용자 영향도 높은 순서 → 빠른 승리 우선

---

## 🚨 긴급 (High Priority)

### 1. ✅ 관전자 모드 GUI 상호작용 버그 (해결 완료)

**발견일:** 2025-11-22  
**해결일:** 2025-11-22  
**우선순위:** HIGH  
**실제 작업 시간:** 1시간

#### 문제 상황
관리자가 관전자(Spectator) 모드 상태에서 백업 관리 GUI와 상호작용이 불가능합니다.

**재현 단계:**
1. `/gamemode spectator` 실행
2. `/inventory gui Steve` 실행 → GUI는 열림
3. 백업 아이콘 클릭 시도 → **아무 반응 없음**
4. `/inventory view Steve 2025-11-22-10-30-45` 실행 → 미리보기 열림
5. 아이템 드래그 시도 → **아이템 집을 수 없음**

#### 영향 범위
- ✅ 명령어 실행: 정상 (모든 게임모드에서 가능)
- ❌ GUI 백업 브라우저: 아이콘 클릭 불가
- ❌ 백업 미리보기: 아이템 드래그/Shift+클릭 불가
- ✅ 채팅 버튼 클릭: 정상 (`/inventory list` 페이지네이션 등)

#### 원인 분석

**1. 마인크래프트 기본 동작**
```java
// Minecraft 기본: 관전자 모드는 컨테이너와 상호작용 불가
GameType.SPECTATOR → Container interaction disabled by default
```

**2. 코드 위치 및 문제점**

##### 📍 `BackupBrowserMenu.clicked()` (Line 1217-1262)
```java
@Override
public void clicked(int slotId, int button, ClickType clickType, Player player) {
    // 좌클릭만 처리
    if (clickType != ClickType.PICKUP || button != 0) {
        return; // ❌ 관전자 모드는 여기서 차단됨
    }
    // ...
}
```

**문제:** 관전자 모드에서 `ClickType.PICKUP`이 제대로 전달되지 않음

##### 📍 `CopyableBackupSlot.mayPickup()` (Line 921-923)
```java
@Override
public boolean mayPickup(Player player) {
    return true;  // ❌ 게임모드 무시하고 무조건 true
}
```

**문제:** `true`를 반환하지만, 마인크래프트 엔진 레벨에서 관전자 모드는 차단됨

##### 📍 `ChestCopyableMenu.clicked()` (Line 843-896)
```java
@Override
public void clicked(int slotId, int button, ClickType clickType, Player player) {
    // Curios 버튼 (slot 48)
    if (slotId == 48) { ... }
    
    // 돌아가기 버튼 (slot 53)
    if (slotId == 53) { ... }
    
    // 아이템 배치 차단
    if (slotId >= 0 && slotId < this.containerSize) {
        ItemStack cursor = player.containerMenu.getCarried();
        if (!cursor.isEmpty()) {
            return; // ❌ 관전자는 여기 도달 못함
        }
    }
    
    super.clicked(slotId, button, clickType, player);
}
```

**문제:** 관전자 모드 체크 누락

#### 해결 방안

##### ✅ 방안 1: 강제 상호작용 허용 (권장)

관리자 전용 기능이므로 관전자 모드에서도 상호작용을 허용합니다.

**장점:**
- 관리자 편의성 극대화
- 서버 관리 시 관전자 모드로 이동하며 백업 관리 가능
- 코드 수정 최소화

**단점:**
- 마인크래프트 관전자 모드 철학과 약간 충돌 (하지만 OP 전용이므로 문제 없음)

**구현:**

```java
// 1. BackupBrowserMenu.clicked() 수정
@Override
public void clicked(int slotId, int button, ClickType clickType, Player player) {
    // 관전자 모드 예외 처리: 좌클릭만 허용
    if (player.isSpectator()) {
        // 관전자 모드에서는 좌클릭만 체크
        if (button != 0) return;
        clickType = ClickType.PICKUP; // 강제 설정
    } else {
        // 일반 모드는 기존 로직
        if (clickType != ClickType.PICKUP || button != 0) return;
    }
    
    // 페이지 버튼 처리
    if (slotId == 45 && currentPage > 0) { /* 이전 페이지 */ }
    else if (slotId == 53 && currentPage < getTotalPages() - 1) { /* 다음 페이지 */ }
    
    // 백업 선택
    if (slotId >= 0 && slotId < 45) { /* 백업 열기 */ }
}

// 2. ChestCopyableMenu.clicked() 수정
@Override
public void clicked(int slotId, int button, ClickType clickType, Player player) {
    // 관전자 모드 특별 처리
    boolean isSpectator = player.isSpectator();
    
    // Curios 버튼 (slot 48)
    if (slotId == 48) {
        ItemStack item = this.chestContainer.getItem(48);
        if (item.getItem() == Items.ENDER_EYE) {
            player.closeContainer();
            if (player instanceof ServerPlayer sp) {
                sp.getServer().execute(() -> {
                    openCuriosView(sp, targetPlayer, originalItems, viewer);
                });
            }
        }
        return;
    }
    
    // 돌아가기 버튼 (slot 53)
    if (slotId == 53) {
        player.closeContainer();
        if (player instanceof ServerPlayer sp) {
            sp.getServer().execute(() -> {
                // ... 브라우저 재오픈
            });
        }
        return;
    }
    
    // 백업 슬롯 처리 (0-53)
    if (slotId >= 0 && slotId < this.containerSize) {
        // 버튼 슬롯은 건너뛰기
        if (slotId == 48 || slotId == 53) return;
        
        // 관전자 모드가 아닐 때만 아이템 배치 차단
        if (!isSpectator) {
            ItemStack cursor = player.containerMenu.getCarried();
            if (!cursor.isEmpty()) {
                return; // 일반 플레이어는 아이템 배치 차단
            }
        }
    }
    
    // 관전자 모드는 항상 super.clicked() 호출
    if (isSpectator || clickType == ClickType.PICKUP) {
        super.clicked(slotId, button, clickType, player);
    }
}

// 3. CopyableBackupSlot.mayPickup() 수정
@Override
public boolean mayPickup(Player player) {
    // OP 권한이 있는 관전자는 허용
    if (player.isSpectator()) {
        return player instanceof ServerPlayer sp && 
               sp.hasPermissions(2); // OP 레벨 2 이상
    }
    return true;
}

// 4. CopyableBackupSlot.mayPlace() 강화
@Override
public boolean mayPlace(ItemStack stack) {
    // 관전자 포함 모든 모드에서 배치 불가
    return false;
}
```

##### ⚠️ 방안 2: 관전자 모드 자동 전환 (비권장)

GUI 열기 전 크리에이티브 모드로 자동 전환 후 복원

**장점:**
- 마인크래프트 기본 동작 준수

**단점:**
- 모드 전환 시 플레이어 경험 저해
- 추가 코드 복잡도
- 예상치 못한 부작용 가능

**구현 생략** (권장하지 않음)

##### ❌ 방안 3: 관전자 모드 차단 (최악)

관전자 모드에서 GUI 실행 자체를 차단하고 에러 메시지 표시

**구현하지 않음** - 관리자 불편

#### 테스트 계획

```
테스트 케이스 1: 관전자 모드 백업 브라우저
1. /gamemode spectator
2. /inventory gui TestPlayer
3. ✅ GUI 열림 확인
4. ✅ 백업 아이콘 좌클릭 → 미리보기 열림
5. ✅ 이전/다음 페이지 버튼 작동 확인

테스트 케이스 2: 관전자 모드 백업 미리보기
1. /gamemode spectator
2. /inventory view TestPlayer 2025-11-22-10-30-45
3. ✅ 미리보기 열림 확인
4. ✅ 아이템 좌클릭 드래그 → 인벤토리로 복사
5. ✅ Shift+클릭 → 인벤토리로 복사
6. ✅ 백업 슬롯 아이템은 유지 (무한 복사)
7. ✅ 아이템 배치 시도 → 차단 확인
8. ✅ Curios 버튼 (slot 48) 클릭 → Curios 뷰 열림
9. ✅ 뒤로가기 버튼 (slot 53) 클릭 → 브라우저로 복귀

테스트 케이스 3: 다른 게임모드 검증
1. /gamemode survival
2. 위 테스트 반복 → ✅ 정상 작동
3. /gamemode creative
4. 위 테스트 반복 → ✅ 정상 작동
5. /gamemode adventure
6. 위 테스트 반복 → ✅ 정상 작동

테스트 케이스 4: 권한 테스트
1. /deop TestAdmin
2. /gamemode spectator
3. /inventory gui 시도 → ❌ 권한 없음 에러
4. GUI는 열리지 않음 (기존 requires() 검증)
```

#### 예상 영향
- ✅ 기존 기능 유지
- ✅ 관전자 모드 지원 추가
- ✅ 다른 게임모드 영향 없음
- ✅ 권한 시스템 유지

#### 파일 수정 목록
- `src/main/java/com/pocky/invbackups/commands/InventoryCommand.java`
  - `BackupBrowserMenu.clicked()` (Line ~1217)
  - `ChestCopyableMenu.clicked()` (Line ~843)
  - `CopyableBackupSlot.mayPickup()` (Line ~921)

#### 관련 이슈
- 없음 (신규 발견)

#### 참고 자료
- Minecraft GameType: https://minecraft.fandom.com/wiki/Gamemode
- NeoForge Container API: https://docs.neoforged.net/docs/gui/menus/

---

## 🚨 긴급 (High Priority)

### 2. 실시간 인벤토리 동기화 개선 🔄

**발견일:** 2025-11-22  
**우선순위:** HIGH  
**예상 작업 시간:** 6시간

#### 문제 상황
`/inventory player <플레이어>` 명령으로 현재 인벤토리를 열었을 때, 아이템을 추가/제거해도 **창을 닫을 때까지 대상 플레이어에게 반영되지 않음**

#### 재현 단계
```
1. 관리자: /inventory player Steve
2. 관리자가 다이아몬드 검 추가
3. ❌ Steve 화면에 보이지 않음 (실시간 반영 안됨)
4. 관리자가 GUI 닫기
5. ✅ Steve 화면에 다이아몬드 검 표시 (이제서야!)
```

#### 원인
- `ChestEditableMenu.removed()` 메서드에서 **창 닫을 때만** 동기화
- `SimpleContainer`는 독립된 복사본이며 원본과 실시간 연결 없음
- 대상 플레이어 ↔ GUI 양방향 동기화 미구현

#### 상세 계획
📄 **별도 문서 참조:** `REALTIME_SYNC_PLAN.md`

#### 해결 방안
**Tick 기반 주기적 동기화 (권장)**
```java
@Override
public void broadcastFullState() {
    super.broadcastFullState();
    
    tickCounter++;
    if (tickCounter >= SYNC_INTERVAL) { // 5틱 = 0.25초
        tickCounter = 0;
        syncToTarget();      // GUI → 대상 플레이어
        syncFromTarget();    // 대상 플레이어 → GUI
    }
}
```

#### 구현 체크리스트
- [ ] `ChestEditableMenu`에 틱 카운터 추가
- [ ] `broadcastFullState()` 오버라이드
- [ ] `syncToTarget()` 양방향 동기화
- [ ] Hash 기반 변경 감지 최적화
- [ ] `EnderChestEditableMenu` 동기화 적용
- [ ] `CuriosEditableMenu` 동기화 적용
- [ ] 설정 파일 동기화 주기 옵션 추가
- [ ] 동시 접근 처리 (선택적 락 시스템)
- [ ] 테스트 (실시간, Shift+클릭, 성능)

#### 예상 효과
- ✅ 0.25초 이내 실시간 반영
- ✅ 관리자 ↔ 대상 플레이어 양방향 동기화
- ✅ 사용자 혼란 제거
- ✅ 직관적인 편집 경험

#### 성능 영향
- CPU: +0.01% ~ +0.1%
- 메모리: 무시 가능
- 서버 TPS 영향: < 0.1

---

## 📊 중요 (Medium Priority)

### 3. 경험치 백업 기능 추가 ✨

**발견일:** 2025-11-22  
**우선순위:** MEDIUM  
**예상 작업 시간:** 1-2일 (8시간)

#### 개요
현재 인벤토리만 백업하는 시스템에 플레이어 경험치(Experience) 백업 및 복원 기능 추가

#### 상세 계획
📄 **별도 문서 참조:** `EXPERIENCE_BACKUP_PLAN.md`

#### 주요 기능
- ✨ 경험치 레벨, 진행도, 총 XP 자동 백업
- ✨ 인벤토리 복원 시 경험치도 함께 복원
- ✨ GUI 미리보기에서 경험치 정보 표시
- ✨ 설정으로 활성화/비활성화 가능

#### 구현 체크리스트
- [ ] `ExperienceData.java` 클래스 생성
- [ ] `InventoryData` 확장 (experienceData 필드)
- [ ] 백업 로직 수정 (모든 이벤트)
- [ ] 복원 로직 수정 (`/inventory set`)
- [ ] GUI 경험치 표시 (slot 52)
- [ ] 번역 추가 (en/ko/ru)
- [ ] 테스트 (단위/통합)
- [ ] 하위 호환성 검증

#### 예상 효과
- 🎮 플레이어 경험 개선 (사망 시 경험치 복구)
- 🐛 버그 복구 용이 (경험치 손실 문제 해결)
- 📊 백업 완전성 향상

---

### 3. SQL 마이그레이션 프로젝트 🔄

**시작일:** TBD  
**우선순위:** MEDIUM (성능 평가 후 결정)  
**예상 작업 시간:** 5주

#### 개요
JSON 파일 기반 백업 시스템을 SQLite 데이터베이스로 전환하여 대용량 백업 처리 성능 향상 및 고급 기능 추가

#### 상세 계획
📄 **별도 문서 참조:** `SQL_MIGRATION_PLAN.md`

#### 주요 목표
- 백업 목록 조회 166배 개선 (500ms → 3ms)
- 자동완성 250배 개선 (500ms → 2ms)
- 통계 및 분석 기능 추가
- 대용량 데이터 지원

#### 체크리스트
- [ ] Phase 0: 준비 (의존성, 설정)
- [ ] Phase 1: DB 설계
- [ ] Phase 2: 코드 구조 (인터페이스, 팩토리)
- [ ] Phase 3: 구현 (SqlStorage)
- [ ] Phase 4: 마이그레이션 도구
- [ ] Phase 5: 테스트 및 배포

---

## 🔧 개선 사항 (Low Priority)

### 3. GUI 현지화 개선

**현재 상태:**
- 한국어, 영어, 러시아어 지원
- GUI 일부 텍스트는 하드코딩 (예: "◄ Back to Browser")

**개선 목표:**
- [ ] 모든 GUI 텍스트 번역 파일로 이동
- [ ] `en_us.json`, `ko_kr.json`, `ru_ru.json` 확장

**영향 파일:**
- `InventoryCommand.java:776-780` - Back button
- 기타 하드코딩된 텍스트 검색 필요

---

### 4. 백업 압축 옵션

**아이디어:**
- 오래된 백업 자동 압축 (gzip)
- 디스크 공간 절약

**검토 필요:**
- 압축/해제 오버헤드
- SQL 마이그레이션 시 불필요할 수 있음

---

### 5. 웹 대시보드 (미래)

**컨셉:**
- 웹 브라우저에서 백업 관리
- REST API 제공
- 실시간 백업 통계

**우선순위:** VERY LOW (SQL 마이그레이션 완료 후 고려)

---

## ✅ 완료된 작업

### v2.0 (2025-01-22)
- ✅ GUI 백업 브라우저 (`/inventory gui`)
- ✅ 페이지네이션 시스템 (10개/페이지)
- ✅ 빠른 필터 버튼 (오늘/어제/이번 달)
- ✅ 인터랙티브 미리보기 (드래그 앤 드롭)
- ✅ Shift+클릭 복사
- ✅ 무한 복사 (원본 백업 유지)
- ✅ Curios 슬롯 지원
- ✅ 비동기 I/O (TPS 저하 방지)
- ✅ 한국어 완전 현지화
- ✅ 컨테이너 닫기 이벤트 백업

### v1.x
- ✅ 주기적 자동 백업 (10분)
- ✅ 사망/접속/종료 백업
- ✅ 자동 정리 (7일)
- ✅ 중복 제거
- ✅ 명령어 시스템
- ✅ 권한 시스템 (OP 레벨 2)

---

## 📝 노트

### 개발 원칙
1. **서버 전용:** 클라이언트 설치 불필요 유지
2. **하위 호환성:** 기존 JSON 백업 지원 유지
3. **성능 우선:** 비동기 I/O, 최소 TPS 영향
4. **관리자 친화:** 명령어 + GUI 모두 지원
5. **확장성:** 새 스토리지 백엔드 쉽게 추가 가능

### 다음 릴리스 목표
- v2.1: 관전자 모드 버그 수정
- v3.0: SQL 마이그레이션 (조건부)

---

**작성자:** Pocky  
**프로젝트:** InventoryLogger  
**라이선스:** All Rights Reserved
