# Prometheus·Grafana: 주요 확인 항목과 판단 방법

기본 배치는 Prometheus/Grafana/Alertmanager/Operator/kube-state-metrics를 k3s-cp로 지정한다. node-exporter는 모든 노드에서 실행된다. 메트릭은 모니터링용 Pod가 수집하므로 업무 Pod 8개와 별도이다. CP가 중단되면 모니터링도 영향을 받는 **교육용 구성**이며, 운영에서는 외부 관측/별도 모니터링 인프라를 검토한다.

## 1. 설치 — k3s-cp에서

업무 배포와 Traefik 설정을 먼저 완료한다. 저장소 루트에서 실행한다.

```bash
curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 -o /tmp/get-helm.sh
bash /tmp/get-helm.sh
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm search repo prometheus-community/kube-prometheus-stack --versions | head
read -rp 'Chart version: ' LAB_CHART_VERSION
helm show chart prometheus-community/kube-prometheus-stack --version "$LAB_CHART_VERSION"
helm upgrade --install monitor prometheus-community/kube-prometheus-stack \
 --version "$LAB_CHART_VERSION" -n monitoring --create-namespace \
 -f monitoring/values.yaml --wait --timeout 10m
kubectl apply -f monitoring/dashboard-configmap.yaml
kubectl apply -f monitoring/alerts.yaml
kubectl -n monitoring get pods,pvc
kubectl -n monitoring get prometheusrule citizen-lab-rules
kubectl top nodes
```

chart의 kubeVersion과 설치된 K3s 버전이 맞는지 확인한다. 동일 강의에서는 chart 버전을 고정한다. Helm values 변경은 위 upgrade 명령으로 반영한다. values.yaml은 kubectl apply 대상이 아니다.

보관은 Prometheus 2일/10Gi 요청, Grafana 2Gi 요청이다. local-path 실제 저장은 CP 디스크에 이루어진다. DB 내부 exporter, 로그 저장용 Loki, 외부 Slack/이메일 전송은 기본 설치에 포함하지 않는다. kubeEtcd/scheduler/controller/proxy 등 K3s 내부 제어부 수집은 이번 간단 구성에서 비활성화했다.

## 2. 화면 접속

CP에서 암호를 확인한다.

```bash
kubectl -n monitoring get secret monitor-grafana -o jsonpath='{.data.admin-password}' | base64 -d
printf '\n'
```

**각각 별도의 CP SSH 터미널**에서 포트포워딩을 유지한다.

```bash
# 터미널 A
kubectl -n monitoring port-forward svc/monitor-grafana 3000:80 --address 127.0.0.1
```

```bash
# 터미널 B
kubectl -n monitoring port-forward svc/monitor-kube-prometheus-prometheus 9090:9090 --address 127.0.0.1
```

```bash
# 터미널 C — Alertmanager를 볼 경우
kubectl -n monitoring port-forward svc/monitor-kube-prometheus-alertmanager 9093:9093 --address 127.0.0.1
```

PC에서 한 번의 SSH 터널로 연결한다.

```bash
ssh -i YOUR_KEY.pem -L 3000:127.0.0.1:3000 \
 -L 9090:127.0.0.1:9090 -L 9093:127.0.0.1:9093 ubuntu@CP_PUBLIC_IP
```

- Grafana: http://localhost:3000 — admin / 조회한 비밀번호.
- Prometheus: http://localhost:9090 — Targets / Graph / Alerts.
- Alertmanager: http://localhost:9093 — 현재 알림 목록.

보안 그룹에 3000/9090/9093을 공개하지 않는다. CP에 ssh와 port-forward가 모두 살아 있어야 한다. Grafana 첫 로그인 후 비밀번호를 변경한다.

## 3. 전용 대시보드와 수집 상태

Grafana Dashboards에서 **Citizen Lab / 3 Nodes**를 찾는다. dashboard ConfigMap을 sidecar가 읽어 자동 등록한다. Prometheus 데이터소스 선택과 최근 30분/새로고침 30초를 확인한다.

