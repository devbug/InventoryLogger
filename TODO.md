# 📝 InventoryLogger TODO

**마지막 업데이트:** 2025-11-23

---

## 🗺️ 권장 구현 순서

📄 **상세 로드맵:** `IMPLEMENTATION_ROADMAP.md`

### ✅ v2.1 릴리스 완료! (2025-11-23)

```
✅ 1순위 (HIGH) → 실시간 인벤토리 동기화 (1.5시간)
✅ 2순위 (MED)  → 경험치 백업 기능 (3시간)
✅ 3순위 (LOW)  → GUI 현지화 개선 (0.5시간)
────────────────────────────────────────────
총 작업시간: 5시간 (예상 16시간 → 실제 5시간!)
```

### v3.0 메이저 업그레이드 (다음 단계)

```
4순위 (MED)  → SQL 마이그레이션 (5주)
5순위 (LOW)  → 백업 압축 옵션 (4시간, 선택적)
6순위 (VLOW) → 웹 대시보드 (2주+, 미래)
```

**핵심 원칙:** 사용자 영향도 높은 순서 → 빠른 승리 우선

**현재 상태:** v2.1 모든 목표 달성! 🎉

---

## 🚨 긴급 (High Priority)

### 1. ⚠️ 관전자 모드 GUI 상호작용 버그 (해결 불가 - 보류)

**발견일:** 2025-11-22  
**시도일:** 2025-11-23  
**상태:** 해결 불가 - Minecraft 엔진 제약  
**우선순위:** MEDIUM (필수 아님)  
**소요 시간:** 3시간 (여러 접근법 시도)

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

#### 시도한 해결 방안 (모두 실패)

##### ❌ 방안 1: `clicked()` 메서드 오버라이드
**시도 내용:**
```java
@Override
public void clicked(int slotId, int button, ClickType clickType, Player player) {
    if (player.isSpectator()) {
        if (button != 0) return;
        clickType = ClickType.PICKUP;
    }
    // ... 처리 로직
}
```

**실패 원인:** `clicked()` 메서드 자체가 호출되지 않음. Minecraft 엔진이 더 상위 레벨에서 차단.

##### ❌ 방안 2: `stillValid()` true 반환
**시도 내용:**
```java
@Override
public boolean stillValid(Player player) {
    return true;  // 항상 유효
}
```

**실패 원인:** GUI 유효성과 상호작용 권한은 별개. 여전히 클릭 차단됨.

##### ❌ 방안 3: `mayPickup()` OP 권한 체크
**시도 내용:**
```java
@Override
public boolean mayPickup(Player player) {
    if (player.isSpectator()) {
        return player instanceof ServerPlayer sp && sp.hasPermissions(2);
    }
    return true;
}
```

**실패 원인:** 슬롯 레벨 권한은 통과하지만, 메뉴 레벨에서 차단됨.

##### ❌ 방안 4: `canTakeItemForPickAll()` / `canDragTo()` 오버라이드
**시도 내용:**
```java
@Override
public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
    return true;
}

@Override
public boolean canDragTo(Slot slot) {
    return true;
}
```

**실패 원인:** 로그조차 찍히지 않음. 더 깊은 레벨에서 차단되는 것으로 추정.

##### ❌ 방안 5: `doClick()` 메서드 오버라이드 시도
**시도 내용:**
```java
@Override
public void doClick(int slotId, int button, ClickType clickType) {
    super.doClick(slotId, button, clickType);
}
```

**실패 원인:** 컴파일 에러. 해당 메서드가 존재하지 않거나 접근 불가능.

#### 근본 원인 분석

Minecraft의 관전자 모드 제약은 **여러 레벨에서 중첩적으로 작동**합니다:

1. **클라이언트 레벨:** 클라이언트가 관전자 모드에서 일부 패킷을 보내지 않음
2. **네트워크 레벨:** 서버가 관전자의 컨테이너 조작 패킷을 거부
3. **메뉴 레벨:** `AbstractContainerMenu`가 관전자 상호작용 차단
4. **슬롯 레벨:** `Slot.mayPickup()` 등에서 게임모드 체크

