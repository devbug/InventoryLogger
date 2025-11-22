# 실시간 인벤토리 동기화 개선 계획

**작성일:** 2025-11-22  
**우선순위:** HIGH  
**예상 작업 시간:** 4-6시간

---

## 📋 문제 상황

### 현재 동작
`/inventory player <플레이어>` 명령으로 다른 플레이어의 현재 인벤토리를 열었을 때:

```java
// InventoryCommand.java:689-716
@Override
public void removed(Player player) {
    super.removed(player);
    
    // ⚠️ 창을 닫을 때만 동기화
    if (targetPlayer != null && !targetPlayer.isRemoved()) {
        Inventory targetInv = targetPlayer.getInventory();
        
        // 메뉴에서 타겟 플레이어로 복사
        for (int i = 0; i < 36; i++) {
            targetInv.items.set(i, this.getContainer().getItem(i).copy());
        }
        // ... 방어구, 오프핸드 동기화 ...
    }
}
```

**문제점:**
1. ❌ 관리자가 아이템을 빼거나 넣어도 **대상 플레이어는 실시간으로 보지 못함**
2. ❌ 대상 플레이어가 아이템을 사용해도 **관리자 화면에 반영되지 않음**
3. ❌ 창을 닫기 전까지 변경사항이 **완전히 보류됨**

### 재현 단계
```
1. 관리자: /inventory player Steve
2. 관리자가 GUI에서 다이아몬드 검 추가
3. Steve 플레이어 화면 확인
   → ❌ 다이아몬드 검이 보이지 않음
4. 관리자가 GUI 닫기
5. Steve 플레이어 화면 확인
   → ✅ 다이아몬드 검 표시됨 (이제서야!)
```

### 사용자 혼란
- 관리자: "왜 Steve 화면에 안 보이지?"
- Steve: "아이템 안 들어왔는데요?"
- 관리자: (GUI 닫음)
- Steve: "아, 이제 보이네요!"

---

## 🔍 원인 분석

### 1. Minecraft 컨테이너 시스템 아키텍처

```
┌─────────────────────────────────────────────────┐
│                 관리자 (Viewer)                  │
├─────────────────────────────────────────────────┤
│  ChestEditableMenu (독립 Container)              │
│  ├─ SimpleContainer (54 슬롯)                   │
│  │   └─ 대상 플레이어 인벤토리의 복사본         │
│  └─ removed() 시에만 원본에 반영               │
└─────────────────────────────────────────────────┘
                      ⬇ (창 닫을 때만)
┌─────────────────────────────────────────────────┐
│              대상 플레이어 (Target)               │
├─────────────────────────────────────────────────┤
│  Inventory (실제 인벤토리)                       │
│  ├─ items (36 슬롯)                             │
│  ├─ armor (4 슬롯)                              │
│  └─ offhand (1 슬롯)                            │
└─────────────────────────────────────────────────┘
```

**문제:** `SimpleContainer`는 독립된 임시 저장소이며, 대상 플레이어의 `Inventory`와 **직접 연결되지 않음**

### 2. 왜 실시간 동기화가 없는가?

#### **설계 의도**
- 마인크래프트 기본 메뉴 시스템은 **단일 플레이어 자신의 인벤토리만** 관리
- 다른 플레이어의 인벤토리를 직접 조작하는 것은 **비표준 동작**
- 기본 `ChestMenu`는 `Container`를 표시만 하고, 원본과의 동기화는 개발자 책임

#### **기술적 제약**
```java
// Minecraft 기본 동작
PlayerInventory → PlayerInventoryMenu (자동 동기화)
ChestBlockEntity → ChestMenu (BlockEntity가 원본)

// InventoryLogger의 경우
Player A의 Inventory → SimpleContainer (복사본) → ChestEditableMenu → Player B가 봄
                    ⬆ 실시간 연결 없음
```

### 3. 엔더 상자는 어떻게 작동하는가?

```java
// EnderChestEditableMenu.java:598-608
@Override
public void removed(Player player) {
    super.removed(player);
    
    // ✅ 엔더 상자도 동일한 문제 (창 닫을 때만 동기화)
    if (targetPlayer != null && !targetPlayer.isRemoved()) {
        EnderChestContainer ec = targetPlayer.getEnderChestInventory();
        for (int i = 0; i < 27; i++) {
            ec.setItem(i, this.getContainer().getItem(i).copy());
        }
    }
}
```

**엔더 상자도 같은 방식:** 창을 닫을 때만 반영

