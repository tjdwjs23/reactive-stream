// ★ 키셋 페이지네이션 "깊이 무관성(depth-independence)" 증명 시나리오.
//
// 목적: OFFSET 페이지네이션은 깊이(뒤 페이지)로 갈수록 앞 행을 전부 스캔·폐기해 지연이 선형으로
//       증가하지만, 키셋(cursor = 마지막으로 본 id)은 인덱스 시크라 "몇 페이지째냐"와 무관하게
//       일정한 지연을 낸다. 이 스크립트는 같은 부하를 커서 깊이별(shallow/mid/deep)로 뿌리고
//       depth 태그별 p95를 비교해 그 평탄함을 실측으로 보여준다.
//
//   BASE_URL=http://localhost:8080 k6 run load/scenarios/pagination.js
//
// 전제: board 테이블에 대용량 데이터가 이미 시드돼 있어야 한다(수십만 행 권장). 커서 범위는
//       setup()이 목록 API로 자동 탐지하며(ID_MAX), 필요하면 env로 직접 지정할 수 있다.
//
// 강도/범위 조절(환경변수):
//   PAGE_SIZE   한 페이지 크기                       기본 20
//   PEAK_RATE   피크 시 목표 RPS                      기본 2000
//   ID_MIN      커서 하한(가장 오래된 id)             기본 1
//   ID_MAX      커서 상한(가장 최신 id). 미지정 시 자동 탐지
//   DEEP_FRAC   'deep' 버킷이 건너뛴 것과 등가인 비율  기본 0.95 (전체의 95% 지점 = 사실상 마지막 페이지)

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, BASE_THRESHOLDS } from '../lib/config.js';
import { pick } from '../lib/helpers.js';

const PAGE_SIZE = Number(__ENV.PAGE_SIZE || 20);
const PEAK_RATE = Number(__ENV.PEAK_RATE || 2000);
const ID_MIN = Number(__ENV.ID_MIN || 1);
const DEEP_FRAC = Number(__ENV.DEEP_FRAC || 0.95);

const WARMUP_DUR = __ENV.WARMUP_DUR || '10s';
const RAMP_DUR = __ENV.RAMP_DUR || '10s';
const SUSTAIN_DUR = __ENV.SUSTAIN_DUR || '40s';
const RAMPDOWN_DUR = __ENV.RAMPDOWN_DUR || '5s';
const PRE_VUS = Number(__ENV.PRE_VUS || Math.min(3000, Math.max(200, PEAK_RATE)));

export const options = {
  scenarios: {
    pagination: {
      executor: 'ramping-arrival-rate',
      startRate: Math.max(20, Math.floor(PEAK_RATE * 0.05)),
      timeUnit: '1s',
      preAllocatedVUs: PRE_VUS,
      maxVUs: 5000,
      stages: [
        { target: Math.floor(PEAK_RATE * 0.3), duration: WARMUP_DUR },
        { target: PEAK_RATE, duration: RAMP_DUR },
        { target: PEAK_RATE, duration: SUSTAIN_DUR },
        { target: 0, duration: RAMPDOWN_DUR },
      ],
    },
  },
  // 깊이 버킷별로 p95를 따로 본다. 깊이무관이 성립하면 셋이 거의 같은 값에 머문다.
  // (OFFSET이라면 deep이 shallow보다 수십~수백 배 느려져 이 threshold가 깨졌을 것.)
  thresholds: Object.assign({}, BASE_THRESHOLDS, {
    'http_req_duration{depth:shallow}': ['p(95)<50'],
    'http_req_duration{depth:mid}': ['p(95)<50'],
    'http_req_duration{depth:deep}': ['p(95)<50'],
  }),
};

// 커서 상한(최신 id)을 목록 API 첫 페이지에서 탐지한다(id 내림차순이라 첫 항목이 최댓값).
export function setup() {
  const envMax = Number(__ENV.ID_MAX || 0);
  if (envMax > 0) {
    console.log(`[setup] ID_MAX=${envMax} (env), ID_MIN=${ID_MIN}, size=${PAGE_SIZE}`);
    return { idMax: envMax };
  }
  const res = http.get(`${BASE_URL}/api/boards?size=1`, { tags: { name: 'probe' } });
  if (res.status !== 200) throw new Error(`ID_MAX 탐지 실패(status=${res.status}). 서버/데이터를 확인하세요.`);
  let idMax;
  try {
    idMax = res.json('result.items.0.id');
  } catch (_) {
    idMax = 0;
  }
  if (!idMax || idMax <= ID_MIN + PAGE_SIZE) {
    throw new Error(`데이터가 부족합니다(idMax=${idMax}). 대용량 시드 후 다시 실행하세요.`);
  }
  console.log(`[setup] idMax=${idMax} 자동 탐지 (ID_MIN=${ID_MIN}, size=${PAGE_SIZE}, deepFrac=${DEEP_FRAC})`);
  return { idMax };
}

// 세 깊이 지점. frac = "이 커서까지 도달하려면 OFFSET 방식이 건너뛰었어야 할 비율".
//   shallow ≈ 첫 페이지(OFFSET ~0), mid ≈ 전체의 절반 지점, deep ≈ 사실상 마지막 페이지.
const DEPTHS = [
  { name: 'shallow', frac: 0.0 },
  { name: 'mid', frac: 0.5 },
  { name: 'deep', frac: DEEP_FRAC },
];

export default function (data) {
  const range = data.idMax - ID_MIN;
  // 세 깊이를 균등 확률로 섞어, 같은 부하 아래에서 depth 태그별 지연을 나란히 비교한다.
  const depth = pick(DEPTHS);
  // 커서 = 최신에서 frac*range 만큼 내려간 지점(+ 소량 지터로 같은 행 반복/캐시 히트 방지).
  const jitter = Math.floor(Math.random() * Math.min(1000, range * 0.01));
  let cursor = data.idMax - Math.floor(depth.frac * range) - jitter;
  if (cursor < ID_MIN + PAGE_SIZE) cursor = ID_MIN + PAGE_SIZE;

  const res = http.get(`${BASE_URL}/api/boards?cursor=${cursor}&size=${PAGE_SIZE}`, {
    tags: { name: 'page', depth: depth.name },
  });
  check(res, {
    'page 200': (r) => r.status === 200,
    'page has items': (r) => {
      try {
        return Array.isArray(r.json('result.items'));
      } catch (_) {
        return false;
      }
    },
  });
}
