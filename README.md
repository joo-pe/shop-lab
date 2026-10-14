# 온민원 — EC2 3노드 / Ingress / Java 민원사이트 실습

Java 17 · Spring Boot 3.5.16 · PostgreSQL 17 · K3s 3노드 · Traefik Ingress · GitHub Actions · Prometheus/Grafana

이번 버전은 Control Plane 1대 + Worker 2대, Ingress 경로 분기, 20가지 장애 진단·복구 시나리오, Grafana 16개 패널을 포함한다. 새 EC2 클러스터에 구축하는 실습이며 이전 단일 노드 PVC를 자동 이전하지 않는다.

**회원 생성 → 민원 신청 → 접수 조회 → 수수료 모의 결제 → 영수증 조회**를 브라우저에서 체험하는 GitHub 업로드용 프로젝트이다. 실제 기관과 연결되지 않는 가상 서비스이며 실제 개인정보/결제정보를 입력하지 않는다.

## 1. 구현 기능

| 구분 | 기능 | 코드 |
|---|---|---|
| 회원 API | 가상 이름으로 체험 계정 생성, 토큰 기반 본인 내역 조회 | MemberController.java |
| 주문 API | 민원 신청·목록·상세, 입력값 검증, 무료/유료 구분 | OrderController.java |
| 결제 API | 1,000원 모의 결제, 중복 결제 방지, 영수증 조회 | PaymentController.java |
| 대국민 화면 | 민원 분야 선택, 신청 폼, 제목 검색, 상태 필터, 상세 팝업 | web/ |
| 운영 | DB 저장, 상태 점검, JVM/HTTP 메트릭, 자동 배포 | k8s/, monitoring/ |

기존 주문 API를 민원 신청 건 관리 역할로 사용한다. 각 API는 같은 Java JAR에서 `SERVICE_NAME=member/payment/order`에 따라 담당 Controller만 활성화한다. Kubernetes에서는 **서로 다른 Deployment/Service**로 실행된다. 독립 서비스 분리 학습 전에 공통 코드 관리 부담을 줄인 구성이다.

서비스 간 데이터 연계는 실습을 단순하게 하기 위해 공용 DB 테이블을 사용한다. API 간 HTTP 호출·분산 트랜잭션을 구현한 완전한 마이크로서비스 예제는 아니다.

| 업무용 Pod | 수 |
|---|---:|
| member-api | 2 |
| order-api | 2 |
| payment-api | 2 |
| postgres | 1 |
| gateway — 정적 웹 화면 (API 라우팅은 Ingress) | 1 |
| 합계 | **8** |

시스템/모니터링 Pod와 롤링 업데이트 중 임시 Pod는 별도이다. Compose는 빠른 기능 확인용으로 API를 하나씩 실행하므로 총 5개 컨테이너이다.

## 2. 가장 빠른 실행: Docker Compose

필수: Docker Engine/Desktop + Docker Compose v2. PC 또는 WSL2 터미널에서 실행한다. Java와 Maven은 Docker 이미지 안에서 사용하므로 호스트에 설치하지 않아도 된다.

```bash
cd shop-lab
cp .env.example .env
# .env의 DB_PASSWORD를 원하는 실습용 비밀번호로 변경한다.
docker compose up -d --build
docker compose logs -f member-api order-api payment-api
```

세 API에서 `Started CitizenApplication`이 보이면 Ctrl+C로 로그 관찰을 끝낸다. 서버는 계속 실행된다. 최초 빌드는 의존성/이미지 다운로드로 수 분이 걸릴 수 있다.

브라우저에서 **http://localhost:8080** 에 접속한다. 처음 잠깐 502가 나타나면 API 시작 완료 후 새로고침한다.

EC2에서 Compose를 실행했다면 8080은 localhost에만 바인딩되어 있다. PC에서 다음 SSH 터널로 접속한다.

```bash
ssh -i YOUR_KEY.pem -L 8080:127.0.0.1:8080 ubuntu@EC2_PUBLIC_IP
```

```bash
# 중지: DB 데이터 유지
docker compose down
# 다시 시작
docker compose up -d
# DB 초기화: 저장된 모든 실습 데이터 삭제
docker compose down -v
```

기존 볼륨이 생성된 뒤 .env 비밀번호만 바꾸면 기존 DB 비밀번호가 변경되지 않는다. 새 초기화 또는 PostgreSQL 계정 변경이 별도로 필요하다.

## 3. 화면에서 따라 하는 테스트