---

## 💡 해결 방안

### 방안 1: Tick 기반 주기적 동기화 (권장) ⭐

#### **개념**
매 틱(또는 N틱마다) 양방향으로 인벤토리를 동기화

```
┌─────────────────────────────────────────────────┐
│            ChestEditableMenu                     │
│  ┌────────────────────────────────────┐         │
│  │  broadcastFullState() 메서드        │         │
│  │  - 매 5틱마다 실행                 │         │
│  │  - 관리자 → 대상 동기화            │         │
│  │  - 대상 → 관리자 동기화            │         │
│  └────────────────────────────────────┘         │
└─────────────────────────────────────────────────┘
```

#### **장점**
✅ 구현이 간단함  
✅ 기존 코드 변경 최소화  
✅ 양방향 동기화 가능 (대상 플레이어가 아이템 사용 시 반영)  
✅ 설정으로 동기화 주기 조절 가능  

#### **단점**
⚠️ 약간의 딜레이 (0.25초 ~ 1초)  
⚠️ 틱마다 동기화 오버헤드 (최적화 필요)  

#### **구현 예시**

```java
private static class ChestEditableMenu extends ChestMenu {
    private final ServerPlayer targetPlayer;
    private final Container chestContainer;
    private final ServerPlayer viewer;
    private int tickCounter = 0;
    private static final int SYNC_INTERVAL = 5; // 5틱마다 (0.25초)

    @Override
    public void broadcastFullState() {
        super.broadcastFullState();
        
        // 주기적 동기화
        tickCounter++;
        if (tickCounter >= SYNC_INTERVAL) {
            tickCounter = 0;
            syncToTarget();
            syncFromTarget();
        }
    }
    
    /**
     * Sync changes from GUI to target player
     */
    private void syncToTarget() {
        if (targetPlayer == null || targetPlayer.isRemoved()) return;
        
        Inventory targetInv = targetPlayer.getInventory();
        
        // Main inventory
        for (int i = 0; i < 36; i++) {
            ItemStack guiStack = this.chestContainer.getItem(i);
            ItemStack targetStack = targetInv.items.get(i);
            
            // Only sync if different
            if (!ItemStack.matches(guiStack, targetStack)) {
                targetInv.items.set(i, guiStack.copy());
            }
        }
        
        // Armor
        for (int i = 0; i < 4; i++) {
            ItemStack guiStack = this.chestContainer.getItem(36 + i);
            ItemStack targetStack = targetInv.armor.get(i);
            
            if (!ItemStack.matches(guiStack, targetStack)) {
                targetInv.armor.set(i, guiStack.copy());
            }
        }
        
        // Offhand
        ItemStack guiStack = this.chestContainer.getItem(40);
        ItemStack targetStack = targetInv.offhand.get(0);
        
        if (!ItemStack.matches(guiStack, targetStack)) {
            targetInv.offhand.set(0, guiStack.copy());
        }
        
        // Notify client
        targetPlayer.inventoryMenu.broadcastChanges();
    }
    
    /**
     * Sync changes from target player to GUI
     */
    private void syncFromTarget() {
        if (targetPlayer == null || targetPlayer.isRemoved()) return;
        
        Inventory targetInv = targetPlayer.getInventory();
        
        // Main inventory
        for (int i = 0; i < 36; i++) {
            ItemStack targetStack = targetInv.items.get(i);
            ItemStack guiStack = this.chestContainer.getItem(i);
            
            if (!ItemStack.matches(targetStack, guiStack)) {
                this.chestContainer.setItem(i, targetStack.copy());
            }
        }
        
        // Armor
        for (int i = 0; i < 4; i++) {
            ItemStack targetStack = targetInv.armor.get(i);
            ItemStack guiStack = this.chestContainer.getItem(36 + i);
            
            if (!ItemStack.matches(targetStack, guiStack)) {
                this.chestContainer.setItem(36 + i, targetStack.copy());
            }
        }
        
        // Offhand
        ItemStack targetStack = targetInv.offhand.get(0);
        ItemStack guiStack = this.chestContainer.getItem(40);
        
        if (!ItemStack.matches(targetStack, guiStack)) {
            this.chestContainer.setItem(40, targetStack.copy());
        }
    }
    
    @Override
    public void removed(Player player) {
        super.removed(player);
        
        // Final sync when closing
        syncToTarget();
        
        ChatUI.showSuccess((ServerPlayer) player, 
            Component.translatable("invbackups.success.player_inventory_updated",
                Component.literal(targetPlayer.getScoreboardName()).withStyle(ChatFormatting.WHITE)).getString());
    }
}
```

