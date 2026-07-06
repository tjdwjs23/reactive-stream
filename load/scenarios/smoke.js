// 스모크 테스트: 부하가 아니라 "경로가 다 살아있나" 최소 검증.
// 본 부하를 돌리기 전에 항상 먼저 실행해 환경/시드가 정상인지 확인합니다.
//   k6 run load/scenarios/smoke.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL } from '../lib/config.js';
import { createBoard, pick, SEARCH_KEYWORDS } from '../lib/helpers.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate==1.0'], // 스모크는 100% 통과해야 함
  },
};

export default function () {
  // 1) 생성
  const id = createBoard({ name: 'create' });
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
