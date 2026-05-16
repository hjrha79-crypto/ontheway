# OnTheWay Scripts

## collect_otw — 디바이스 데이터 회수 + Ledger 분석

```bash
# Git Bash 또는 터미널에서
scripts/collect_otw.sh

# Windows CMD에서
scripts\collect_otw.bat
```

### 회수 대상

| 파일 | 용도 |
|------|------|
| `ledger.db` | P0-1 원장 (영구 보존) |
| `call_logs.db` | 운영 콜 로그 |
| `diagnostic.db` | 진단 로그 |
| `prefs/*.xml` | SharedPreferences |
| `logs/*` | OtwFileLogger 파일 |

### 자동 분석 (ledger_report.md)

| 섹션 | 내용 |
|------|------|
| 8.1 | Event Type 분포 |
| 8.2 | DRIVER_ACCEPTED Source 분포 |
| 8.3 | 거품 검증 (중복 ACCEPT, 매출 정확도) |
| 8.4 | Lifecycle 분포 |
| 8.5 | Raw 한글 보존 비율 |
| 8.6 | ledger.db 사이즈 + 성장 속도 |
| 8.7 | 정체성 6단계 종합 (GREEN/YELLOW/RED) |

### 환경 설정

- **ADB**: `OTW_ADB_PATH` 환경변수 또는 자동 감지
  - PATH 내 `adb`
  - `C:\platform-tools\adb.exe` (노트북)
  - `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` (데스크탑)
- **Python**: `python3` / `python` / `py` 자동 감지

### 출력

```
scripts/output/otw_2026_05_09_2130/
├── ledger.db
├── call_logs.db
├── diagnostic.db
├── prefs/
│   ├── ontheway.xml
│   ├── earnings_tracker.xml
│   └── ...
├── logs/
│   └── otw_*.txt
├── ledger_report.md
└── pull_log.txt
```

## devops_daily — 일일 빌드/테스트 보고

```bash
scripts/devops_daily.sh
scripts\devops_daily.bat
```

### READ-ONLY MODE

두 스크립트 모두 **절대로**:
- git commit / push 하지 않음
- APK 설치하지 않음
- 코드 수정하지 않음
- 디바이스 데이터 수정/삭제하지 않음