#### **최적화 버전 (Hash 기반)**

```java
private static class ChestEditableMenu extends ChestMenu {
    private int tickCounter = 0;
    private static final int SYNC_INTERVAL = 10; // 10틱 (0.5초)
    
    // 이전 상태의 해시값 저장
    private int lastGuiHash = 0;
    private int lastTargetHash = 0;

    @Override
    public void broadcastFullState() {
        super.broadcastFullState();
        
        tickCounter++;
        if (tickCounter >= SYNC_INTERVAL) {
            tickCounter = 0;
            
            // 변경사항이 있을 때만 동기화
            int currentGuiHash = calculateGuiHash();
            int currentTargetHash = calculateTargetHash();
            
            if (currentGuiHash != lastGuiHash) {
                syncToTarget();
                lastGuiHash = currentGuiHash;
            }
            
            if (currentTargetHash != lastTargetHash) {
                syncFromTarget();
                lastTargetHash = currentTargetHash;
            }
        }
    }
    
    private int calculateGuiHash() {
        int hash = 0;
        for (int i = 0; i < 41; i++) {
            ItemStack stack = this.chestContainer.getItem(i);
            hash = 31 * hash + stack.hashCode();
        }
        return hash;
    }
    
    private int calculateTargetHash() {
        if (targetPlayer == null || targetPlayer.isRemoved()) return 0;
        
        int hash = 0;
        Inventory inv = targetPlayer.getInventory();
        
        for (ItemStack stack : inv.items) {
            hash = 31 * hash + stack.hashCode();
        }
        for (ItemStack stack : inv.armor) {
            hash = 31 * hash + stack.hashCode();
        }
        for (ItemStack stack : inv.offhand) {
            hash = 31 * hash + stack.hashCode();
        }
        
        return hash;
    }
}
```

---

### 방안 2: 이벤트 기반 동기화

#### **개념**
컨테이너 슬롯 변경 이벤트를 감지하여 즉시 동기화

```java
@Override
public void setItem(int slot, int stateId, ItemStack stack) {
    super.setItem(slot, stateId, stack);
    
    // 슬롯이 변경될 때마다 즉시 동기화
    syncSlotToTarget(slot, stack);
}

private void syncSlotToTarget(int slot, ItemStack stack) {
    if (targetPlayer == null || targetPlayer.isRemoved()) return;
    
    Inventory targetInv = targetPlayer.getInventory();
    
    if (slot < 36) {
        targetInv.items.set(slot, stack.copy());
    } else if (slot < 40) {
        targetInv.armor.set(slot - 36, stack.copy());
    } else if (slot == 40) {
        targetInv.offhand.set(0, stack.copy());
    }
    
    targetPlayer.inventoryMenu.broadcastChanges();
}
```

#### **장점**
✅ 완전한 실시간 (즉시 반영)  
✅ 오버헤드 최소 (변경 시에만)  

#### **단점**
❌ 역방향 동기화 어려움 (대상 → GUI)  
❌ `setItem` 메서드가 모든 변경을 캐치하지 못할 수 있음  
❌ Shift+클릭 등 복잡한 상호작용 처리 어려움  

---

### 방안 3: Proxy Container 패턴 (고급)

#### **개념**
`SimpleContainer` 대신 대상 플레이어의 `Inventory`를 직접 참조하는 프록시 생성

```java
private static class InventoryProxyContainer implements Container {
    private final Inventory targetInventory;
    
    @Override
    public ItemStack getItem(int slot) {
        // 직접 대상 인벤토리에서 읽기
        if (slot < 36) return targetInventory.items.get(slot);
        else if (slot < 40) return targetInventory.armor.get(slot - 36);
        else if (slot == 40) return targetInventory.offhand.get(0);
        return ItemStack.EMPTY;
    }
    
    @Override
    public void setItem(int slot, ItemStack stack) {
        // 직접 대상 인벤토리에 쓰기
        if (slot < 36) targetInventory.items.set(slot, stack);
        else if (slot < 40) targetInventory.armor.set(slot - 36, stack);
        else if (slot == 40) targetInventory.offhand.set(0, stack);
        
        // 클라이언트에 알림
        targetPlayer.inventoryMenu.broadcastChanges();
    }
}
```

#### **장점**
✅ 완전한 실시간 (진짜 실시간)  
✅ 추가 동기화 로직 불필요  
✅ 양방향 자동 동기화  

