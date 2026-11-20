// main.js – orchestrates UI, authentication, real‑time notifications, and task status updates

// The API wrapper (api.js) is already loaded and attaches `window.api`
// This script builds on the existing functions from app.js (loadBackendData, renderAll, showPage, showToast, etc.)

document.addEventListener('DOMContentLoaded', async () => {
  // -----------------------------------------------------------------
  // 1️⃣ Ensure authentication header is present – prompt if missing
  // -----------------------------------------------------------------
  if (!sessionStorage.getItem('authHeader')) {
    const user = prompt('Enter your username (manager or employee)');
    const pass = prompt('Enter your password');
    if (user && pass) {
      window.api.setAuth(user, pass);
    }
  }

  // -----------------------------------------------------------------
  // 2️⃣ Load initial data (employees, tasks, assignments) and render UI
  // -----------------------------------------------------------------
  await loadBackendData();

  // -----------------------------------------------------------------
  // 3️⃣ Real‑time notifications via Server‑Sent Events
  // -----------------------------------------------------------------
  const source = new EventSource(`${API_BASE}/stream`);
  source.onmessage = e => {
    try {
      const notif = JSON.parse(e.data);
      // Show a toast – you can extend this to update a notification panel
      showToast(`🔔 ${notif.message}`);
    } catch (err) {
      console.error('Failed to parse SSE message', err);
    }
  };
  source.onerror = err => {
    console.error('SSE connection error', err);
    // Try to reconnect after a short delay
    setTimeout(() => {
      source.close();
      // Re‑create the EventSource (simple reconnect strategy)
      const retry = new EventSource(`${API_BASE}/stream`);
      retry.onmessage = source.onmessage;
    }, 3000);
  };

  // -----------------------------------------------------------------
  // 4️⃣ Attach delegated listener for task‑status buttons (Mark Completed)
  // -----------------------------------------------------------------
  const taskTable = document.getElementById('task-table');
  if (taskTable) {
    taskTable.addEventListener('click', async ev => {
      const btn = ev.target.closest('button[data-status]');
      if (!btn) return;
      const taskId = btn.dataset.id;
      const newStatus = btn.dataset.status;
      try {
        await window.api.apiPatch(`/tasks/${taskId}/status`, { status: newStatus });
        showToast(`Task ${taskId} marked as ${newStatus}`);
        await loadBackendData(false);
      } catch (err) {
        console.error(err);
        showToast(`Failed to update task: ${err.message}`);
      }
    });
  }

  // -----------------------------------------------------------------
  // 5️⃣ (Optional) Quick helper to load an employee's personal task list
  // -----------------------------------------------------------------
  // Assuming a page with id "employee-tasks-page" and a container "employee-task-table"
  // You can call this function from a manager view to inspect a specific employee.
  window.loadEmployeeTasks = async function (employeeId) {
    try {
      const tasks = await window.api.apiGet(`/tasks/employee/${employeeId}`);
      const container = document.getElementById('employee-task-table');
      if (!container) return;
      container.innerHTML = tasks.map(t => `
        <tr>
          <td>${escapeHTML(t.title)}</td>
          <td>${skillPills([t.requiredSkill])}</td>
          <td>${t.priority}</td>
          <td>${t.durationHours}h</td>
          <td>${formatDate(t.deadline)}</td>
          <td>${t.status}</td>
        </tr>`).join('');
    } catch (e) {
      console.error(e);
      showToast('Could not load employee tasks');
    }
  };
});