안 보이면:

```bash
kubectl -n monitoring get configmap citizen-dashboard --show-labels
kubectl -n monitoring get pods -l app.kubernetes.io/name=grafana
kubectl -n monitoring logs GRAFANA_POD_NAME -c grafana-sc-dashboard --tail=100
```

대안으로 Grafana Import에서 `monitoring/citizen-dashboard.json`을 업로드하고 Prometheus 데이터소스를 선택한다. JSON을 수정했다면 ConfigMap의 JSON도 함께 갱신해야 하며, import와 자동 등록을 동시에 반복하여 중복 대시보드를 만들 필요는 없다.

Prometheus Targets에서:

| 대상 | 안정 상태 기대값 | 의미 |
|---|---:|---|
| shop-apis | 6개 UP | API 6개 Pod의 Actuator 수집 |
| traefik-pods | 2개 UP | Ingress Controller 메트릭 수집 |
| node-exporter 계열 | 노드별 3개 UP | EC2 호스트 CPU/메모리/디스크 |
| kube-state-metrics | UP | Deployment/Pod/Node 등 Kubernetes 상태 |

업데이트 중에는 API 수집 대상이 임시로 늘어날 수 있다. `up=1`은 메트릭 HTTP 수집 성공이며 실제 DB 업무의 정상 완료를 보장하지 않는다. 목록에서 대상이 아예 사라지면 up=0 시계열 자체가 없어질 수도 있으므로 replica 상태/실제 요청을 함께 본다.

## 4. 트래픽 발생

CP 또는 Worker IP에 접근 가능한 shell에서 실행한다.

```bash
export INGRESS_IP='WORKER1_PRIVATE_IP'
export LAB_REQUESTS=120
bash scripts/traffic.sh
```

가상 회원 한 명을 만들고 1초 간격으로 내 회원/내 민원 조회를 수행한다. 최대 600회로 제한한 소규모 관찰용이며 부하 한계 측정 도구가 아니다. 출력되는 HTTP 코드와 응답시간을 그래프와 비교한다. p95/비율은 표본이 적으면 흔들리므로 적어도 여러 번의 scrape 이후 확인한다.

## 5. 주요 확인 항목 16가지

아래 기준값은 학습용 예시이며 업무 SLA에 맞게 바꿔야 한다. 한 순간의 값보다 지속 시간/평소 대비 변화/관련 지표를 함께 판단한다.

| 항목 | 어디서/무엇을 확인 | 이상 시 우선 조치 |
|---|---|---|
| 1. 노드 Ready | Ready 노드 수 = 3 | get nodes/describe node, k3s-agent 로그, EC2 상태 |
| 2. 업무 Pod Ready | 정상 안정 상태 8개 | 0/1 Pod의 Events, readiness, DB 상태 |
| 3. PostgreSQL Ready | StatefulSet Ready = 1 | worker2/PVC/DB 로그 |
| 4. Ingress 수집 | traefik-pods UP = 2 | Traefik Pod/metrics 포트/라벨 |
| 5. 노드 CPU | 80% 이상이 5분 이상 지속하는지 | Pod별 CPU와 스케줄링 여유 비교 |
| 6. 노드 메모리 | Available 감소, 사용률 85% 이상 추세 | API JVM/모니터링/DB별 사용량 분리 |
| 7. 노드 디스크 | / 사용률, inode 부족, 80% 이상 추세 | df -h/-i, PV·로그·이미지 사용량 |
| 8. Pod CPU | 코어 단위 사용률 | limit/request, throttling 및 요청량 비교 |
| 9. Pod 메모리 | working set과 limit의 간격 | OOMKilled 여부, heap/native 차이 |
| 10. 재시작 | 10분간 증가량 | 이전 컨테이너 로그/종료 원인 |
| 11. API 요청량 | application별 RPS | 갑작스런 급증/0, 실제 요청과 대조 |
| 12. API p95 | 95%의 요청이 완료되는 시간, 예: 1초 초과 지속 | DB 풀 대기·CPU·GC·Ingress와 비교 |
| 13. API 5xx 비율 | 충분한 요청에서 5% 초과 지속 | Java 예외/DB 연결 실패 |
| 14. Ingress 5xx | 백엔드에 도착하기 전 발생한 실패 포함 | EndpointSlice/Service 포트/노드 연결 |
| 15. JVM heap | 사용량이 GC 후 감소하는지 | 지속 상승·GC 시간·native 메모리 확인 |
| 16. Hikari 대기 | pending이 지속적으로 0보다 큰지 | 느린 쿼리/DB 중단/풀 고갈 확인 |

