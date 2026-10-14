# 장애 재현·진단·복구 실습

새로 구축한 실습 클러스터만 대상으로 한다. 기본 명령은 **k3s-cp 관리자 계정, 저장소 루트**에서 실행한다. Worker SSH/PC에서 실행할 항목은 따로 표시하였다. 명령어 안의 `POD_NAME`, IP는 실제 값으로 바꾼다. 예시를 파일 전체로 붙여넣어 일괄 실행하지 않는다.

## 공통 준비

GitHub ENABLE_DEPLOY=false로 자동 배포를 잠시 중단한다. 실습 시작 전 다음을 확인한다.

```bash
kubectl config use-context citizen-lab
kubectl get nodes
kubectl -n shop get pods -o wide
kubectl -n shop get pdb
kubectl create namespace lab-faults --dry-run=client -o yaml | kubectl apply -f -
mkdir -p .lab-backup
kubectl -n shop exec postgres-0 -- pg_dump -U shop -d shop -Fc > .lab-backup/before-lab.dump
export INGRESS_IP='WORKER1_PRIVATE_IP'
bash scripts/smoke-ingress.sh
```

.lab-backup은 Git에서 제외된다. DB 백업은 생성 성공/용량을 확인하고 별도 PC 등 노드 밖에도 복사한다. kubectl get pods의 Running은 Ready와 다르다. `1/1`과 `0/1`, RESTARTS, Events를 함께 본다.

기본 진단 순서: **사용자 요청 → Ingress → Service/EndpointSlice → Pod/로그 → DB·노드**이다.

```bash
kubectl -n shop get events --sort-by=.metadata.creationTimestamp
kubectl -n shop describe pod POD_NAME
kubectl -n shop logs POD_NAME --tail=100
kubectl -n shop logs POD_NAME --previous --tail=100
kubectl -n shop get endpointslice -l kubernetes.io/service-name=member-api -o yaml
kubectl top nodes
kubectl top pods -n shop
```

장애가 새 Pod에만 발생하면 RollingUpdate의 maxUnavailable=0 때문에 기존 정상 Pod는 유지된다. 이때 사용자 장애 없이 배포만 멈출 수 있다. 기존 정상 Pod를 억지로 삭제하여 전면 중단을 만들 필요는 없다.

## 1. API Pod 삭제 — Deployment 복구 관찰

**예상:** 삭제된 Pod를 대신할 새 Pod가 생성된다. 새 이름/UID로 생성되므로 같은 Pod를 되살리는 것은 아니다.

```bash
LAB_POD=$(kubectl -n shop get pods -l app=member-api -o jsonpath='{.items[0].metadata.name}')
kubectl -n shop delete pod "$LAB_POD"
kubectl -n shop get pods -l app=member-api -o wide -w
```

관찰 후 Ctrl+C. 복구 확인:

```bash
kubectl -n shop rollout status deployment/member-api --timeout=300s
kubectl -n shop get deployment member-api
```

PDB는 직접 `kubectl delete pod`를 막아주는 기능이 아니다. API별 두 복제본 중 하나만 삭제한다. Grafana에서 Ready Pod 수/재시작 그래프를 구분한다. **Pod 교체는 기존 컨테이너 RESTARTS 증가와 동일하지 않다.**

## 2. 잘못된 이미지 태그 — ImagePullBackOff

```bash
LAB_GOOD_IMAGE=$(kubectl -n shop get deployment member-api -o jsonpath='{.spec.template.spec.containers[0].image}')
kubectl -n shop set image deployment/member-api api=ghcr.io/example/no-such-image:lab-does-not-exist
kubectl -n shop get pods -l app=member-api
kubectl -n shop describe pod NEW_POD_NAME
```

Events에서 not found, denied, 인증 오류를 구분한다. Private GHCR이면 ghcr-pull 존재 여부와 두 패키지 접근 권한을 확인한다. Secret 내용을 터미널에 전부 출력하지 않는다.

```bash
kubectl -n shop get secret ghcr-pull
kubectl -n shop set image deployment/member-api "api=$LAB_GOOD_IMAGE"
kubectl -n shop rollout status deployment/member-api --timeout=300s
```

기존 2개 Pod가 정상이라면 가용성은 유지되고 새 배포만 실패하는 것이 기대 결과이다.