1. **계정 만들기:** 체험용 이름 `홍길동`을 입력하고 계정 만들기를 누른다.
2. **무료 민원:** 생활 불편을 선택하고 제목 `공원 가로등 점검 요청`, 내용 `학습용 테스트 민원입니다.`를 입력한다.
3. 체험 확인 체크 후 접수한다. 나의 민원에 **접수 완료**가 표시된다.
4. 제목을 클릭하여 상세 내용을 확인한다. 제목 검색과 상태 필터를 사용한다.
5. **유료 민원:** 증명서 발급 체험을 선택하고 새 신청을 등록한다. **결제 대기**가 표시된다.
6. `1,000원 모의 결제`를 누르고 확인한다. **접수 완료**로 바뀐다.
7. 해당 제목을 누르면 **모의 결제 영수증**이 표시된다. 실제 증명서는 생성되지 않는다.
8. 브라우저 새로고침 후 같은 계정의 민원이 유지되는지 확인한다.
9. 별도 시크릿 창에서 새 체험 계정을 만든다. 이전 계정의 민원이 보이지 않는지 확인한다.
10. `docker compose restart` 후 동일 브라우저로 다시 조회한다. DB 볼륨이 유지되면 데이터도 유지된다.

체험 토큰은 localStorage에 저장하고 DB에는 SHA-256 해시만 보관한다. 이 샘플에는 본인 인증, 비밀번호 로그인, 토큰 만료/회수, 계정 복구가 없다. 다른 계정으로 전환하면 기존 토큰을 브라우저에서 잃으므로 이전 계정으로 돌아오는 UI는 제공하지 않는다. 운영용 인증 구현으로 사용하지 않는다.

## 4. GitHub에 올리는 방법

GitHub에서 `citizen-portal-lab`이라는 **빈 Private 저장소**를 만든다. README 자동 생성은 선택하지 않는다. 압축을 푼 `shop-lab` 내부에서 다음을 실행한다.

```bash
git init
git branch -M main
git add .
git commit -m "Add Java citizen portal Kubernetes lab"
git remote add origin https://github.com/YOUR_OWNER/citizen-portal-lab.git
git push -u origin main
```

인증은 GitHub CLI, SSH 키 또는 GitHub 토큰을 사용한다. `.github` 폴더, `package-lock.json`까지 커밋한다. `.env`, kubeconfig, 토큰, DB 백업 파일은 커밋하지 않는다. 이 응답은 업로드할 파일을 제공한 것이며 사용자 계정에 저장소를 생성하거나 push한 것은 아니다.

Actions는 다음 순서로 실행된다.

1. Java API 테스트 (`mvn verify`, H2).
2. PostgreSQL + 세 Java API + Nginx를 Docker Compose로 실행.
3. Chromium 데스크톱/모바일 브라우저 테스트.
4. main이면 Java API 이미지와 화면 이미지를 GHCR에 업로드.
5. `ENABLE_DEPLOY=true`일 때만 EC2 Runner가 Kubernetes에 자동 배포.

PR에서는 테스트만 실행하며 배포 Runner를 사용하지 않는다. 최초 실행은 자동 배포가 비활성화되어 있으므로 Runner가 없어도 테스트/이미지 빌드까지 수행된다.

## 5. Kubernetes 실습

상세 설치 순서는 **[docs/KUBERNETES.md](docs/KUBERNETES.md)** 에 있다.

- Ubuntu 24.04 x86_64 EC2 3대: k3s-cp / k3s-worker1 / k3s-worker2.
- 각 4 vCPU·8GiB 권장. CP는 모니터링, Worker는 업무 API/Ingress를 실행한다.
- 업무 Pod 8개와 Traefik/시스템/모니터링 Pod는 별도이다.
- PC hosts 설정 후 `http://minwon.lab`로 접속한다. **기존 30080 대신 80/443**을 사용한다.
- API 6개는 Worker에 분산하고, PostgreSQL은 worker2의 local-path PVC를 사용한다.
- CP 1개·DB 1개이므로 완전한 HA가 아니다. DB 노드 유실에 대비한 백업이 필요하다.
- 화면 이미지는 `web/Dockerfile.ingress`로 빌드한다. Compose는 기존 프록시 모드를 유지한다.

실습 문서 순서:

1. [docs/KUBERNETES.md](docs/KUBERNETES.md): EC2/VPC/SG → CP/Worker 가입 → Ingress → 배포/HTTPS/NLB 선택 실습.
2. [docs/MONITORING.md](docs/MONITORING.md): 모니터링 설치, 16개 확인 항목, PromQL, 로그, 알림.
3. [docs/FAILURE-LABS.md](docs/FAILURE-LABS.md): 20개 장애 재현·진단·복구 예시.
4. [docs/VALIDATION.md](docs/VALIDATION.md): 실제 검증 범위와 미실행 항목.

## 6. 프로젝트 구조