## 6. 직접 입력할 PromQL

Grafana Explore 또는 Prometheus Graph에 붙여넣는다. 데이터소스별로 label이 다르면 실제 시계열의 label을 먼저 확인한다.

### 노드 상태 / CPU / 메모리

```promql
kube_node_status_condition{condition="Ready",status="true"}
```

```promql
100 * (1 - avg by(instance) (rate(node_cpu_seconds_total{mode="idle"}[5m])))
```

```promql
100 * (1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)
```

Ready는 노드별 1이 정상이다. CPU 쿼리는 코어 평균 비율이며 Pod CPU 코어 수 쿼리와 단위가 다르다.

### Pod 메모리 / 재시작 / Deployment 가용성

```promql
sum by(pod) (container_memory_working_set_bytes{namespace="shop",container!="",container!="POD"})
```

```promql
sum by(pod) (increase(kube_pod_container_status_restarts_total{namespace="shop"}[10m]))
```

```promql
kube_deployment_status_replicas_available{namespace="shop"}
```

API별 2, gateway 1이 정상이다. PostgreSQL은 Deployment가 아니라 StatefulSet 지표로 조회한다.

### API p95 / RPS

```promql
histogram_quantile(0.95,
 sum by(le,application) (
  rate(http_server_requests_seconds_bucket{job="shop-apis",uri!~"/actuator.*"}[5m])
 ))
```

```promql
sum by(application) (
 rate(http_server_requests_seconds_count{job="shop-apis",uri!~"/actuator.*"}[5m])
)
```

application.yml에 HTTP histogram 활성화를 추가했다. **이번 버전 Java 이미지를 다시 빌드/배포**해야 bucket이 제공된다. probe/scrape 요청을 제외하여 업무 요청을 관찰한다. 관측 요청이 없으면 p95가 NaN/No data일 수 있다.

### Ingress 실패

```promql
sum by(code) (rate(traefik_entrypoint_requests_total{job="traefik-pods",code=~"5.."}[5m]))
```

Ingress에서 거절한 요청은 Java의 HTTP 메트릭에 남지 않을 수 있다. API 5xx와 Ingress 5xx를 같은 지표로 취급하지 않는다. 5xx가 한 번도 없으면 해당 코드 시계열이 없을 수 있다.

### Hikari 커넥션 풀

```promql
hikaricp_connections_active{job="shop-apis"}
```

```promql
hikaricp_connections_pending{job="shop-apis"}
```

각 API Pod 풀 최대값은 5이다. 6개 Pod의 풀을 합쳐 DB 전체 연결을 추정할 수 있지만 관리 연결/스크립트 연결까지 포함한 정확한 DB 총 연결 수와는 다르다. 무조건 풀을 늘리면 DB 부하가 커질 수 있다.

## 7. DB 내부 상태는 SQL로 확인

기본 대시보드는 PostgreSQL Pod 자원과 StatefulSet 상태를 본다. DB 내부 exporter는 설치하지 않았다. 아래 SQL은 읽기 전용이다.

