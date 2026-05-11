/**
 * Proxy para desarrollo: /api → backend en 8081.
 * Reescribe Set-Cookie para Domain=localhost y Path=/ así la sesión persiste al refrescar (4200).
 */
const PROXY_CONFIG = {
  '/api': {
    target: 'http://localhost:8081',
    secure: false,
    changeOrigin: true,
    onProxyRes(proxyRes) {
      const setCookie = proxyRes.headers['set-cookie'];
      if (setCookie) {
        proxyRes.headers['set-cookie'] = (Array.isArray(setCookie) ? setCookie : [setCookie]).map((c) => {
          let cookie = c.replace(/;\s*Domain=[^;]+/gi, '');
          if (!/;\s*Domain=/i.test(cookie)) {
            cookie = cookie.replace(/;\s*$/, '') + '; Domain=localhost';
          }
          if (!/;\s*Path=/i.test(cookie)) {
            cookie = cookie + '; Path=/';
          }
          return cookie;
        });
      }
    },
  },
};

module.exports = PROXY_CONFIG;
