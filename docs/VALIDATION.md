# 검증 결과

검증 기준: 2026-10-13, Java 17 / Spring Boot 3.5.16.

Maven/API/H2 실행 결과는 기존 Java 예제 작성 시의 결과이다. 이번 EC2 3노드·Ingress 개정에서는 아래 정적 검증을 수행했으며, 실제 AWS 장애 주입이나 복구 성공을 검증한 것은 아니다.

| 항목 | 결과 |
|---|---|
| Maven `verify` | BUILD SUCCESS — 실행 가능한 Spring Boot JAR 생성 |
| Java API 통합 테스트 | 5건 통과, 실패 0건, 오류 0건 |
| H2 체험 서버 시작 | HTTP 8080 기동 및 readiness 응답 확인 |
| JavaScript 문법 | node --check 통과 |
| Shell 문법 | bootstrap/deploy/Runner 설정 스크립트 확인 |
| Kubernetes/Compose/Helm YAML 파싱 | 통과 |
| 업무용 Deployment/StatefulSet 복제 수 | 정상 상태 합계 8 확인 |
| 브라우저 E2E | 데스크톱/모바일 6개 테스트 제공. 작성 환경에서 Chromium 시작 시 소켓 사용이 제한되어 실행 완료하지 못함 |
| PostgreSQL + Nginx + API 3개 통합 | Docker 미제공 환경으로 로컬에서 실행하지 않음. GitHub Actions에 Compose 기반 브라우저 테스트 구성 |
| EC2/K3s/GHCR 배포 및 Grafana | 실제 사용자 계정 환경에서는 실행하지 않음 |
| 이번 개정 YAML | 17개 파일 파싱 통과 |
| Ingress | HTTP/TLS 각각 4개 경로의 Service 이름·포트 일치 확인 |
| 노드 배치 | API/웹 worker 선택, DB worker2 고정, Traefik 2 replicas 확인 |
| 대시보드·알림 | 16개 패널 JSON 및 ConfigMap 내용 일치, 알림 규칙 5개 확인 |
| 이번 개정 스크립트 | 모든 Shell 스크립트 및 문서 내 bash 블록 문법 확인, 웹 JavaScript 문법 확인 |
| 장애 실습 20개 | 재현·진단·복구 명령 제공. 실제 클러스터에서 실행하지 않음 |

API 통합 테스트는 H2 PostgreSQL 호환 모드이다. 실제 PostgreSQL의 잠금·동시성·드라이버와 완전히 동일한 검증이 아니다. PostgreSQL을 사용하는 Compose와 Kubernetes 실습을 순서대로 실행하여 확인해야 한다.

자동 테스트 내용:

1. 무료 민원은 접수 완료 상태로 생성되며 결제 요청은 거절된다.
2. 유료 민원 결제 금액은 서버에서 1,000원으로 결정되고 반복 결제 요청은 같은 결제 결과를 반환한다.
3. 다른 체험 계정의 민원 조회/결제는 차단되고 목록에서도 제외된다.
4. 인증 토큰 없는 민원 요청은 401로 거절된다.
5. 허용되지 않은 민원 분야는 400으로 거절된다.

브라우저 테스트는 무료 신청·검색·상세·새로고침 유지, 유료 신청·모의 결제·영수증, 가로 넘침 검사를 각각 데스크톱/모바일에서 수행하도록 작성하였다. 브라우저 테스트가 이 작성 환경에서 통과한 것으로 해석하면 안 된다.