#### **단점**
❌ 구현 복잡도 매우 높음  
❌ `Container` 인터페이스 모든 메서드 구현 필요  
❌ 예기치 않은 버그 발생 가능성  
❌ Curios 버튼 등 커스텀 슬롯 처리 복잡  

---

## 🎯 권장 방안: 방안 1 (Tick 기반)

### 선택 이유
1. ✅ **구현 난이도:** 낮음 (4-6시간)
2. ✅ **안정성:** 높음 (기존 코드 변경 최소)
3. ✅ **성능:** 충분함 (0.25초 딜레이는 허용 가능)
4. ✅ **양방향 동기화:** 가능
5. ✅ **유지보수:** 쉬움

### 실제 체감
```
현재: 창 닫을 때 (수 초 ~ 수 분 후)
개선 후: 0.25초마다

사용자 입장: "거의 실시간"으로 느껴짐
```

---

## 📝 구현 체크리스트

### Phase 1: 기본 동기화 구현
- [ ] `ChestEditableMenu`에 `tickCounter` 추가
- [ ] `broadcastFullState()` 오버라이드
- [ ] `syncToTarget()` 메서드 구현
- [ ] `syncFromTarget()` 메서드 구현
- [ ] `removed()` 최종 동기화 유지

### Phase 2: 최적화
- [ ] Hash 기반 변경 감지 구현
- [ ] 불필요한 동기화 스킵
- [ ] 설정 파일에 동기화 주기 추가

### Phase 3: 엔더 상자 적용
- [ ] `EnderChestEditableMenu` 동일한 로직 적용
- [ ] 엔더 상자 27 슬롯 동기화

### Phase 4: Curios 적용
- [ ] `CuriosEditableMenu` 동일한 로직 적용
- [ ] Curios 슬롯 동기화

### Phase 5: 테스트
- [ ] 단일 슬롯 변경 테스트
- [ ] Shift+클릭 테스트
- [ ] 대상 플레이어가 아이템 사용 시 반영 테스트
- [ ] 동시에 여러 관리자가 같은 플레이어 열었을 때 동작 확인
- [ ] 성능 테스트 (서버 틱 영향)

### Phase 6: 설정 및 문서
- [ ] `InventoryConfig`에 동기화 설정 추가
- [ ] README 업데이트
- [ ] 번역 추가 (실시간 동기화 안내)

---

## ⚙️ 설정 옵션

```toml
# config/inventory/InventoryBackups.toml
[general]
    # 실시간 인벤토리 동기화 활성화
    realtimeSyncEnabled = true
    
    # 동기화 주기 (틱 단위, 20틱 = 1초)
    # 5틱 = 0.25초 (권장)
    # 10틱 = 0.5초
    # 20틱 = 1초
    syncIntervalTicks = 5
    
    # 양방향 동기화 (대상 플레이어 → GUI)
    bidirectionalSync = true
```

---

## 🧪 테스트 시나리오

### 테스트 1: 기본 동기화
```
1. 관리자: /inventory player Steve
2. 관리자가 다이아몬드 검 추가
3. ✅ 0.25초 이내에 Steve 화면에 다이아몬드 검 표시
4. Steve가 다이아몬드 검 사용 (내구도 감소)
5. ✅ 0.25초 이내에 관리자 GUI에 내구도 반영
```

### 테스트 2: Shift+클릭
```
1. 관리자: /inventory player Steve
2. 관리자가 여러 아이템 Shift+클릭으로 이동
3. ✅ 모든 아이템이 Steve 화면에 표시
```

### 테스트 3: 대상 플레이어 상호작용
```
1. 관리자: /inventory player Steve (GUI 열기)
2. Steve가 아이템 사용 (예: 음식 먹기)
3. ✅ 관리자 GUI에서 음식 개수 감소 확인
4. Steve가 몬스터에게 피해 (방어구 내구도 감소)
5. ✅ 관리자 GUI에서 방어구 내구도 감소 확인
```

### 테스트 4: 동시 접근
```
1. 관리자A: /inventory player Steve
2. 관리자B: /inventory player Steve (동시에)
3. 관리자A가 아이템 추가
4. ✅ 관리자B의 GUI에도 반영
5. ✅ Steve에게도 반영
```

### 테스트 5: 성능
```
1. 20명의 관리자가 각각 다른 플레이어 열기
2. ✅ 서버 TPS 정상 (19.8 이상)
3. ✅ 눈에 띄는 렉 없음
```