## 3. Java 실행 옵션 오류 — CrashLoopBackOff

```bash
kubectl -n shop set env deployment/member-api JAVA_TOOL_OPTIONS=-XX:ThisOptionDoesNotExist
kubectl -n shop get pods -l app=member-api
kubectl -n shop logs NEW_POD_NAME --previous --tail=80
kubectl -n shop describe pod NEW_POD_NAME
```

Java 옵션 인식 오류로 프로세스가 종료된다. 초기에는 Error, 반복되면 CrashLoopBackOff가 보인다. 복구는 Deployment에서 추가한 환경변수를 제거하여 이미지 기본값을 다시 사용한다.

```bash
kubectl -n shop set env deployment/member-api JAVA_TOOL_OPTIONS-
kubectl -n shop rollout status deployment/member-api --timeout=300s
```

로그가 없는 상태에서 무조건 메모리 증설부터 하지 않는다. 종료 원인/exit code를 먼저 확인한다.

## 4. 잘못된 Readiness 경로 — Running이지만 Ready 아님

```bash
kubectl -n shop patch deployment order-api --type=json \
 -p='[{"op":"replace","path":"/spec/template/spec/containers/0/readinessProbe/httpGet/path","value":"/does-not-exist"}]'
kubectl -n shop get pods -l app=order-api
kubectl -n shop describe pod NEW_POD_NAME
kubectl -n shop get endpointslice -l kubernetes.io/service-name=order-api -o yaml
```

예상: 새 Pod는 Running/0 Ready, readiness에서 HTTP404. 기존 Ready Pod가 남아 있으면 Service는 계속 응답한다.

```bash
kubectl -n shop patch deployment order-api --type=json \
 -p='[{"op":"replace","path":"/spec/template/spec/containers/0/readinessProbe/httpGet/path","value":"/actuator/health/readiness"}]'
kubectl -n shop rollout status deployment/order-api --timeout=300s
```

Readiness 실패는 트래픽 대상 제외이며 그 자체가 컨테이너 재시작 명령은 아니다. Liveness 실패나 프로세스 종료와 구분한다.

## 5. Service selector 오타 — Pod는 정상인데 연결 대상 없음

```bash
kubectl -n shop patch service member-api --type=merge -p '{"spec":{"selector":{"app":"member-api-typo"}}}'
kubectl -n shop get pods -l app=member-api
kubectl -n shop get endpointslice -l kubernetes.io/service-name=member-api -o yaml
curl -i --resolve minwon.lab:80:$INGRESS_IP http://minwon.lab/api/members/me
```

Pod는 Ready인데 EndpointSlice에 사용 가능한 주소가 없다. Ingress 버전/처리 방식에 따라 503 또는 라우트 제외에 따른 404가 보일 수 있다.

```bash
kubectl -n shop patch service member-api --type=merge -p '{"spec":{"selector":{"app":"member-api"}}}'
kubectl -n shop get endpointslice -l kubernetes.io/service-name=member-api
bash scripts/smoke-ingress.sh
```

복구 후 미인증 `/api/members/me`의 401은 정상이다. 모든 4xx를 장애로 간주하지 않는다.

## 6. Ingress Host 불일치 — 404

설정을 훼손하지 않고 잘못된 Host로 요청한다.

```bash
curl -i -H 'Host: wrong.lab' "http://$INGRESS_IP/"
kubectl -n shop get ingress minwon -o yaml
kubectl -n kube-system logs deployment/traefik --tail=50
curl -i --resolve minwon.lab:80:$INGRESS_IP http://minwon.lab/
```

같은 IP에서 올바른 Host 요청은 200이면 네트워크 단절이 아니라 Host 규칙 문제이다. PC hosts의 IP와 브라우저 주소를 확인한다. IP만 입력한 접속과 minwon.lab 접속은 다르다.

## 7. Ingress backend 포트 오타

```bash
kubectl -n shop patch ingress minwon --type=json \
 -p='[{"op":"replace","path":"/spec/rules/0/http/paths/0/backend/service/port/number","value":9999}]'
kubectl -n shop describe ingress minwon
kubectl -n shop get svc member-api -o yaml
kubectl -n kube-system logs -l app.kubernetes.io/name=traefik --all-containers=true --max-log-requests=5 --tail=100
curl -i --resolve minwon.lab:80:$INGRESS_IP http://minwon.lab/api/members/me
```