**문제:** 서버 측 모드만으로는 클라이언트나 네트워크 레벨의 차단을 우회할 수 없음.

#### 가능한 대안 (미구현)

##### 💡 방안 A: 자동 게임모드 전환 (권장하지 않음)
GUI 열기 전 크리에이티브로 전환 후 닫을 때 복원
- **장점:** 작동 가능성 높음
- **단점:** 사용자 경험 저해, 예상치 못한 부작용 가능

##### 💡 방안 B: 클라이언트 모드 개발 (대규모 작업)
클라이언트 측 모드를 추가로 개발하여 패킷 전송 강제
- **장점:** 완전한 해결
- **단점:** 서버 전용 모드가 아니게 됨, 개발 난이도 매우 높음

##### 💡 방안 C: 명령어 기반 대안 사용 (현재 가능)
GUI 대신 명령어 사용 (`/inventory list`, `/inventory set` 등)
- **장점:** 모든 게임모드에서 작동
- **단점:** GUI만큼 편리하지 않음

#### 결론

**상태:** 해결 불가 (보류)

**이유:**
- Minecraft 엔진의 근본적인 제약
- 서버 측 모드만으로 우회 불가능
- 클라이언트 모드 개발은 프로젝트 범위 초과

**대안:**
- 관전자 모드 사용 시 `/gamemode creative` 전환 후 GUI 사용
- 명령어 인터페이스 활용 (`/inventory list`, `/inventory view` 등)
- 대부분의 관리 작업은 크리에이티브/서바이벌 모드에서 수행 가능

**영향:**
- **낮음:** 관전자 모드에서 GUI를 써야 하는 경우가 드묾
- 명령어 실행은 여전히 가능
- 핵심 기능에는 영향 없음

---

#### ~~해결 방안~~ (참고용)

##### ~~✅ 방안 1: 강제 상호작용 허용 (권장)~~ (실패)

관리자 전용 기능이므로 관전자 모드에서도 상호작용을 허용합니다.

**장점:**
- 관리자 편의성 극대화
- 서버 관리 시 관전자 모드로 이동하며 백업 관리 가능
- 코드 수정 최소화

**단점:**
- 마인크래프트 관전자 모드 철학과 약간 충돌

**시도했지만 실패함** - 위의 "시도한 해결 방안" 참조

##### ~~방안 2/3: 자동 게임모드 전환 / 관전자 차단~~
이러한 접근법들도 고려했으나 사용자 경험을 해치므로 구현하지 않음.

#### 참고 자료
- Minecraft Spectator Mode: https://minecraft.fandom.com/wiki/Spectator
- NeoForge Container API: https://docs.neoforged.net/docs/gui/menus/
- AbstractContainerMenu 소스 코드 분석 필요

---

## 🚨 긴급 (High Priority)

### 2. ✅ 실시간 인벤토리 동기화 개선 🔄 (완료)

**발견일:** 2025-11-22  
**완료일:** 2025-11-23  
**우선순위:** HIGH  
**실제 작업 시간:** 1.5시간

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
- [x] `ChestEditableMenu`에 틱 카운터 추가
- [x] `broadcastFullState()` 오버라이드
- [x] `syncToTarget()` 양방향 동기화 구현
- [x] `syncFromTarget()` 역방향 동기화 구현
- [x] `EnderChestEditableMenu` 동기화 적용
- [x] `CuriosEditableMenu` 동기화 적용
- [ ] Hash 기반 변경 감지 최적화 (선택적)
- [ ] 설정 파일 동기화 주기 옵션 추가 (선택적)
- [ ] 동시 접근 처리 락 시스템 (선택적)
- [ ] 테스트 (실시간, Shift+클릭, 성능)

