# 보안 운영 지침

## 비밀정보 관리

- 실제 DB/Aspera Node 주소, 계정, 암호, 인증서, 토큰은 Git에 커밋하지 않습니다.
- `.env.example`을 `.env`로 복사한 뒤 운영 서버에서만 실제 값을 입력합니다. `.env`는 Git과 Docker 빌드 컨텍스트에서 제외됩니다.
- 노출된 적이 있는 DB/Node/관리자 자격 증명은 저장소 이력 삭제와 별개로 반드시 폐기하거나 교체합니다.
- clone마다 `git config core.hooksPath .githooks`를 실행합니다. `pre-commit`은 스테이징된 비밀정보를 검사하고, `pre-push`는 정리 전 과거 이력이 원격에 다시 유입되는 푸시를 거부합니다.

## TLS

Aspera Node의 인증서와 호스트 이름은 항상 검증합니다. 사설 CA나 자체 서명 인증서를 사용한다면 검증을 끄지 말고 Java truststore에 CA 또는 서버 인증서를 추가한 뒤 `JAVA_TOOL_OPTIONS`로 truststore 경로와 암호를 전달합니다.
관리자 화면에서 런타임 Node 주소 변경이 필요하면 허용할 정확한 HTTPS origin만 `ASPERA_NODE_ALLOWED_ORIGINS`에 쉼표로 지정합니다. 주소 또는 계정을 바꿀 때는 기존 암호가 재사용되지 않으며 새 Node 암호를 다시 입력해야 합니다.
원격 PostgreSQL도 `sslmode=verify-full`로 암호화·CA·호스트 이름을 모두 검증합니다. 운영 DB CA는 전용 `root.crt` 또는 운영 환경의 읽기 전용 `sslrootcert` 경로로만 배포하고 저장소, Docker build context, 이미지에 포함하지 않습니다.

## 최초 관리자

기본 계정은 자동으로 생성되지 않습니다. 최초 실행 시에만 `BOOTSTRAP_ADMIN_ENABLED=true`와 충분히 긴 임의 암호를 주입하고, 계정 생성 확인 즉시 enabled를 `false`로 바꾸며 bootstrap 사용자명·암호·이메일도 환경과 비밀관리 시스템에서 제거한 뒤 재시작합니다.

## 로그인 보호

애플리케이션은 단일 인스턴스 기준 IP·사용자명별 로그인 실패를 제한합니다. 여러 인스턴스나 인터넷 공개 환경에서는 신뢰된 TLS reverse proxy/WAF에도 분산 rate limit을 설정하고, 관리자 접근에는 MFA 또는 별도 인증 게이트를 적용합니다.
운영에서는 애플리케이션 포트를 외부에 직접 노출하지 말고, 전달 헤더를 보낼 수 있는 proxy 주소를 `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`로 제한합니다.
관리자 삭제·강등 세션 만료도 JVM 로컬이므로 공유 세션·전역 권한 철회 검증을 구현하기 전에는 애플리케이션을 수평 확장하지 않습니다.
세션 만료는 이후 웹 요청만 차단하며 이미 Connect에 전달된 Node transfer spec/token이나 진행 중 전송을 회수하지 못합니다. Node의 transfer token TTL을 업무상 가능한 짧은 값으로 유지하고, 긴급 권한 철회 시 Node에서 해당 사용자의 활성 전송·토큰을 취소하고 감사 로그를 확인하는 운영 절차를 함께 적용합니다.

## Connect SDK 공급망 경계

현재 파일 전송 화면은 IBM의 `connect/v4/asperaweb-4.min.js`를 사용합니다. 이 벤더 URL은 고정 버전이 아니므로 외부 인터넷 노출 또는 고보안 환경에서는 검증한 Connect SDK 전체 배포본을 같은 origin에서 자체 호스팅하고, 배포 파일의 해시와 업데이트 절차를 별도로 관리합니다. 일부 파일만 복사하지 말고 installer와 SDK location 의존 자산을 포함한 IBM의 전체 로컬 호스팅 절차를 적용합니다. Connect 지원 종료 일정에 맞춰 IBM Aspera JavaScript SDK 또는 후속 데스크톱 통합으로의 전환도 계획합니다.

## 신고

새로운 보안 문제를 발견하면 공개 이슈에 자격 증명이나 재현용 실데이터를 올리지 말고 저장소 소유자에게 비공개로 전달합니다.