Ingress가 참조하는 포트는 containerPort가 아니라 **Service port**이다. 이 예제는 둘 다 8080이어서 혼동하기 쉽다. 기대 증상은 라우트 생성 실패/404 또는 5xx이며 정확한 코드는 Traefik 로그와 함께 판단한다.

```bash
kubectl apply -f k8s/ingress.yaml
bash scripts/smoke-ingress.sh
```

선택 HTTPS Ingress는 별도 리소스이므로 이 HTTP 실습 패치의 영향을 받지 않는다.

## 8. PostgreSQL 중단 — API readiness 연쇄 실패

```bash
kubectl -n shop scale statefulset postgres --replicas=0
kubectl -n shop get pods -w
```

Ctrl+C 후 API 로그와 상태를 확인한다.

```bash
kubectl -n shop logs deployment/order-api --tail=100
kubectl -n shop get endpointslice -l kubernetes.io/service-name=order-api -o yaml
```

DB 연결을 readiness에 포함했으므로 시간이 지나면 API가 트래픽 대상에서 제외된다. 화면 정적 파일은 정상이어도 신청/조회는 실패한다. connection pool 대기와 타임아웃이 먼저 보일 수 있다. **readiness 실패를 감추려고 probe를 삭제하지 않는다.**

```bash
kubectl -n shop scale statefulset postgres --replicas=1
kubectl -n shop rollout status statefulset/postgres --timeout=300s
kubectl -n shop wait --for=condition=Ready pod -l tier=api --timeout=300s
```

동일 브라우저에서 기존 민원을 조회하여 PVC 데이터 유지까지 확인한다. 이 실습은 PVC를 삭제하지 않는다.

## 9. NetworkPolicy로 DB 통신 차단

기본 실습에는 별도의 DB 허용 정책이 없다고 가정한다. NetworkPolicy는 허용 규칙이 합산되므로 기존 다른 정책이 허용하면 이 정책만으로 차단되지 않을 수 있다.

```bash
kubectl apply -f faults/block-db.yaml
kubectl -n shop get networkpolicy
kubectl -n shop describe networkpolicy lab-block-db
kubectl -n shop run db-check --image=busybox:1.36 --restart=Never \
 --command -- sh -c 'nc -w 3 postgres 5432 </dev/null; echo exit:$?'
kubectl -n shop logs db-check
```

기존 연결은 conntrack 때문에 즉시 끊기지 않을 수 있다. 새 연결 실패를 관찰한다. 진단 Pod가 ContainerCreating이면 실행 완료 후 logs를 다시 확인한다.

```bash
kubectl -n shop delete networkpolicy lab-block-db
kubectl -n shop delete pod db-check --ignore-not-found
kubectl -n shop wait --for=condition=Ready pod -l tier=api --timeout=300s
```

API가 아니라 네트워크 정책이 원인이므로 DB 비밀번호를 바꾸는 방식으로 해결하지 않는다.

## 10. 메모리 제한 초과 — 격리된 OOM 실습

서비스 전체를 압박하지 않도록 `lab-faults` namespace에 **추가 임시 Pod 하나**를 만든다. Java에서 큰 배열을 할당하고 컨테이너 메모리를 128Mi로 제한한다.

```bash
kubectl apply -f faults/oom.yaml
kubectl -n lab-faults get pod oom-demo -w
```

Ctrl+C 후 확인한다.

```bash
kubectl -n lab-faults describe pod oom-demo
kubectl -n lab-faults get pod oom-demo -o jsonpath='{.status.containerStatuses[0].state.terminated.reason}{"\n"}'
kubectl -n lab-faults logs oom-demo
```

일반적인 기대값은 OOMKilled/137이다. 이미지 시작·컴파일 단계에서 먼저 메모리 오류가 발생할 수도 있으므로 실제 reason/로그를 기준으로 판단한다. Java heap OOM 로그와 커널의 컨테이너 OOMKilled를 구분한다.

```bash
kubectl -n lab-faults delete pod oom-demo
```

