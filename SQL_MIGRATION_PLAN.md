# InventoryLogger SQL 마이그레이션 계획

**작성일:** 2025-01-22  
**상태:** 계획 단계 (성능 평가 후 결정)

---

## 📋 마이그레이션 목표

### 해결할 문제

#### 1. 명령어 백업 날짜 자동완성 성능 문제
**현상:**
```
/inventory set PlayerName <TAB>
→ 2025-01-22-15-30-45-death
→ 2025-01-22-14-20-30
→ ... (수백 개)
```
- 명령어에서 백업 파일명(날짜) 입력 시 TAB 자동완성 제공
- 백업 500개 시 `sorted()` 메서드가 전체 파일 정렬 필요 (500ms 지연)
- SQL: 인덱스로 최신 50개만 쿼리 (2ms) → **250배 개선**

#### 2. 대용량 백업 목록 조회 속도
**현상:**
```
/inventory list PlayerName
```
- 파일 시스템 탐색으로 백업 목록 조회 (500개 시 500ms)
- SQL: 인덱싱된 쿼리로 즉시 조회 (3ms) → **166배 개선**

#### 3. 통계 및 분석 기능 부재
**현재 불가능:**
- 플레이어별 총 백업 수
- 백업 타입별 통계 (auto/death/join 등)
- 특정 기간 백업 검색
- 배낭/Curios 포함 백업 필터링

#### 4. 백업 자동 정리 효율
**현상:**
```
retentionDays = 7 (7일 이상 백업 자동 삭제)
```
- 현재: 모든 파일 스캔 후 삭제 (2초)
- SQL: WHERE 조건으로 일괄 삭제 (50ms) → **40배 개선**

### 현재 상태
- ✅ 설정 핫리로드 지원 (NeoForge ModConfigSpec)
- ✅ 파일 위치: `config/inventory/InventoryBackups.toml`
- ✅ 변경 시 자동 반영

---

## 🚀 Phase 0: 준비

### 의존성 추가 (build.gradle)
```gradle
dependencies {
    implementation 'org.xerial:sqlite-jdbc:3.44.1.0'
    implementation 'com.zaxxer:HikariCP:5.1.0'  // Connection Pool
}
```

### 설정 확장 (InventoryConfig.java)
```java
public final ModConfigSpec.EnumValue<StorageType> storageType;
public final ModConfigSpec.ConfigValue<String> databasePath;

storageType = COMMON_BUILDER
    .comment("Storage: JSON or SQL")
    .defineEnum("storageType", StorageType.JSON);

databasePath = COMMON_BUILDER
    .define("databasePath", "InventoryLog/inventorybackups.db");

enum StorageType { JSON, SQL }
```

---

## 📊 Phase 1: 데이터베이스 설계

### 핵심 테이블

```sql
-- 플레이어
CREATE TABLE players (
    uuid TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    last_seen INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);

-- 인벤토리 백업 메타데이터
CREATE TABLE inventory_backups (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid TEXT NOT NULL,
    backup_name TEXT NOT NULL,
    backup_type TEXT NOT NULL,  -- auto, death, join, quit, manual
    created_at INTEGER NOT NULL,
    slot_count INTEGER NOT NULL,
    has_curios BOOLEAN DEFAULT 0,
    has_backpacks BOOLEAN DEFAULT 0,
    FOREIGN KEY (player_uuid) REFERENCES players(uuid)
);

-- 인벤토리 슬롯 데이터
CREATE TABLE inventory_slots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    backup_id INTEGER NOT NULL,
    slot_index INTEGER NOT NULL,
    item_nbt TEXT NOT NULL,  -- JSON
    FOREIGN KEY (backup_id) REFERENCES inventory_backups(id) ON DELETE CASCADE
);

-- 배낭 스냅샷
CREATE TABLE backpack_snapshots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    backup_id INTEGER NOT NULL,
    backpack_uuid TEXT NOT NULL,
    snapshot_nbt TEXT NOT NULL,
    FOREIGN KEY (backup_id) REFERENCES inventory_backups(id) ON DELETE CASCADE
);

-- 엔더 상자 백업
CREATE TABLE enderchest_backups (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid TEXT NOT NULL,
    backup_name TEXT NOT NULL,
    backup_type TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (player_uuid) REFERENCES players(uuid)
);

-- 엔더 상자 슬롯
CREATE TABLE enderchest_slots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    backup_id INTEGER NOT NULL,
    slot_index INTEGER NOT NULL,
    item_nbt TEXT NOT NULL,
    FOREIGN KEY (backup_id) REFERENCES enderchest_backups(id) ON DELETE CASCADE
);

-- 인덱스 (성능)
CREATE INDEX idx_inv_player_uuid ON inventory_backups(player_uuid);
CREATE INDEX idx_inv_created_at ON inventory_backups(created_at);
CREATE INDEX idx_inv_backup_type ON inventory_backups(backup_type);
CREATE UNIQUE INDEX idx_inv_player_name ON inventory_backups(player_uuid, backup_name);
```

