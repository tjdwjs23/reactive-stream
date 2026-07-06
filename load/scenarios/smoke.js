// 스모크 테스트: 부하가 아니라 "경로가 다 살아있나" 최소 검증.
// 본 부하를 돌리기 전에 항상 먼저 실행해 환경/시드가 정상인지 확인합니다.
//   k6 run load/scenarios/smoke.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, LOAD_USERNAME, LOAD_PASSWORD } from '../lib/config.js';
import { createBoard, signUpAndLogin, pick, SEARCH_KEYWORDS } from '../lib/helpers.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate==1.0'], // 스모크는 100% 통과해야 함
  },
};

// 쓰기(생성) 경로가 인증을 요구하므로 토큰을 먼저 확보합니다.
export function setup() {
  const token = signUpAndLogin(LOAD_USERNAME, LOAD_PASSWORD);
  if (!token) throw new Error('로그인 실패: 서버/DB 상태를 확인하세요.');
  return { token };
}

export default function (data) {
  // 1) 생성(인증 필요)
  const id = createBoard(data.token, { name: 'create' });
  check(id, { '생성된 id 존재': (v) => v != null });

  // 2) 단건 조회(→ Redis 조회수 증가 경로)
  const get = http.get(`${BASE_URL}/api/boards/${id}`, { tags: { name: 'get' } });
  check(get, { 'get 200': (r) => r.status === 200 });

  // 3) 목록(키셋)
  const list = http.get(`${BASE_URL}/api/boards?size=10`, { tags: { name: 'list' } });
  check(list, { 'list 200': (r) => r.status === 200 });

  // 4) 검색(ES/Nori) — 색인 refresh(~1s) 여유를 주고 조회
  sleep(1.5);
  const kw = encodeURIComponent(pick(SEARCH_KEYWORDS));
  const search = http.get(`${BASE_URL}/api/boards/search?keyword=${kw}&size=10`, {
    tags: { name: 'search' },
  });
  check(search, { 'search 200': (r) => r.status === 200 });
}