실제 API가 OOMKilled라면 limit만 올리기 전에 JVM heap/native/thread 사용량과 메모리 증가 추세를 확인한다. 이 예제의 API 기본은 limit 768Mi / MaxRAMPercentage=65이다.

## 11. CPU request 과다 — Pending / FailedScheduling

```bash
kubectl -n shop set resources deployment/payment-api --containers=api \
 --requests=cpu=100,memory=384Mi --limits=cpu=100,memory=768Mi
kubectl -n shop get pods -l app=payment-api
kubectl -n shop describe pod NEW_PENDING_POD
```

요청 100 CPU 코어를 만족하는 Worker가 없어 Pending이다. 낮은 실제 CPU 사용률이어도 request 기반 스케줄링은 실패할 수 있다.

```bash
kubectl -n shop set resources deployment/payment-api --containers=api \
 --requests=cpu=250m,memory=384Mi --limits=cpu=1,memory=768Mi
kubectl -n shop rollout status deployment/payment-api --timeout=300s
```

## 12. 잘못된 노드 라벨 조건 — Pending

```bash
kubectl -n shop patch deployment order-api --type=merge \
 -p '{"spec":{"template":{"spec":{"nodeSelector":{"lab-role":"worker-typo"}}}}}'
kubectl -n shop get pods -l app=order-api
kubectl get nodes -L lab-role
kubectl -n shop describe pod NEW_PENDING_POD
```

Events의 node selector/affinity 불일치를 확인한다.

```bash
kubectl -n shop patch deployment order-api --type=merge \
 -p '{"spec":{"template":{"spec":{"nodeSelector":{"lab-role":"worker"}}}}}'
kubectl -n shop rollout status deployment/order-api --timeout=300s
```

## 13. Worker1 계획 점검 — cordon / drain / uncordon

DB가 고정된 Worker2가 아니라 Worker1에서 먼저 실습한다. 다른 Worker의 여유 리소스를 확인한다.

```bash
kubectl cordon k3s-worker1
kubectl get nodes
kubectl drain k3s-worker1 --ignore-daemonsets --delete-emptydir-data --timeout=180s
kubectl -n shop get pods -o wide
kubectl -n shop get pdb
```

cordon은 신규 배치만 막으며 기존 Pod를 옮기지 않는다. drain은 Eviction API를 사용하여 PDB를 따른다. PDB minAvailable=1 때문에 마지막 정상 API를 동시에 축출하지 않는다. 여유 리소스/Pod 상태 문제로 중단되면 describe/PDB를 확인하고 정상화한다. `--disable-eviction`으로 우회하지 않는다.

`--delete-emptydir-data`는 Pod의 임시 emptyDir 데이터를 버리는 옵션이며 PostgreSQL PVC 삭제 옵션이 아니다. 그래도 Worker2 drain은 DB 중단/로컬 볼륨 고정 때문에 별도 계획 없이 하지 않는다.

```bash
kubectl uncordon k3s-worker1
kubectl get nodes
```

uncordon만으로 이미 Worker2에 실행 중인 Pod가 자동 재분산되는 것은 아니다. 분산을 다시 관찰하려면 충분히 안정화한 뒤:

```bash
kubectl -n shop rollout restart deployment/member-api deployment/order-api deployment/payment-api
kubectl -n shop rollout status deployment/member-api --timeout=300s
kubectl -n shop rollout status deployment/order-api --timeout=300s
kubectl -n shop rollout status deployment/payment-api --timeout=300s
kubectl -n shop get pods -o wide
```

## 14. Worker1 에이전트 장애 / EC2 실제 중단

**Worker1 SSH에서만:**

```bash
sudo systemctl stop k3s-agent
```

**CP에서:**

```bash
kubectl get nodes -w
kubectl -n shop get pods -o wide
kubectl describe node k3s-worker1
```

에이전트를 중지해도 이미 실행 중인 컨테이너는 계속 실행될 수 있다. 따라서 이것은 **EC2 전원 중단과 동일한 실험이 아니다.** NotReady 탐지, 기본 not-ready/unreachable toleration 약 300초 및 controller 처리 시간 때문에 다른 노드 재배치가 즉시 발생하지 않을 수 있다. 장애 노드의 terminating Pod가 남아 전체 표시 개수가 8보다 커질 수도 있다.

복구는 Worker1에서:

```bash
sudo systemctl start k3s-agent
sudo systemctl status k3s-agent --no-pager
```

CP에서:

```bash
kubectl wait --for=condition=Ready node/k3s-worker1 --timeout=180s
kubectl uncordon k3s-worker1
kubectl -n shop get pods -o wide
```

실제 전원 장애는 AWS 콘솔에서 Worker1 인스턴스만 Stop/Start하여 별도로 실습한다. 먼저 INGRESS_IP와 PC hosts를 살아 있는 Worker2로 바꾸거나 NLB를 구성한다. hosts가 중단된 Worker1 IP를 가리키면 API 복제본이 살아 있어도 접속이 실패한다. EC2 Start 후 Public IP 변경 여부를 확인한다.

## 15. Worker2 중단 — 로컬 PVC의 장애 경계

DB 백업을 확보한 뒤 AWS 콘솔에서 Worker2만 Stop한다. CP에서:

```bash
kubectl -n shop get pods -o wide
kubectl -n shop get pvc data-postgres-0
LAB_PV=$(kubectl -n shop get pvc data-postgres-0 -o jsonpath='{.spec.volumeName}')
kubectl describe pv "$LAB_PV"
kubectl -n shop describe pod postgres-0
```

PostgreSQL은 worker2 로컬 데이터와 nodeSelector에 묶여 있어 worker1에서 자동으로 데이터를 이어받지 못한다. 기존 StatefulSet Pod가 Terminating/Unknown으로 남거나 재생성 대기일 수 있다. **강제 Pod 삭제·PV node affinity 수정만으로 데이터가 다른 노드로 이동하지 않는다.**

Worker2 Start → Ready → postgres-0 Ready → API Ready 순으로 복구한다. 영구 유실이면 새 PVC/DB를 마련하고 **노드 밖에 보관한 pg_dump 백업으로 복구**한다. 노드가 살아 있을 가능성이 있는 네트워크 분리 상황에서 DB Pod를 강제 삭제하여 이중 실행을 유도하지 않는다.

## 16. Control Plane 중단 — 제어 기능과 트래픽 구분

**CP의 별도 SSH 세션에서:**

```bash
sudo systemctl stop k3s
```

kubectl은 연결 거부/타임아웃이 발생한다. Worker에서 이미 실행 중인 Pod와 기존 라우팅은 계속 동작할 수 있지만 신규 배포/재배치/장애 복구는 정상 동작하지 않는다. CP에 있는 모니터링도 영향받을 수 있으며 **CP EC2 자체를 Stop하면 Grafana/Prometheus는 접속 불가**이다.

같은 CP SSH에서 복구:

```bash
sudo systemctl start k3s
sudo systemctl status k3s --no-pager
kubectl get nodes
kubectl -n shop get pods -o wide
```

DB/etcd HA와 별개로 CP가 한 대라는 제약을 관찰하는 실습이다. SQLite 기반 이 구성에 `k3s etcd-snapshot` 명령을 사용하지 않는다.

## 17. DNS 설정 오류 — Service 이름 해석 실패

새 payment-api Pod의 DNS만 잘못 지정한다. CoreDNS 전체를 중지하지 않는다.

```bash
kubectl -n shop patch deployment payment-api --type=merge \
 -p '{"spec":{"template":{"spec":{"dnsPolicy":"None","dnsConfig":{"nameservers":["203.0.113.53"]}}}}}'
kubectl -n shop logs NEW_POD_NAME --tail=100
kubectl -n kube-system get pods -l k8s-app=kube-dns
kubectl -n shop run dns-check --image=busybox:1.36 --restart=Never \
 --command -- nslookup postgres.shop.svc.cluster.local
kubectl -n shop logs dns-check
```

payment-api는 DB 이름 해석/연결에 실패하지만 정상 DNS 설정의 진단 Pod는 이름을 해석할 수 있다. 이 차이로 전체 DNS 장애와 개별 Pod 설정 오류를 구분한다.

```bash
kubectl -n shop patch deployment payment-api --type=json \
 -p='[{"op":"replace","path":"/spec/template/spec/dnsPolicy","value":"ClusterFirst"},{"op":"remove","path":"/spec/template/spec/dnsConfig"}]'
kubectl -n shop delete pod dns-check --ignore-not-found
kubectl -n shop rollout status deployment/payment-api --timeout=300s
```