---

## 🏗️ Phase 2: 코드 구조

### 새 패키지
```
com.pocky.invbackups/
├── storage/
│   ├── StorageProvider.java      [인터페이스]
│   ├── JsonStorage.java           [기존]
│   ├── SqlStorage.java            [신규]
│   └── StorageFactory.java        [팩토리]
├── database/
│   ├── DatabaseManager.java
│   └── ConnectionPool.java
├── repository/
│   ├── InventoryRepository.java
│   └── EnderChestRepository.java
├── migration/
│   └── JsonToSqlMigrator.java
└── dto/
    ├── BackupInfo.java
    └── PlayerInfo.java
```

### StorageProvider 인터페이스
```java
public interface StorageProvider {
    void saveInventory(UUID uuid, String name, String backupName, String type, InventoryData data);
    InventoryData loadInventory(UUID uuid, String backupName);
    List<BackupInfo> listInventoryBackups(UUID uuid, String filter, int page, int pageSize);
    boolean deleteInventoryBackup(UUID uuid, String backupName);
    
    void saveEnderChest(UUID uuid, String name, String backupName, String type, EnderChestData data);
    EnderChestData loadEnderChest(UUID uuid, String backupName);
    List<BackupInfo> listEnderChestBackups(UUID uuid, String filter, int page, int pageSize);
    boolean deleteEnderChestBackup(UUID uuid, String backupName);
    
    List<PlayerInfo> getPlayersWithBackups();
    int cleanupOldBackups(int retentionDays);
    void close();
}
```

---

## 💻 Phase 3: 구현 핵심

### DatabaseManager
```java
public class DatabaseManager {
    private static Connection connection;
    
    public static void initialize(String dbPath) throws SQLException {
        String url = "jdbc:sqlite:" + dbPath;
        connection = DriverManager.getConnection(url);
        
        // SQLite 최적화
        try (var stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=NORMAL");
            stmt.execute("PRAGMA foreign_keys=ON");
        }
        
        SchemaInitializer.createTables(connection);
    }
    
    public static Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            throw new SQLException("Database not initialized");
        }
        return connection;
    }
}
```

### SqlStorage (핵심 메서드)
```java
public class SqlStorage implements StorageProvider {
    
    @Override
    public List<BackupInfo> listInventoryBackups(UUID uuid, String filter, int page, int pageSize) {
        String sql = """
            SELECT backup_name, backup_type, created_at, slot_count, has_curios, has_backpacks
            FROM inventory_backups
            WHERE player_uuid = ? AND (? = '' OR backup_name LIKE ?)
            ORDER BY created_at DESC
            LIMIT ? OFFSET ?
        """;
        
        try (PreparedStatement stmt = DatabaseManager.getConnection().prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, filter);
            stmt.setString(3, "%" + filter + "%");
            stmt.setInt(4, pageSize);
            stmt.setInt(5, (page - 1) * pageSize);
            
            // ResultSet → BackupInfo 변환
            return convertToBackupInfoList(stmt.executeQuery());
        }
    }
    
    @Override
    public int cleanupOldBackups(int retentionDays) {
        long cutoff = System.currentTimeMillis() - (retentionDays * 86400000L);
        String sql = "DELETE FROM inventory_backups WHERE created_at < ?";
        
        try (PreparedStatement stmt = DatabaseManager.getConnection().prepareStatement(sql)) {
            stmt.setLong(1, cutoff);
            return stmt.executeUpdate();  // CASCADE로 slots도 삭제
        }
    }
}
```