| 경로 | 내용 |
|---|---|
| pom.xml | Java 17 / Spring Boot 의존성, Maven 빌드 |
| src/main/java/com/carroti/citizen | 회원·민원·결제 Controller, DB 초기화, 체험 인증 |
| src/main/resources/application.yml | DB, Actuator 및 실행 역할 설정 |
| src/test | API 통합 테스트와 메모리 DB 체험 실행기 |
| web | 화면 HTML/CSS/JS, Compose 프록시와 Ingress용 정적 Nginx Dockerfile |
| infra | Traefik 2개 복제본/Worker 분산 설정 |
| faults | 임시 OOM Pod 및 DB 통신 차단 실습 |
| Dockerfile | Java 멀티스테이지 빌드 |
| compose.yaml | 로컬 PostgreSQL + API 3개 + Nginx |
| k8s | Deployment, StatefulSet, Service, PVC, Runner RBAC |
| monitoring | Helm 설정, 16개 패널 대시보드, PrometheusRule 알림 |
| scripts | 최초 배포, 자동 배포, Runner 인증 설정 |
| tests | 실제 브라우저 E2E 테스트 |
| .github/workflows/deploy.yml | 테스트 → 빌드 → 배포 |

## 7. 개발 및 자동 테스트

Java 17 이상과 Maven 3.6.3 이상이 있으면 다음을 실행한다.

```bash
mvn verify
```

API 테스트: 무료 민원 접수, 무료 항목 결제 차단, 모의 결제 중복 방지, 서버 기준 금액 계산, 타인 민원 접근 차단, 인증 누락 및 입력 오류 처리.

Compose로 서버를 실행한 상태에서 Node.js 22 이상으로 브라우저 테스트를 실행한다.

```bash
npm ci
npx playwright install --with-deps chromium
npm run test:e2e
```

Kubernetes를 대상으로 실행할 수도 있다.

```bash
BASE_URL=http://minwon.lab npm run test:e2e
```

테스트는 가상 계정과 민원을 생성하므로 실습용 환경에서 실행한다.

Docker 없이 화면과 Java API만 빠르게 확인하려면 **휘발성 H2 체험 모드**를 사용할 수 있다.

```bash
mvn test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
 -Dexec.mainClass=com.carroti.citizen.LocalDemoApplication \
 -Dexec.classpathScope=test
```

프로젝트 루트에서 실행한 뒤 http://localhost:8080 에 접속한다. 이 모드는 한 Java 프로세스가 세 API와 화면을 제공하며 종료 시 DB가 사라진다. PostgreSQL·3개 Deployment·8개 Pod 검증은 Compose/Kubernetes로 수행한다.

## 8. 주요 API

모든 민원/결제 요청에는 `Authorization: Bearer {체험 토큰}`이 필요하다.

| 메서드 | 경로 | 내용 |
|---|---|---|
| POST | /api/members/demo | `{ "name": "홍길동" }` 체험 계정 생성 |
| GET | /api/members/me | 내 체험 계정 |
| POST | /api/orders | `{ "category":"LIVING", "title":"제목", "content":"내용" }` |
| GET | /api/orders | 내 민원 최근 100건 |
| GET | /api/orders/{id} | 내 민원 상세 |
| POST | /api/payments/simulate | `{ "orderId":"민원 UUID" }` 모의 결제 |
| GET | /api/payments/{orderId} | 내 모의 결제 영수증 |

무료 분야는 LIVING/TRAFFIC/ENVIRONMENT, 모의 유료 분야는 CERTIFICATE이다. 수수료는 서버에서 정한다. 결제 트랜잭션은 해당 민원 행을 잠그며 order_id UNIQUE 제약으로 중복 결제를 방지한다.

Actuator는 각 API의 `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus`에 제공한다. Ingress는 이 경로를 외부에 라우팅하지 않는다. readiness에는 DB 상태를 포함하고 liveness에는 포함하지 않는다.

## 9. 제한 사항

- 실제 민원 접수, 증명서 발급, PG 승인, 관리자 처리/답변, 첨부파일은 구현하지 않았다.
- 샘플 상태는 결제 대기/접수 완료이다. 실제 담당자 배정·처리 중·처리 완료 절차는 확장 과제이다.
- 3노드여도 단일 DB/단일 Control Plane은 HA가 아니다. 실운영에는 인증/권한, HTTPS, 감사기록, 개인정보 정책, 백업, 네트워크 격리, DB migration 도구를 추가해야 한다.
- 화면은 키보드 초점·label·반응형을 반영한 샘플이며 웹접근성 인증을 받은 결과물이 아니다.
- 의존성과 컨테이너의 보안 검토 후 운영 사용 여부를 결정한다. 재현성을 강화하려면 컨테이너 digest와 GitHub Action commit SHA를 고정한다.

실제 검증 결과는 [docs/VALIDATION.md](docs/VALIDATION.md)를 확인한다.

공식 참고: [Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [Actuator](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html), [K3s](https://docs.k3s.io/quick-start), [GitHub Runner](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/add-runners).