## 18. 디스크 부족 / DiskPressure — 진단 중심

의도적으로 디스크를 가득 채우지 않는다. 문제가 발생한 노드에 SSH로 접속하여 확인한다.

```bash
df -h
df -i
sudo du -xhd1 /var/lib/rancher/k3s /var/log 2>/dev/null
sudo journalctl --disk-usage
```

CP에서:

```bash
kubectl describe node k3s-worker2
kubectl get events -A --sort-by=.metadata.creationTimestamp
```

사용량/인오드 고갈, 이미지·로그·DB 데이터 중 원인을 식별한다. 승인된 보관 정책으로 로그를 정리하거나 AWS 콘솔에서 EBS를 증설한 뒤 OS의 실제 디스크/파티션/파일시스템에 맞게 확장한다. containerd 내부 디렉터리나 PV 데이터 폴더를 임의 삭제하지 않는다. local-path의 10Gi 요청은 노드 디스크를 강제 격리하는 quota가 아니므로 **노드 디스크 그래프도 함께 확인**한다.

## 19. Prometheus No data — 수집 장애와 서비스 장애 구분

```bash
kubectl -n monitoring get pods
kubectl -n monitoring get prometheus
kubectl -n kube-system get pods -l k8s-app=metrics-server
kubectl top nodes
kubectl -n shop port-forward svc/member-api 18080:8080
```

다른 CP 터미널에서 `curl -fsS http://127.0.0.1:18080/actuator/prometheus | head`로 메트릭 노출을 확인한다. 이후 Prometheus Targets의 `shop-apis`, `traefik-pods` 오류를 확인한다. 앱 직접 메트릭은 응답하지만 Targets DOWN이면 수집 경로/라벨/네트워크를 본다. Targets UP인데 특정 HTTP 시계열이 없으면 실제 해당 경로 요청이 아직 없을 수 있다.

`kubectl top`은 Metrics Server를 사용하므로 Prometheus 정상 여부의 증거는 아니다. 반대로 top 실패만으로 Grafana 장애라고 판단하지 않는다.

## 20. GitHub 배포 실패 — 인증/권한/Runner 분리

```bash
sudo /home/runner/actions-runner/svc.sh status
sudo -u runner env KUBECONFIG=/home/runner/.kube/config \
 kubectl auth can-i patch deployment/member-api -n shop
```

- queued: Runner Idle 여부/label shop-lab 확인.
- Unauthorized: 단기 토큰 만료 가능. 관리자 계정에서 아래 실행.
- Forbidden: RBAC/namespace 확인. 관리자 kubeconfig로 대체하지 않는다.
- build 실패: Java 테스트/웹 E2E/GHCR 권한을 확인. Kubernetes 재시작으로 해결되지 않는다.

```bash
bash scripts/runner-credentials.sh
kubectl apply -f k8s/runner-rbac.yaml
```

## 실습 종료 확인

```bash
kubectl -n shop delete networkpolicy lab-block-db --ignore-not-found
kubectl -n lab-faults delete pod oom-demo --ignore-not-found
kubectl uncordon k3s-worker1
kubectl uncordon k3s-worker2
kubectl -n shop scale statefulset postgres --replicas=1
bash scripts/check-cluster.sh
```

이 명령만으로 모든 잘못된 이미지/probe/리소스 설정을 복구하는 것은 아니다. 각 실습의 복구 명령을 먼저 수행한다. 저장소 기본 설정으로 재설정하려면 정상 IMAGE/WEB_IMAGE 값을 다시 지정하고 `bash scripts/bootstrap.sh`를 실행한다. 정상 업무 Pod 8개, 노드 Ready 3개, API 가용 복제본 각 2개, Ingress 접속, 기존 민원 데이터까지 확인하고 ENABLE_DEPLOY=true로 복원한다.

공식 참고:
- https://kubernetes.io/docs/tasks/run-application/configure-pdb/
- https://kubernetes.io/docs/concepts/scheduling-eviction/taint-and-toleration/
- https://kubernetes.io/docs/concepts/architecture/nodes/
- https://kubernetes.io/docs/tasks/debug/debug-application/debug-running-pod/
