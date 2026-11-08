# Arena Agent

LMArena(`https://arena.ai/text/direct?model_a=max`) 웹을 AI 백엔드로 쓰고, Android 접근성 서비스로 기기를 직접 조작하는 에이전트 앱.

## 빌드 (GitHub Actions)
1. 이 폴더를 GitHub 저장소에 push (main/master)
2. Actions 탭 → **Build APK** → Artifacts 의 `arena-agent-debug-apk` 다운로드
3. APK 설치

## 첫 실행
1. 앱 실행 → 로딩 UI → Google 로그인 창 → 로그인 (쿠키는 저장되어 재실행 시 유지)
2. 상단 좌측 버튼 또는 "접근성 서비스 켜기" → **Arena Agent** 활성화
   - Android 13+ 사이드로드 앱: 앱 정보 → ⋮ → **제한된 설정 허용** 후 활성화
3. 명령 입력 (예: "설정 앱에서 Wi-Fi 상태 알려줘")

## 구조
- `ArenaController` : WebView 로 LMArena 로그인/전송/스트리밍/중단/새 세션 (DOM 주입)
- `WorkParser` : 응답의 ```work JSON 블록 해석
- `AgentViewModel` : 목표 달성까지 반복하는 에이전트 루프 (최대 40 step)
- `AgentAccessibilityService` : 화면 덤프 + tap/swipe/scroll/type/key/launch_app/open_url/wait
- `ui/Glass.kt` : Kyant0/AndroidLiquidGlass (backdrop 1.0.6) 기반 캡슐 글래스 UI