### StorageFactory
```java
public class StorageFactory {
    private static StorageProvider instance;
    
    public static void initialize() {
        StorageType type = InventoryConfig.general.storageType.get();
        
        instance = switch (type) {
            case JSON -> new JsonStorage();
            case SQL -> {
                String dbPath = InventoryConfig.general.databasePath.get();
                DatabaseManager.initialize(dbPath);
                yield new SqlStorage();
            }
        };
    }
    
    public static StorageProvider get() {
        return instance;
    }
}
```

---

## 🔄 Phase 4: 마이그레이션

### JsonToSqlMigrator
```java
public class JsonToSqlMigrator {
    
    public static MigrationResult migrate(boolean deleteJson) {
        SqlStorage storage = new SqlStorage();
        int count = 0;
        
        Path inventoryDir = Path.of("InventoryLog/inventory");
        
        for (Path playerDir : Files.list(inventoryDir).toList()) {
            UUID uuid = UUID.fromString(playerDir.getFileName().toString());
            
            for (Path file : Files.list(playerDir).filter(p -> p.toString().endsWith(".json")).toList()) {
                String name = file.getFileName().toString().replace(".json", "");
                InventoryData data = JsonFileHandler.load("inventory/" + uuid, name, InventoryData.class);
                
                if (data != null) {
                    String type = name.endsWith("-death") ? "death" : "auto";
                    storage.saveInventory(uuid, "Unknown", name, type, data);
                    
                    if (deleteJson) Files.delete(file);
                    count++;
                }
            }
        }
        
        return new MigrationResult(true, count);
    }
}
```

### 마이그레이션 명령어
```java
.then(Commands.literal("migrate")
    .requires(cs -> cs.hasPermission(4))
    .then(Commands.literal("json-to-sql")
        .executes(context -> {
            CompletableFuture.runAsync(() -> {
                MigrationResult result = JsonToSqlMigrator.migrate(false);
                // 결과 메시지 전송
            });
            return 1;
        })))
```

---

## 📈 예상 성능 (백업 500개 기준)

| 작업 | JSON | SQL | 개선율 |
|------|------|-----|--------|
| 목록 조회 50개 | 500ms | 3ms | 166배 |
| 검색 | 800ms | 2ms | 400배 |
| 자동완성 | 500ms | 2ms | 250배 |
| 통계 | 불가능 | 2ms | ∞ |
| 정리 (7일) | 2000ms | 50ms | 40배 |

---

## 🎯 마이그레이션 타임라인

- **Week 1:** Phase 0-1 (준비 + DB 설계)
- **Week 2:** Phase 2 (코드 구조)
- **Week 3:** Phase 3 (구현)
- **Week 4:** Phase 4 (마이그레이션 도구)
- **Week 5:** 테스트 & 버그 수정

---

## 📝 체크리스트

### 구현 전
- [ ] build.gradle 의존성 추가
- [ ] InventoryConfig 확장
- [ ] 테이블 스키마 최종 검토

### 구현 중
- [ ] StorageProvider 인터페이스
- [ ] DatabaseManager
- [ ] SqlStorage 구현
- [ ] StorageFactory
- [ ] JsonToSqlMigrator

### 구현 후
- [ ] 단위 테스트
- [ ] 성능 벤치마크
- [ ] 마이그레이션 테스트
- [ ] 문서 업데이트

---

## ⚠️ 주의사항

1. **백업:** 마이그레이션 전 JSON 파일 백업 필수
2. **트랜잭션:** 백업 저장 시 롤백 처리
3. **인덱스:** 대용량 데이터에서 필수
4. **Connection Pool:** 멀티플레이어 서버에서 권장
5. **WAL 모드:** SQLite 성능 향상 필수

---

## 🔗 참고 자료

- SQLite WAL: https://www.sqlite.org/wal.html
- HikariCP: https://github.com/brettwooldridge/HikariCP
- JDBC Best Practices: https://docs.oracle.com/javase/tutorial/jdbc/