### 테스트 6: 엣지 케이스
```
1. 관리자: /inventory player Steve (GUI 열기)
2. Steve가 로그아웃
3. ✅ 관리자 GUI 자동 닫힘 (또는 에러 없이 동작)
4. Steve가 재접속
5. ✅ 마지막 동기화된 상태 유지
```

---

## ⚠️ 주의사항

### 1. 동시 접근 처리
**문제:** 여러 관리자가 동시에 같은 플레이어의 인벤토리를 열 경우
**해결:** 
- 각 `ChestEditableMenu`가 독립적으로 동기화
- "Last Write Wins" 방식
- 또는 첫 번째 관리자만 편집 가능하도록 제한 (선택적)

```java
// 선택적: 락 시스템
private static final Map<UUID, ServerPlayer> EDITING_LOCKS = new ConcurrentHashMap<>();

public int viewCurrentInventory(CommandSourceStack source, String targetName) {
    // ...
    
    if (EDITING_LOCKS.containsKey(target.getUUID())) {
        ServerPlayer editor = EDITING_LOCKS.get(target.getUUID());
        ChatUI.showError(executor, 
            Component.translatable("invbackups.error.already_editing", 
                target.getScoreboardName(), 
                editor.getScoreboardName()).getString());
        return 0;
    }
    
    EDITING_LOCKS.put(target.getUUID(), executor);
    // ...
}
```

### 2. 성능 고려사항
- **Worst Case:** 100명 플레이어 서버, 10명 관리자가 동시에 인벤토리 열기
- **부하:** 10개 메뉴 × 41 슬롯 × 5틱마다 = 초당 약 82회 비교
- **해결:** Hash 기반 변경 감지로 불필요한 복사 방지

### 3. Curios 버튼 보호
```java
private void syncToTarget() {
    // ...
    
    for (int i = 0; i < 36; i++) {
        // Skip button slots
        if (i == 48) continue; // Curios button
        
        // Sync logic...
    }
}
```

### 4. 네트워크 대역폭
- 각 동기화마다 클라이언트로 패킷 전송
- `broadcastChanges()` 호출 시 변경된 슬롯만 전송 (Minecraft 최적화)
- 대부분의 경우 영향 무시 가능

---

## 📊 예상 영향

### 성능
- **CPU:** +0.01% ~ +0.1% (틱당 Hash 계산 및 비교)
- **메모리:** 무시 가능 (메뉴당 8 bytes 추가)
- **네트워크:** 변경 시에만 패킷 전송 (기존과 동일)

### 사용자 경험
- ✅ "실시간" 편집 체험
- ✅ 관리자와 대상 플레이어 간 혼란 제거
- ✅ 직관적인 동작

---

## 🚀 예상 타임라인

- **Phase 1:** 기본 구현 (2시간)
  - `syncToTarget()` / `syncFromTarget()` 구현
  - `broadcastFullState()` 오버라이드
  
- **Phase 2:** 최적화 (1시간)
  - Hash 기반 변경 감지
  - 설정 옵션 추가
  
- **Phase 3:** 엔더 상자/Curios 적용 (1시간)
  - `EnderChestEditableMenu` 동기화
  - `CuriosEditableMenu` 동기화
  
- **Phase 4:** 테스트 (1.5시간)
  - 모든 테스트 시나리오 검증
  
- **Phase 5:** 문서 및 번역 (0.5시간)
  - README 업데이트
  - 번역 추가

**총 예상 시간: 6시간**

---

## 🎯 성공 기준

- ✅ 관리자가 아이템 추가/제거 시 0.5초 이내 대상 플레이어에게 반영
- ✅ 대상 플레이어가 아이템 사용 시 0.5초 이내 관리자 GUI에 반영
- ✅ 서버 TPS 영향 < 0.1 (19.9 이상 유지)
- ✅ 동시 접근 시 충돌 없음
- ✅ 모든 테스트 시나리오 통과

---

## 📚 참고 코드

### 유사 구현 사례

#### **LuckPerms Editor (웹 기반)**
- 실시간 권한 편집
- WebSocket으로 서버와 동기화

#### **EssentialsX `/invsee`**
- 실시간 인벤토리 보기/편집
- 이벤트 기반 동기화

#### **WorldEdit Selection**
- 실시간 선택 영역 업데이트
- Tick 기반 렌더링

---

**작성자:** Pocky  
**프로젝트:** InventoryLogger  
**버전:** v2.1 (예정)
