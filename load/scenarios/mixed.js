// ★ 메인 시나리오 — 현실적인 종합 트래픽 믹스.
// 읽기 70% : 쓰기 20% : 검색 10% 비율로 한 서버에 동시에 부하를 줍니다.
// 세 경로(Redis 조회수 / DB write + ES 인라인 색인 / ES 검색)가 서로 경합하는 상황을 봅니다.
//
//   BASE_URL=http://localhost:8080 k6 run load/scenarios/mixed.js
//
// 비율/강도 조절(환경변수):
//   HOT_COUNT   인기 글 시드 수(읽기·검색 대상)   기본 30
//   PEAK_RATE   피크 시 목표 RPS                    기본 1000
//   READ_PCT / WRITE_PCT   읽기/쓰기 비중(%)        기본 70 / 20 (나머지는 검색)

import http from 'k6/http';
import { check } from 'k6';
import {
  BASE_URL,
  authHeaders,
  BASE_THRESHOLDS,
  LOAD_USERNAME,
  LOAD_PASSWORD,
  ADMIN_USERNAME,
  ADMIN_PASSWORD,
} from '../lib/config.js';
import { seedBoards, signUpAndLogin, login, randomBoardPayload, pick, SEARCH_KEYWORDS } from '../lib/helpers.js';

const HOT_COUNT = Number(__ENV.HOT_COUNT || 30);
const PEAK_RATE = Number(__ENV.PEAK_RATE || 1000);
const READ_PCT = Number(__ENV.READ_PCT || 70);
const WRITE_PCT = Number(__ENV.WRITE_PCT || 20);
// 나머지(= 100 - READ - WRITE)는 검색.

// 실행 시간 프로파일(스윕용으로 짧게 덮어쓸 수 있게 env로). 미지정 시 기본 = 워밍업30s+램프1m+지속3m+램프다운30s.
const WARMUP_DUR = __ENV.WARMUP_DUR || '30s';
const RAMP_DUR = __ENV.RAMP_DUR || '1m';
const SUSTAIN_DUR = __ENV.SUSTAIN_DUR || '3m';
const RAMPDOWN_DUR = __ENV.RAMPDOWN_DUR || '30s';
// 목표 RPS를 지연 없이 소화하려면 VU를 넉넉히 미리 확보. 기본은 PEAK_RATE에 비례.
const PRE_VUS = Number(__ENV.PRE_VUS || Math.min(3000, Math.max(300, PEAK_RATE)));

export const options = {
  scenarios: {
    mixed: {
      executor: 'ramping-arrival-rate',
      startRate: Math.max(20, Math.floor(PEAK_RATE * 0.05)),
      timeUnit: '1s',
      preAllocatedVUs: PRE_VUS,
      maxVUs: 5000,
      stages: [
        { target: Math.floor(PEAK_RATE * 0.3), duration: WARMUP_DUR }, // 워밍업
        { target: PEAK_RATE, duration: RAMP_DUR }, // 램프업
        { target: PEAK_RATE, duration: SUSTAIN_DUR }, // 피크 지속
        { target: 0, duration: RAMPDOWN_DUR }, // 램프다운
      ],
    },
  },
  // 경로별로 threshold를 따로 잡아, 어떤 경로가 먼저 무너지는지 분리해서 봅니다.
  thresholds: Object.assign({}, BASE_THRESHOLDS, {
    'http_req_duration{op:read}': ['p(95)<200'],
    'http_req_duration{op:write}': ['p(95)<400'],
    'http_req_duration{op:search}': ['p(95)<350'],
  }),
};

export function setup() {
  // 쓰기는 인증이 필요하므로 로드용 사용자를 가입/로그인해 토큰을 확보합니다.
  const userToken = signUpAndLogin(LOAD_USERNAME, LOAD_PASSWORD);
  if (!userToken) throw new Error('로드 유저 로그인 실패: 서버가 떠 있고 PostgreSQL이 연결됐는지 확인하세요.');
  // admin 플러시(teardown)용 토큰. ADMIN_PASSWORD가 서버 설정과 일치하면 확보, 아니면 null(플러시 생략).
  const adminToken = ADMIN_PASSWORD ? login(ADMIN_USERNAME, ADMIN_PASSWORD) : null;

  const ids = seedBoards(HOT_COUNT, userToken);
  if (ids.length === 0) throw new Error('시드 실패: 서버가 떠 있고 PostgreSQL이 연결됐는지 확인하세요.');
  console.log(`[setup] 인기 글 ${ids.length}건 시드 완료 (admin 토큰 ${adminToken ? '확보' : '없음'})`);
  return { ids, userToken, adminToken };
}

export default function (data) {
  const roll = Math.random() * 100;

  if (roll < READ_PCT) {
    // 읽기: 인기 글 단건 조회(→ Redis 조회수 increment)
    const id = pick(data.ids);
    const res = http.get(`${BASE_URL}/api/boards/${id}`, { tags: { name: 'get', op: 'read' } });
    check(res, { 'read 200': (r) => r.status === 200 });
  } else if (roll < READ_PCT + WRITE_PCT) {
    // 쓰기: 새 글 생성(→ DB write + 인라인 ES 색인). 인증 필요 — 로드 유저 토큰을 Bearer로 전달.
    const res = http.post(`${BASE_URL}/api/boards`, JSON.stringify(randomBoardPayload()), {
      headers: authHeaders(data.userToken),
      tags: { name: 'create', op: 'write' },
    });
    check(res, { 'write 201': (r) => r.status === 201 });
  } else {
    // 검색: 한글 전문검색(→ ES/Nori)
    const kw = encodeURIComponent(pick(SEARCH_KEYWORDS));
    const res = http.get(`${BASE_URL}/api/boards/search?keyword=${kw}&size=20`, {
      tags: { name: 'search', op: 'search' },
    });
    check(res, { 'search 200': (r) => r.status === 200 });
  }
}

// 부하 종료 후 조회수 델타를 DB로 flush(누적 델타 write-back 경로까지 마무리 검증).
// flush는 ROLE_ADMIN이 필요하므로 admin 토큰이 있을 때만 호출합니다(없으면 생략).
export function teardown(data) {
  if (!data.adminToken) {
    console.log('[teardown] admin 토큰 없음 → view-count flush 생략 (ADMIN_PASSWORD 설정 시 수행)');
    return;
  }
  const res = http.post(`${BASE_URL}/api/admin/view-counts/flush`, null, {
    headers: authHeaders(data.adminToken),
    tags: { name: 'flush' },
  });
  console.log(`[teardown] view-count flush status=${res.status} body=${res.body}`);
}
