import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 30,
  duration: '45s',

  thresholds: {
    http_req_duration: [
      'p(95)<200',
      'p(99)<500',
    ],
  },
};

export default function () {
  const clientId = `client-${__VU}`;

  const res = http.post(
    'http://localhost:8080/api/demo/action',
    null,
    {
      headers: {
        'X-Client-Id': clientId,
      },
    }
  );

  check(res, {
    'status is 200 or 429': (r) =>
      r.status === 200 || r.status === 429,
  });
}