```bash
kubectl -n shop exec postgres-0 -- psql -U shop -d shop -c \
 "SELECT state, count(*) FROM pg_stat_activity WHERE datname=current_database() GROUP BY state;"
kubectl -n shop exec postgres-0 -- psql -U shop -d shop -c \
 "SELECT pg_size_pretty(pg_database_size(current_database()));"
kubectl -n shop exec postgres-0 -- psql -U shop -d shop -c \
 "SELECT pid, now()-query_start AS duration, wait_event_type, wait_event FROM pg_stat_activity WHERE state='active' AND pid<>pg_backend_pid() ORDER BY query_start;"
```

느린 쿼리 분석을 확장할 때 postgres-exporter/pg_stat_statements를 추가한다. 민감한 실제 SQL 텍스트를 무분별하게 수집하지 않는다.

## 8. 로그 확인

```bash
kubectl -n shop logs deployment/member-api --since=10m --tail=100
kubectl -n shop logs POD_NAME --previous --tail=100
kubectl -n kube-system logs -l app.kubernetes.io/name=traefik \
 --all-containers=true --max-log-requests=5 --since=10m --tail=100
```

Deployment 로그 명령은 임의의 한 Pod를 선택할 수 있다. 전체 API 복제본을 보려면 label selector를 사용한다.

```bash
kubectl -n shop logs -l app=order-api --all-containers=true --prefix=true --max-log-requests=5 --since=5m
```

Traefik access log는 JSON이며 Authorization 같은 헤더는 기록하지 않도록 설정하였다. 요청 경로/상태 코드/백엔드 처리 시간을 확인한다. Prometheus는 로그 저장소가 아니므로 Grafana 대시보드에 이 로그가 자동으로 표시되는 것은 아니다.

## 9. 알림 확인 실습

`monitoring/alerts.yaml`에는 다음 규칙을 넣었다.

| 이름 | 조건/대기 |
|---|---|
| LabNodeNotReady | 노드 Ready 아님 2분 |
| LabApiReplicaShortage | API Available replicas < 2가 2분 |
| LabDatabaseNotReady | PostgreSQL Ready < 1이 1분 |
| LabApiScrapeDown | API 수집 실패 1분 |
| LabIngressScrapeDown | Traefik 수집 실패 1분 |

Prometheus Alerts에서 Inactive → Pending → Firing을 관찰한다. `for` 시간과 scrape/evaluation 간격 때문에 즉시 Firing이 되지 않는다. Alertmanager 목록에 전달되는지도 확인한다. 외부 이메일/Slack 수신자를 설정하지 않았으므로 실제 메시지는 전송되지 않는다.

DB 중단 실습에서는 DB readiness 알림과 API replica 부족 알림이 연쇄로 나타날 수 있다. 여러 알림 중 공통 원인이 DB라는 점을 찾는 것이 목표이다. CP 자체가 완전히 중단되면 로컬 Prometheus도 중단되어 알림을 만들 수 없으므로 독립된 외부 관측이 필요하다.

## 10. 정상 운영 점검 루틴

1. 노드 Ready 3 / 업무 Pod Ready 8 / API 복제본 각 2 확인.
2. Targets의 API 6개·Ingress 2개 UP 확인.
3. 최근 30분 CPU/메모리/디스크 추세와 재시작 증가 확인.
4. p95/5xx/RPS를 함께 확인하여 트래픽 증가와 장애를 구분.
5. PostgreSQL Ready와 Hikari pending, 필요하면 SQL 확인.
6. 실제 사이트에서 무료 민원 등록·조회 1건 수행.
7. Pending/Firing 알림의 원인과 복구 여부 기록.

공식 근거:
- https://github.com/prometheus-community/helm-charts/tree/main/charts/kube-prometheus-stack
- https://doc.traefik.io/traefik/observe/logs-and-access-logs/
- https://doc.traefik.io/traefik/reference/install-configuration/observability/metrics/
- https://prometheus.io/docs/prometheus/latest/querying/functions/
- https://docs.spring.io/spring-boot/3.5/reference/actuator/metrics.html