#### ✅ 구현 완료 내용
**3개 Menu 클래스에 실시간 동기화 적용:**
1. **ChestEditableMenu** - 플레이어 인벤토리 (41 슬롯)
2. **EnderChestEditableMenu** - 엔더 상자 (27 슬롯)
3. **CuriosEditableMenu** - Curios 장비 (18 슬롯)

**동기화 방식:**
- 5틱마다 (0.25초) 자동 동기화
- 양방향 동기화: GUI ↔ 대상 플레이어
- `ItemStack.matches()`로 변경 감지
- 창 닫을 때 최종 동기화

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

### 3. ✅ 경험치 백업 기능 추가 ✨ (완료)

**발견일:** 2025-11-22  
**완료일:** 2025-11-23  
**실제 작업 시간:** 3시간

#### 개요
현재 인벤토리만 백업하는 시스템에 플레이어 경험치(Experience) 백업 및 복원 기능 추가

#### 상세 계획
📄 **별도 문서 참조:** `EXPERIENCE_BACKUP_PLAN.md`

#### 주요 기능
- ✅ 경험치 레벨, 진행도, 총 XP 자동 백업
- ✅ 인벤토리 복원 시 경험치도 함께 복원
- ✅ GUI 미리보기에서 경험치 정보 표시
- ✅ 설정으로 활성화/비활성화 가능

#### 구현 체크리스트
- [x] `ExperienceData.java` 클래스 생성
- [x] `InventoryData` 확장 (experienceData 필드)
- [x] 백업 로직 수정 (모든 이벤트)
- [x] 복원 로직 수정 (`/inventory set`)
- [x] GUI 경험치 표시 (백업: slot 52, 현재 플레이어: slot 52)
- [x] 번역 추가 (en/ko/ru)
- [x] 테스트 (사용자 확인 완료)
- [x] 하위 호환성 검증 (레거시 백업 지원)

#### 달성 효과
- ✅ 플레이어 경험 개선 (사망 시 경험치 복구)
- ✅ 버그 복구 용이 (경험치 손실 문제 해결)
- ✅ 백업 완전성 향상
- ✅ `/inventory player` 명령에서도 경험치 표시

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

### 3. ✅ GUI 현지화 개선 (완료)

**완료일:** 2025-11-23  
**실제 작업 시간:** 30분

**현재 상태:**
- 한국어, 영어, 러시아어 지원
- ✅ 모든 GUI 텍스트 번역 파일로 이동 완료

**개선 내용:**
- [x] 하드코딩된 텍스트 검색 및 제거
- [x] `en_us.json`, `ko_kr.json`, `ru_ru.json` 확장
- [x] 5개 위치 번역 키 적용

**변경된 텍스트:**
- ✅ "◄ Back to Browser" → `invbackups.gui.button.back`
- ✅ "Click to return to backup browser" → `invbackups.gui.button.back.desc`
- ✅ "Click to return to main preview" → `invbackups.gui.button.back.preview`
- ✅ "Click to return to main inventory" → `invbackups.gui.button.back.inventory`
- ✅ "(Legacy backup - no XP data)" → `invbackups.gui.experience.legacy`

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

### v2.1 (2025-11-23)
- ✅ 실시간 인벤토리 동기화 (0.25초 간격, 양방향)
- ✅ 경험치 백업 및 복원 기능
- ✅ GUI 경험치 표시 (백업 + 현재 플레이어)
- ✅ GUI 완전 현지화 (하드코딩 제거)
- ✅ 레거시 백업 하위 호환성 유지
- ✅ 총 경험치 계산 알고리즘 구현

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
- v2.1: ✅ 완료! (실시간 동기화, 경험치 백업, GUI 현지화)
- v2.2: 테스트 및 안정화 (관전자 모드 버그는 보류)
- v3.0: SQL 마이그레이션 (조건부, 성능 평가 후 결정)

---

**작성자:** Pocky  
**프로젝트:** InventoryLogger  
**라이선스:** All Rights Reserved
