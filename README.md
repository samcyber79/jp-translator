# 일본어 화면번역 (Z플립용)

어떤 앱 위에서든 화면의 일본어를 찾아 **원문 위치에 한국어 번역을 덮어서** 보여주는 안드로이드 앱입니다.

- 가로쓰기·세로쓰기(만화, 소설) 모두 인식, 세로쓰기는 번역도 세로로 표시 (설정에서 가로 표시로 변경 가능)
- 화면을 가로/세로로 돌려도 캡처 크기를 자동으로 맞춤
- 실시간 모드: 스크롤·페이지 넘김이 멈추면 자동으로 다시 번역
- OCR·번역 모두 기기 안에서 처리 (Google ML Kit), 번역 모델만 처음 한 번 다운로드
- Android 11 이상 (Z플립3 ~ 최신 Z플립)

---

## APK 만들기

### 방법 A. GitHub에서 자동 빌드 (PC 프로그램 설치 불필요)

1. [github.com](https://github.com)에 로그인 → 오른쪽 위 **+ → New repository** → 이름 입력(예: `jp-translator`) → **Create repository**
2. 만들어진 저장소 화면에서 **uploading an existing file** 클릭
3. 압축을 푼 `JpScreenTranslator` 폴더 **안의 내용물 전체**를 드래그해서 올리고 **Commit changes**
   - `.github` 폴더(숨김 폴더)도 꼭 함께 올라가야 합니다. 빠졌다면 아래 "워크플로 직접 만들기" 참고
4. 저장소의 **Actions** 탭 → `Build APK`가 자동 실행됨 (약 4~6분)
5. 초록색 체크가 뜨면 그 실행을 눌러 맨 아래 **Artifacts → JpScreenTranslator-apk** 다운로드
6. zip 안의 `app-release.apk`를 폰으로 옮겨 설치 (출처를 알 수 없는 앱 설치 허용 필요)

> **워크플로 직접 만들기**: Actions 탭 → *set up a workflow yourself* → 파일 이름을 `build-apk.yml`로 하고
> `.github/workflows/build-apk.yml` 내용을 그대로 붙여넣은 뒤 Commit.

### 방법 B. Android Studio

1. Android Studio에서 `JpScreenTranslator` 폴더 열기 (Gradle 동기화 자동 진행)
2. 메뉴 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**
3. `app/build/outputs/apk/debug/app-debug.apk` 생성
   - 폰을 USB 디버깅으로 연결했다면 ▶ Run으로 바로 설치해도 됩니다.

APK는 저장소에 포함된 `app/personal.jks` 키로 서명되므로, 코드를 고쳐 다시 빌드해도 **삭제 없이 덮어쓰기 설치**가 됩니다.
(개인용 키입니다. 공개 배포할 거라면 새 키로 바꾸세요.)

---

## 사용법

1. 앱 실행 → **다른 앱 위에 표시 허용** → **알림 허용** → **번역 모델 받기** (Wi-Fi 권장)
2. **▶ 번역기 시작** → 화면 공유 팝업에서 **전체 화면** 선택 후 시작
3. 화면에 뜬 「訳」 버튼
   - **탭**: 지금 화면 번역 / 다시 탭하면 지우기
   - **길게 누르기**: 실시간 모드 ON/OFF (테두리가 초록색이면 ON)
   - **드래그**: 위치 이동
4. 번역이 떠 있어도 아래 앱은 그대로 스크롤·터치 가능. 화면이 바뀌면 지난 번역은 자동으로 사라짐
5. 끄기: 알림의 **[종료]** 또는 상태바의 화면 공유 중지

### 알아두면 좋은 점
- 커버 화면(접은 상태 바깥 화면)은 캡처 대상이 아니어서 번역되지 않습니다.
- 번역 상자는 터치를 통과시키기 위해 안드로이드 규칙상 약 80% 불투명도로 그려져서, 원문이 아주 살짝 비쳐 보입니다.
- 동영상처럼 계속 움직이는 화면에서는 실시간 모드가 "멈춤"을 기다리느라 번역하지 않습니다. 이때는 버튼을 탭하세요.
- 넷플릭스 등 캡처 금지(보안) 화면은 검게 캡처되어 번역할 수 없습니다.

---

## 코드 구조

| 파일 | 역할 |
|---|---|
| `MainActivity.kt` | 권한 요청, 모델 다운로드, 설정, 화면 캡처 동의 받기 |
| `ScreenTranslateService.kt` | 포그라운드 서비스. 캡처→OCR→번역→표시 상태 머신, 화면 변화 감지, 회전 처리 |
| `ScreenCapturer.kt` | MediaProjection → VirtualDisplay → ImageReader, 프레임 지문(변화 감지용), 회전 시 `VirtualDisplay.resize()` |
| `JapaneseOcr.kt` | ML Kit 일본어 OCR, 가로/세로쓰기 판별, 읽는 순서 정렬, 쪼개진 세로쓰기 열 병합 |
| `JaKoTranslator.kt` | ML Kit 기기 내 번역 (ja→ko) + LRU 캐시 |
| `TranslationOverlayView.kt` | 원문 위치에 번역 그리기 (글자 크기 자동 맞춤, 세로쓰기 렌더링) |
| `BubbleController.kt` | 떠 있는 「訳」 버튼 (탭/길게/드래그) |

### 튜닝 포인트 (`ScreenTranslateService` companion)
- `STABLE_MS` (450ms): 화면이 이만큼 멈춰 있어야 실시간 재번역 — 줄이면 빠르지만 스크롤 중 오인식 증가
- `TICK_MS` (150ms): 변화 감지 주기
- `ScreenCapturer.Signature.CELL_THRESHOLD`, `differsFrom()`의 변화 칸 수: 변화 감지 민감도

### 번역 품질을 더 높이고 싶다면
ML Kit 번역은 무료·오프라인이지만 품질은 평범합니다. `JaKoTranslator.translate()`만 바꾸면
Papago, DeepL, Google Cloud Translation 같은 온라인 API로 교체할 수 있습니다 (API 키 필요).
