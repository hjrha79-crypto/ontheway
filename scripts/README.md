# OnTheWay DevOps Agent v0

일일 개발 마감 자동화 스크립트.

## 사용법

```bash
# Git Bash 또는 터미널에서
scripts/devops_daily.sh

# Windows CMD에서
scripts\devops_daily.bat
```

## 출력 파일

| 파일 | 용도 |
|------|------|
| `scripts/output/daily_report.md` | 종합 보고서 (대표가 읽음) |
| `scripts/output/codex_review_prompt.txt` | Codex 복붙 가능 프롬프트 |
| `scripts/output/build_result.txt` | 빌드 raw 출력 |
| `scripts/output/test_result.txt` | 테스트 raw 출력 |

## 자동 실행 항목

1. git status
2. git log --oneline -10
3. gradlew test
4. gradlew assembleDebug
5. 실패 로그 핵심 추출
6. APK 생성 여부 + 크기
7. git diff --stat (변경 파일 + LOC)
8. 위험 파일 감지 (1000줄+, 테스트 없음, TODO)
9. Codex prompt 자동 생성

## READ-ONLY MODE

이 스크립트는 **절대로**:
- git commit / push 하지 않음
- APK 설치하지 않음
- 코드 수정하지 않음
- 배포하지 않음

모든 결정 = 대표 수동.
