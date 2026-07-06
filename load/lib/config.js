// 모든 시나리오가 공유하는 설정. 값은 환경변수로 오버라이드합니다.
//   BASE_URL        대상 서버 (기본 로컬 bootRun)
//   LOAD_USERNAME / LOAD_PASSWORD   쓰기 부하용 일반 사용자(setup에서 가입/로그인)
//   ADMIN_USERNAME / ADMIN_PASSWORD 관리자(flush/reindex) 자격. 서버 board.security.admin.* 와 일치해야 함.
//                                   ADMIN_PASSWORD가 비면 teardown 플러시를 건너뜁니다.
//
// 예)  BASE_URL=http://localhost:8080 ADMIN_PASSWORD=admin-pass-123 k6 run load/scenarios/mixed.js

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const LOAD_USERNAME = __ENV.LOAD_USERNAME || 'loadtester';
export const LOAD_PASSWORD = __ENV.LOAD_PASSWORD || 'loadtest-password';
export const ADMIN_USERNAME = __ENV.ADMIN_USERNAME || 'admin';
export const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || '';

// 공용 합격 기준(threshold). 이 선을 넘으면 k6가 종료 코드 99로 실패 → CI 게이트로 그대로 씀.
// 시나리오별로 필요하면 각 스크립트에서 병합해 덮어씁니다.
export const BASE_THRESHOLDS = {
  http_req_failed: ['rate<0.01'], // 에러율 1% 미만
  http_req_duration: ['p(95)<300', 'p(99)<800'], // 전체 요청 지연(ms)
  checks: ['rate>0.99'], // check 통과율 99% 이상
};

// 공용 HTTP 헤더.
export const JSON_HEADERS = { 'Content-Type': 'application/json' };

// Bearer 인증 헤더(쓰기·admin 요청용).
export function authHeaders(token) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}
