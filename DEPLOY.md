# 배포 가이드

## 1. 운영 설정 준비

`.env.example`을 `.env`로 복사하고 운영 서버에서만 실제 값을 입력합니다. `.env`는 Git에 포함되지 않습니다.

최초 관리자 계정이 없을 때만 다음 값을 사용합니다.

1. `BOOTSTRAP_ADMIN_ENABLED=true`로 시작합니다.
2. 충분히 긴 임의의 관리자 암호를 입력합니다.
3. 로그인과 계정 생성을 확인합니다.
4. 계정 생성 확인 즉시 `BOOTSTRAP_ADMIN_ENABLED=false`로 바꾸고, `BOOTSTRAP_ADMIN_USERNAME`, `BOOTSTRAP_ADMIN_PASSWORD`, `BOOTSTRAP_ADMIN_EMAIL` 값을 `.env` 또는 비밀관리 시스템에서 제거한 뒤 재시작합니다.

기존 운영 DB라면 `JPA_DDL_AUTO=validate`를 유지합니다. 신규 DB를 처음 준비할 때만 변경 절차를 검토한 뒤 제한적으로 `update`를 사용합니다.
`ASPERA_NODE_ALLOWED_ORIGINS`에는 관리자 화면에서 변경을 허용할 정확한 HTTPS origin만 쉼표로 지정합니다. 예: `https://node-a.example.internal:9092,https://node-b.example.internal:9092`.
원격 PostgreSQL URL에는 `sslmode=verify-full`을 반드시 지정합니다. 운영 프로필은 원격 DB URL에 이 값이 없거나 다른 SSL 모드가 함께 있으면 시작을 거부합니다. DB 서버 이름과 인증서 SAN이 일치하도록 구성하고, 신뢰한 DB CA만 포함한 `root.crt`를 Linux의 `$HOME/.postgresql/root.crt`, Windows의 `%APPDATA%\\postgresql\\root.crt`에 배치하거나 JDBC `sslrootcert`로 운영 환경의 읽기 전용 경로를 지정합니다. CA 파일과 경로별 자격 증명은 저장소나 이미지에 넣지 않습니다.

기존 DB의 `folder_permissions.path`가 255자라면 배포 전에 백업을 확인한 뒤 다음 마이그레이션을 실행합니다. 성공 후 `character_maximum_length=4096`을 확인하고 애플리케이션을 시작합니다.

```bash
psql "$POSTGRESQL_MIGRATION_URL" -v ON_ERROR_STOP=1 -f scripts/postgresql-migrate-permission-path.sql
psql "$POSTGRESQL_MIGRATION_URL" -Atc "select character_maximum_length from information_schema.columns where table_name='folder_permissions' and column_name='path'"
```

`POSTGRESQL_MIGRATION_URL`은 `psql`이 이해하는 `postgresql://` 연결 문자열을 운영 비밀관리 시스템에서 일시 주입하고, 명령 이력과 로그에 값이 출력되지 않게 관리한 뒤 작업 후 제거합니다.

## 2. Docker 배포

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps
docker compose logs --tail=200 aspera-web
```

컨테이너는 비루트 사용자, 읽기 전용 파일시스템, 모든 Linux capability 제거 상태로 실행됩니다.
애플리케이션 포트는 호스트의 `127.0.0.1`에만 바인딩됩니다. 같은 호스트의 신뢰된 TLS reverse proxy를 통해서만 외부에 공개하고, proxy에서 로그인 경로의 분산 rate limit을 추가하세요. 운영 관리자 계정에는 가능한 경우 MFA 또는 별도 접근제어 게이트를 적용합니다.
`FORWARD_HEADERS_STRATEGY=NATIVE`는 Tomcat이 신뢰된 내부 proxy에서 받은 전달 헤더만 반영하게 합니다. proxy가 다른 호스트에 있다면 `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`를 실제 proxy CIDR/정규식으로 더 좁게 설정하고, 비어 있는 값으로 모든 proxy를 신뢰하지 마세요.
이 애플리케이션은 origin 루트(`/`) 배포만 지원합니다. `SERVER_SERVLET_CONTEXT_PATH`는 설정하지 말고, reverse proxy도 외부 루트 경로를 애플리케이션 루트로 전달합니다. 하위 경로 배포가 필요하면 로그인·오류·정적 자산 경로 전체의 context-path 지원을 별도 검증한 뒤 적용합니다.
`docker compose config`의 일반 출력은 해석된 환경변수와 비밀정보를 터미널·CI 로그에 노출할 수 있으므로 사용하거나 공유하지 말고, 검증에는 출력하지 않는 `--quiet`만 사용합니다.
현재 로그인 제한과 관리자 세션 강제 만료 저장소는 JVM 로컬입니다. 따라서 이 구성은 단일 애플리케이션 인스턴스로만 운영합니다. 수평 확장이 필요하면 공유 세션 저장소와 전역 로그인 제한·권한 철회 검증을 먼저 구현해야 합니다.

## 3. JAR 배포

```bash
./gradlew clean test bootJar
SPRING_PROFILES_ACTIVE=prod java -jar build/libs/aspera-web-0.0.1-SNAPSHOT.jar
```

Windows에서는 `powershell -ExecutionPolicy Bypass -File scripts/verify.ps1`를 사용합니다. 이 스크립트는 한글 등 비 ASCII 작업 경로에서 Gradle 테스트 worker가 classpath를 잘못 해석하는 경우에만 임시 ASCII 드라이브 별칭을 만들고, 검증 후 즉시 해제합니다. 운영 값은 시스템 환경변수나 승인된 비밀관리 시스템으로 전달합니다.

## 4. 자체 서명 Node 인증서

TLS 검증을 비활성화하지 않습니다. 인증서를 검증한 후 Java truststore로 가져오고 실행 환경에서만 truststore 정보를 전달합니다.

```bash
keytool -importcert -alias aspera-node -file aspera-node.crt \
  -keystore aspera-node-truststore.p12 -storetype PKCS12
```

그다음 `JAVA_TOOL_OPTIONS`에 `javax.net.ssl.trustStore`와 `javax.net.ssl.trustStorePassword`를 설정합니다. truststore와 암호는 저장소에 올리지 않습니다.

## 5. 배포 확인

- 로그인 전 보호 URL이 로그인 화면으로 이동하는지 확인합니다.
- 관리자와 일반 사용자의 시작 화면 및 접근 권한을 각각 확인합니다.
- 존재하지 않는 URL이 정보 노출 없는 404 화면을 반환하는지 확인합니다.
- Node 연결 실패 시 빈 화면 대신 이해 가능한 오류가 표시되는지 확인합니다.
- 모바일과 데스크탑에서 파일 목록, 관리 표, 폼이 가로로 잘리지 않는지 확인합니다.
