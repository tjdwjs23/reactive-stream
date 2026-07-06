// 모든 시나리오가 공유하는 설정. 값은 환경변수로 오버라이드합니다.
//   BASE_URL     대상 서버 (기본 로컬 bootRun)
//   ADMIN_TOKEN  admin 엔드포인트(reindex/flush) 호출용 X-Admin-Token 값
//                로컬 기본은 board.admin.token 미설정 → 아무 값이나 통과(경고 로그만).
//
// 예)  BASE_URL=http://localhost:8080 k6 run load/scenarios/mixed.js

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const ADMIN_TOKEN = __ENV.ADMIN_TOKEN || '';

// 공용 합격 기준(threshold). 이 선을 넘으면 k6가 종료 코드 99로 실패 → CI 게이트로 그대로 씀.
// 시나리오별로 필요하면 각 스크립트에서 병합해 덮어씁니다.
export const BASE_THRESHOLDS = {
  http_req_failed: ['rate<0.01'], // 에러율 1% 미만
  http_req_duration: ['p(95)<300', 'p(99)<800'], // 전체 요청 지연(ms)
  checks: ['rate>0.99'], // check 통과율 99% 이상
};

// 공용 HTTP 헤더.
export const JSON_HEADERS = { 'Content-Type': 'application/json' };
export const ADMIN_HEADERS = { 'X-Admin-Token': ADMIN_TOKEN };
