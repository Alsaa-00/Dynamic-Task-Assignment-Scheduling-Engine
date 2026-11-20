// API wrapper for backend calls with Basic Auth
// Store credentials in sessionStorage as base64 "username:password"
// Usage:
//   setAuth('user', 'pwd'); // called after successful login
//   apiGet('/employees').then(...);

const API_BASE = "http://localhost:8080/api";

function setAuth(username, password) {
  const token = btoa(`${username}:${password}`);
  sessionStorage.setItem('authHeader', `Basic ${token}`);
}

function getAuthHeader() {
  return sessionStorage.getItem('authHeader') || '';
}

async function request(path, options = {}) {
  const headers = {
    "Content-Type": "application/json",
    ...(options.headers || {}),
    ...(getAuthHeader() && { "Authorization": getAuthHeader() })
  };
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers
  });
  if (!response.ok) {
    if (response.status === 401) {
      // Prompt for credentials if not set
      const user = prompt("Enter username:");
      const pass = prompt("Enter password:");
      if (user && pass) {
        setAuth(user, pass);
        return request(path, options); // retry
      }
    }
    const msg = await response.text().catch(() => "");
    throw new Error(msg || `HTTP ${response.status}`);
  }
  if (response.status === 204) return null;
  const txt = await response.text();
  return txt ? JSON.parse(txt) : null;
}

function apiGet(path) { return request(path, { method: 'GET' }); }
function apiPost(path, body) { return request(path, { method: 'POST', body: JSON.stringify(body) }); }
function apiPatch(path, body) { return request(path, { method: 'PATCH', body: JSON.stringify(body) }); }

// Export for other scripts (in browsers this attaches to window)
window.api = { setAuth, apiGet, apiPost, apiPatch };
