# 일정 체크 (안드로이드 앱)

아이 탭에서 시간표 항목을 체크하면 보호자 폰에서 실시간으로 확인하고, 통계·주간 리뷰·세계사 독서 기록을 함께 보는 가족용 앱입니다.

- 화면: `app/src/main/assets/index.html` (앱 안의 WebView로 표시)
- 데이터: Firebase Firestore (`families/<가족코드>/...`), 익명 로그인
- 홈 화면 위젯: 오늘 진행률·지금/다음 할 일·할 일 목록 (위젯 설정: 목록형/요약형, 배경, 완료 숨기기)
- 대상 기기: 갤럭시 노트9 (One UI 1.0 / Android 9) 이상, minSdk 26

## APK 받기
`main` 브랜치에 push하면 GitHub Actions가 APK를 빌드해 **Releases**에 올립니다.
탭/폰 브라우저에서 이 저장소 → Releases → 최신 `kid-planner-v1.1.N.apk` 다운로드 → 설치 (이후 업데이트는 앱 ⚙ 설정 → 앱 업데이트 → 받기)
(처음엔 '출처를 알 수 없는 앱 설치'를 허용해야 합니다). 새 버전은 기존 앱 위에 그대로 업데이트됩니다.

## Firebase 준비 (한 번만)
1. Firebase 콘솔에서 프로젝트 생성
2. Authentication → 로그인 방법 → **익명** 사용 설정
3. Firestore Database 생성 (asia-northeast3 서울, 프로덕션 모드)
4. Firestore → 규칙 탭에 `firestore.rules` 내용을 붙여 넣고 게시
5. 프로젝트 설정 → 웹 앱 추가 → `firebaseConfig`를 복사해 앱 첫 화면에 붙여 넣기

## 첫 실행
1. 첫 기기: Firebase 설정 붙여넣기 → **처음 설치해요** → 가족 코드 확인 → 역할 선택
2. 두 번째 기기: 같은 Firebase 설정 → **코드가 있어요** → 가족 코드 입력 → 역할 선택
3. 아이 탭: 보호자 PIN 설정
4. 위젯: 홈 화면 빈 곳 길게 누르기 → 위젯 → 일정 체크 → 위젯 설정에서 고르고 추가

## 서명 키 (GitHub Secrets)
서명 키는 저장소에 넣지 않습니다. 저장소 Settings → Secrets and variables → Actions → New repository secret 에서
- `KEYSTORE_BASE64`: 서명 키(.jks)를 base64로 바꾼 글자
- `KEYSTORE_PASSWORD`: 키 비밀번호
를 등록하면 빌드됩니다. 키를 잃어버리면 기존 앱 위에 업데이트할 수 없으니 따로 안전하게 보관하세요.
