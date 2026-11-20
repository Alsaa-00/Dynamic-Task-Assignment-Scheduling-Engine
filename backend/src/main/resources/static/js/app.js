/**
 * TaskFlow - Intelligent Task Assignment & Scheduling Engine
 * Complete Application Logic, Authentication, Heuristic Workflows, & Real-Time Sync
 */
"use strict";

if (!String.prototype.equalsIgnoreCase) {
  String.prototype.equalsIgnoreCase = function(other) {
    if (other === null || other === undefined) return false;
    return this.toLowerCase() === String(other).toLowerCase();
  };
}

const API_BASE = (() => {
  if (typeof window !== "undefined" && window.location && window.location.protocol.startsWith("http")) {
    const port = window.location.port;
    // When served via static dev servers (e.g. VS Code Live Server 5500), target backend port 8080
    if (port && !["8080", "8081", "8082"].includes(port)) {
      return "http://localhost:8080/api";
    }
    // When served directly by Spring Boot (on 8080, 8081, etc.), use the same origin
    return `${window.location.origin}/api`;
  }
  return "http://localhost:8080/api";
})();

// --- Application State ---
const state = {
  currentUser: null,       // { id, username, role, employeeId, employeeName, authHeader }
  activePage: "manager-dashboard",
  employees: [],
  tasks: [],
  assignmentsHistory: [],
  emergencies: [],
  notifications: [],
  schedulingStatus: null,
  preemptions: [],
  sseSource: null,
  importType: "employees", // "employees" or "tasks"
  selectedImportFile: null,
  reassignSelectedTaskId: null,
  reassignSelectedEmpId: null,
  lastAssignmentResults: []
};

// --- DOM Helpers ---
const $ = (id) => document.getElementById(id);
const $$ = (sel) => document.querySelectorAll(sel);

function escapeHTML(str) {
  if (str === null || str === undefined) return "";
  return String(str)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function formatDate(isoStr) {
  if (!isoStr) return "N/A";
  try {
    const d = new Date(isoStr);
    if (isNaN(d.getTime())) return escapeHTML(isoStr);
    return d.toLocaleDateString("en-IN", { day: "2-digit", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
  } catch (e) {
    return escapeHTML(isoStr);
  }
}

// --- API Request Wrapper ---
async function apiRequest(path, options = {}) {
  const headers = {
    "Content-Type": "application/json",
    ...(options.headers || {})
  };

  if (state.currentUser && state.currentUser.authHeader) {
    headers["Authorization"] = state.currentUser.authHeader;
  } else if (!headers["Authorization"]) {
    headers["Authorization"] = "Basic " + btoa("manager:manager123");
  }

  // If body is FormData, delete Content-Type to allow browser multipart boundary
  if (options.body instanceof FormData) {
    delete headers["Content-Type"];
  }

  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers
  });

  if (!response.ok) {
    let msg = "";
    try {
      const errJson = await response.json();
      msg = errJson.message || errJson.error || JSON.stringify(errJson);
    } catch (e) {
      msg = await response.text().catch(() => "");
    }
    throw new Error(msg || `Request failed with status ${response.status}`);
  }

  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

// --- Toast Notifications (Clean, Non-Overlapping) ---
function showToast(message, type = "info") {
  const container = $("toast-container");
  if (!container) return;

  // Keep notification stack minimal (max 1 active summary/status toast or at most 2)
  while (container.children.length >= 1) {
    container.removeChild(container.firstChild);
  }

  const toast = document.createElement("div");
  toast.className = `toast-msg ${type}`;
  
  let icon = "🔔";
  if (type === "success") icon = "✅";
  if (type === "error") icon = "❌";
  if (type === "warning") icon = "⚠️";
  if (type === "info") icon = "ℹ️";

  const formattedMsg = escapeHTML(message).replace(/\n/g, "<br>");
  toast.innerHTML = `<span style="font-size:16px;">${icon}</span><span style="flex:1; line-height:1.45;">${formattedMsg}</span>`;
  container.appendChild(toast);

  setTimeout(() => {
    toast.style.opacity = "0";
    toast.style.transform = "translateY(10px)";
    setTimeout(() => {
      if (toast.parentNode) toast.remove();
    }, 300);
  }, 4500);
}

// --- Real-Time Server-Sent Events (SSE) ---
function initRealtimeStream() {
  if (state.sseSource) {
    state.sseSource.close();
  }

  try {
    state.sseSource = new EventSource(`${API_BASE}/stream`);

    state.sseSource.onopen = () => {
      const liveStatus = $("sse-live-status");
      if (liveStatus) liveStatus.textContent = "Live Synchronized";
    };

    state.sseSource.addEventListener("CONNECTED", (e) => {
      console.log("SSE connected:", e.data);
    });

    state.sseSource.addEventListener("NOTIFICATION", (e) => {
      try {
        const notif = JSON.parse(e.data);
        loadNotifications();
        loadDashboardData(false);

        // Suppress toast spam during automatic assignment runs or routine batch alerts
        if (state.suppressAssignmentSseToast) return;
        const msg = notif.message || "";
        if (msg.startsWith("Automatic assignment") || notif.type === "ALERT") return;

        // Only pop toast for urgent events (EMERGENCY / PREEMPTION) or messages addressed to this specific employee
        const isUrgent = notif.type === "EMERGENCY" || notif.type === "PREEMPTION";
        const isForMe = notif.employeeId && state.currentUser && state.currentUser.employeeId === notif.employeeId;

        if (isUrgent || isForMe) {
          showToast(msg, notif.type === "EMERGENCY" ? "error" : "info");
        }
      } catch (err) {
        console.error("Error parsing notification event", err);
      }
    });

    // Handle generic events
    const broadcastEvents = [
      "TASK_CREATED", "TASK_UPDATED", "TASK_COMPLETED", "TASK_DELETED", "TASK_REASSIGNED",
      "TASK_PREEMPTED", "TASK_RESUMED", "TASK_PAUSED", "TASK_STARTED",
      "EMPLOYEE_ADDED", "EMPLOYEE_UPDATED", "EMPLOYEE_DELETED",
      "ASSIGNMENTS_RUN", "EMERGENCY_REPORTED", "EMERGENCY_UPDATED", "IMPORT_COMPLETE"
    ];

    broadcastEvents.forEach(evtName => {
      state.sseSource.addEventListener(evtName, (e) => {
        loadAllData(false);
      });
    });

    state.sseSource.addEventListener("TASK_PREEMPTED", (e) => {
      try {
        const payload = JSON.parse(e.data);
        const msg = payload.message || "High-priority task assigned. Lower-priority task paused.";
        showToast("⚡ Preemption: " + msg, "warning");
        const banner = $("preemption-alert-banner");
        if (banner) {
          const bannerMsg = $("preemption-banner-message");
          if (bannerMsg) bannerMsg.textContent = msg;
          banner.style.display = "flex";
        }
      } catch (err) {}
    });

    state.sseSource.onerror = () => {
      const liveStatus = $("sse-live-status");
      if (liveStatus) liveStatus.textContent = "Reconnecting...";
    };
  } catch (err) {
    console.warn("SSE initialization warning:", err);
  }
}

// --- Authentication & Session Management ---
async function loginUser(username, password) {
  try {
    const data = await apiRequest("/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password })
    });

    state.currentUser = data;
    localStorage.setItem("taskflow_auth", JSON.stringify(data));

    $("login-overlay").style.display = "none";
    $("login-error").style.display = "none";

    applyUserSession();
    showToast(`Welcome back, ${data.employeeName || data.username}!`, "success");
    await loadAllData();
  } catch (err) {
    const errBox = $("login-error");
    errBox.textContent = err.message || "Invalid credentials.";
    errBox.style.display = "block";
  }
}

function logoutUser() {
  state.currentUser = null;
  localStorage.removeItem("taskflow_auth");
  $("login-overlay").style.display = "flex";
  $("login-username").value = "";
  $("login-password").value = "";
  $("login-error").style.display = "none";
  showToast("Logged out successfully.", "info");
}

function applyUserSession() {
  if (!state.currentUser) return;

  const isManager = state.currentUser.role === "MANAGER";

  // Topbar Profile
  $("topbar-user-name").textContent = state.currentUser.employeeName || state.currentUser.username;
  $("topbar-user-role").textContent = isManager ? "System Manager" : (state.currentUser.employeeRole || "Employee");
  $("topbar-avatar").textContent = (state.currentUser.employeeName || state.currentUser.username).charAt(0).toUpperCase();

  // Role Switcher in header
  $("header-role-switcher").value = state.currentUser.role;

  // Sidebar badge & navigation tabs
  const portalBadge = $("sidebar-portal-badge");
  const roleText = $("portal-role-text");
  const badgeTag = $("portal-badge-tag");

  if (isManager) {
    portalBadge.classList.remove("employee-mode");
    roleText.textContent = "Manager Portal";
    badgeTag.textContent = "ADMIN";
    $("manager-nav").style.display = "flex";
    $("employee-nav").style.display = "none";
    navigateToPage("manager-dashboard");
  } else {
    portalBadge.classList.add("employee-mode");
    roleText.textContent = "Employee Portal";
    badgeTag.textContent = "STAFF";
    $("manager-nav").style.display = "none";
    $("employee-nav").style.display = "flex";
    $("emp-dash-greeting-name").textContent = state.currentUser.employeeName || "Employee";
    navigateToPage("employee-dashboard");
  }
}

// --- Navigation ---
function navigateToPage(pageId) {
  state.activePage = pageId;

  // Update nav item active classes
  $$(".nav-btn").forEach(btn => {
    btn.classList.toggle("active", btn.dataset.page === pageId);
  });

  // Update views
  $$(".page-view").forEach(view => {
    view.classList.toggle("active", view.id === `${pageId}-view`);
  });

  // Breadcrumb
  const title = pageId.replace("manager-", "").replace("employee-", "").toUpperCase();
  $("breadcrumb-title").textContent = title;

  // Refresh page-specific view data
  renderCurrentView();
}

window.navigateToPage = navigateToPage;

// --- Data Fetching & Sync ---
async function loadAllData(showLoader = true) {
  try {
    const [employees, tasks, history, emergencies, notifs, schedStatus, preemptions] = await Promise.all([
      apiRequest("/employees"),
      apiRequest("/tasks"),
      apiRequest("/assignments/history"),
      apiRequest("/emergencies"),
      apiRequest("/notifications"),
      apiRequest("/scheduling/status").catch(() => null),
      apiRequest("/scheduling/preemptions").catch(() => [])
    ]);

    state.employees = employees || [];
    state.tasks = tasks || [];
    state.assignmentsHistory = history || [];
    state.emergencies = emergencies || [];
    state.notifications = notifs || [];
    state.schedulingStatus = schedStatus;
    state.preemptions = preemptions || [];

    updateNotificationBadge();
    renderCurrentView();
  } catch (err) {
    console.error("Failed to load application data:", err);
    if (showLoader) showToast("Error connecting to backend: " + err.message, "error");
  }
}

async function loadNotifications() {
  try {
    const empId = (state.currentUser && state.currentUser.role === "EMPLOYEE") ? state.currentUser.employeeId : null;
    const url = empId ? `/notifications?employeeId=${empId}` : "/notifications";
    state.notifications = await apiRequest(url) || [];
    updateNotificationBadge();
  } catch (err) {
    console.error("Notification load error:", err);
  }
}

function updateNotificationBadge() {
  const unreadCount = state.notifications.filter(n => !n.read).length;
  const badge = $("notif-count-badge");
  if (badge) {
    if (unreadCount > 0) {
      badge.textContent = unreadCount > 99 ? "99+" : unreadCount;
      badge.style.display = "block";
    } else {
      badge.style.display = "none";
    }
  }

  // Update emergency badge in manager sidebar
  const openEmergencies = state.emergencies.filter(e => e.status === "OPEN").length;
  const emergBadge = $("sidebar-emergencies-badge");
  if (emergBadge) {
    if (openEmergencies > 0) {
      emergBadge.textContent = openEmergencies;
      emergBadge.style.display = "inline-block";
    } else {
      emergBadge.style.display = "none";
    }
  }

  // Update employee tasks badge
  if (state.currentUser && state.currentUser.role === "EMPLOYEE") {
    const myActive = state.tasks.filter(t => t.assignedEmployeeId === state.currentUser.employeeId && t.status !== "Completed").length;
    const myBadge = $("sidebar-my-tasks-badge");
    if (myBadge) {
      if (myActive > 0) {
        myBadge.textContent = myActive;
        myBadge.style.display = "inline-block";
      } else {
        myBadge.style.display = "none";
      }
    }
  }
}

// --- Render View Dispatcher ---
function renderCurrentView() {
  switch (state.activePage) {
    case "manager-dashboard":
      renderManagerDashboard();
      break;
    case "manager-employees":
      renderEmployeesPage();
      break;
    case "manager-tasks":
      renderTasksPage();
      break;
    case "manager-workforce":
      renderWorkforceMonitoring();
      break;
    case "manager-emergencies":
      renderManagerEmergencies();
      break;
    case "manager-reports":
      renderReportsAndHistory();
      break;
    case "employee-dashboard":
      renderEmployeeDashboard();
      break;
    case "employee-tasks":
      renderEmployeeTasks();
      break;
    case "employee-history":
      renderEmployeeHistory();
      break;
    case "employee-emergencies":
      renderEmployeeEmergencies();
      break;
  }
}

// =========================================================================
// MANAGER PORTAL RENDERERS
// =========================================================================

// Deadline Risk Helper Functions
function calculateTaskDeadlineRisk(task) {
  if (!task) return "On Track";
  if (task.status === "Completed") return "On Track";
  if (task.deadlineRisk && (task.deadlineRisk === "Overdue" || task.deadlineRisk === "At Risk" || task.deadlineRisk === "On Track")) {
    return task.deadlineRisk;
  }
  if (!task.deadline) return "On Track";
  const todayStr = new Date().toISOString().slice(0, 10);
  if (task.deadline < todayStr) return "Overdue";
  const days = Math.max(0, Math.ceil((new Date(task.deadline) - new Date(todayStr)) / (1000 * 60 * 60 * 24)));
  if (days === 0) return "At Risk";
  const availHours = days * 8;
  const remainingHours = (task.remainingDurationHours !== null && task.remainingDurationHours !== undefined) ? task.remainingDurationHours : task.durationHours;
  if (remainingHours > availHours) return "At Risk";
  return "On Track";
}

function renderDeadlineRiskBadge(task) {
  const risk = calculateTaskDeadlineRisk(task);
  if (risk === "Overdue") {
    return `<span class="badge-risk-overdue" title="Deadline has passed!">🔴 Overdue</span>`;
  } else if (risk === "At Risk") {
    return `<span class="badge-risk-atrisk" title="Remaining time is tight relative to task hours required">🟠 At Risk</span>`;
  } else {
    return `<span class="badge-risk-ontrack" title="On schedule to meet deadline">🟢 On Track</span>`;
  }
}

window.filterTasksByStatus = function(status) {
  const select = $("task-status-filter");
  if (select) select.value = status;
  navigateToPage("manager-tasks");
};

function renderManagerDashboard() {
  const totalEmployees = state.employees.length;
  const availableEmployees = state.employees.filter(e => e.available).length;
  const busyEmployees = state.employees.filter(e => e.available && ((e.workloadHours > 0) || (e.activeTaskCount && e.activeTaskCount > 0))).length;

  const totalTasks = state.tasks.length;
  const pendingTasks = state.tasks.filter(t => t.status === "Pending" || t.status === "Unassigned").length;
  const inProgressTasks = state.tasks.filter(t => t.status === "In Progress").length;
  const completedTasks = state.tasks.filter(t => t.status === "Completed").length;
  const overdueTasks = state.tasks.filter(t => t.status !== "Completed" && calculateTaskDeadlineRisk(t) === "Overdue").length;

  // 6 Key Stat Cards
  if ($("stat-total-tasks")) $("stat-total-tasks").textContent = totalTasks;
  if ($("stat-sub-tasks")) $("stat-sub-tasks").textContent = `${inProgressTasks} active · ${pendingTasks} pending`;

  if ($("stat-pending-tasks")) $("stat-pending-tasks").textContent = pendingTasks;
  if ($("stat-inprogress-tasks")) $("stat-inprogress-tasks").textContent = inProgressTasks;
  if ($("stat-completed-tasks")) $("stat-completed-tasks").textContent = completedTasks;

  if ($("stat-overdue-tasks")) {
    $("stat-overdue-tasks").textContent = overdueTasks;
  }

  if ($("stat-total-employees")) $("stat-total-employees").textContent = totalEmployees;
  if ($("stat-sub-employees")) $("stat-sub-employees").textContent = `${availableEmployees} Available · ${busyEmployees} Active`;

  // Render Task Status Overview Box
  renderTaskStatusOverview(totalTasks, pendingTasks, inProgressTasks, completedTasks, overdueTasks);

  // Render Priority Distribution Box
  renderPriorityDistribution();

  // Render Employee Workload Overview Table
  renderDashboardEmployeeWorkload();

  // Recent Assignments Table
  const recentAssignments = state.assignmentsHistory.slice(0, 5);
  const recentTbody = $("dashboard-recent-assignments");
  if (recentTbody) {
    if (recentAssignments.length === 0) {
      recentTbody.innerHTML = `<tr><td colspan="4" class="empty-data-state">No assignment history logged yet. Click "Run Automatic Assignment" above.</td></tr>`;
    } else {
      recentTbody.innerHTML = recentAssignments.map(a => `
        <tr>
          <td><strong>${escapeHTML(a.taskTitle || "Task #" + a.taskId)}</strong></td>
          <td>${escapeHTML(a.employeeName || "Unassigned")}</td>
          <td>${a.workloadBefore !== null ? `${a.workloadBefore}h &rarr; ${a.workloadAfter}h` : "-"}</td>
          <td>
            <span class="badge ${a.outcome === "SUCCESS" ? "completed" : "pending"}">
              <span class="badge-dot"></span>${escapeHTML(a.outcome)}
            </span>
          </td>
        </tr>
      `).join("");
    }
  }

  // Workforce & Emergency Alerts Table
  const alertsTbody = $("dashboard-alerts-table");
  if (alertsTbody) {
    const openReports = state.emergencies.filter(e => e.status === "OPEN");
    const unassignedTasks = state.tasks.filter(t => t.status === "Unassigned");
    const failedAssignments = state.assignmentsHistory.filter(a => a.outcome === "FAILED").slice(0, 5);
    const atRiskTasks = state.tasks.filter(t => t.status !== "Completed" && calculateTaskDeadlineRisk(t) === "At Risk").slice(0, 4);

    if (openReports.length === 0 && failedAssignments.length === 0 && unassignedTasks.length === 0 && atRiskTasks.length === 0) {
      alertsTbody.innerHTML = `<tr><td colspan="4" class="empty-data-state" style="padding:20px;">✅ All operations nominal. No unassigned task alerts or pending emergencies.</td></tr>`;
    } else {
      let alertHtml = "";
      openReports.forEach(r => {
        alertHtml += `
          <tr style="background: #fff5f5;">
            <td><span class="badge unavailable">EMERGENCY</span></td>
            <td><strong>${escapeHTML(r.employeeName || "Employee #" + r.employeeId)}</strong>: ${escapeHTML(r.type)}</td>
            <td>${formatDate(r.createdAt)}</td>
            <td><button class="btn btn-sm btn-danger" onclick="window.navigateToPage('manager-emergencies')">Review</button></td>
          </tr>
        `;
      });
      atRiskTasks.forEach(t => {
        alertHtml += `
          <tr style="background: #fffbeb;">
            <td><span class="badge-risk-atrisk">AT RISK</span></td>
            <td><strong>${escapeHTML(t.title)}</strong><div style="font-size:11px; color:#b45309; margin-top:2px;">⚠️ Deadline ${escapeHTML(t.deadline)} (${t.durationHours}h needed)</div></td>
            <td>Immediate</td>
            <td><button class="btn btn-sm btn-secondary" onclick="window.navigateToPage('manager-tasks')">View</button></td>
          </tr>
        `;
      });
      const seenTaskIds = new Set();
      failedAssignments.forEach(f => {
        seenTaskIds.add(f.taskId);
        alertHtml += `
          <tr>
            <td><span class="badge pending">UNASSIGNED</span></td>
            <td><strong>${escapeHTML(f.taskTitle || "Task #" + f.taskId)}</strong><div style="font-size:11px; color:#b91c1c; margin-top:2px;">⚠️ ${escapeHTML(f.reason || "No eligible employee")}</div></td>
            <td>${formatDate(f.assignmentTime)}</td>
            <td><button class="btn btn-sm btn-secondary" onclick="window.openReassignModalForTask(${f.taskId})">Review &amp; Assign</button></td>
          </tr>
        `;
      });
      unassignedTasks.filter(u => !seenTaskIds.has(u.id)).forEach(u => {
        alertHtml += `
          <tr>
            <td><span class="badge pending">UNASSIGNED</span></td>
            <td><strong>${escapeHTML(u.title)}</strong><div style="font-size:11px; color:#b91c1c; margin-top:2px;">⚠️ Unassigned (${escapeHTML(u.requiredSkill)}, ${u.durationHours}h)</div></td>
            <td>${formatDate(u.createdAt)}</td>
            <td><button class="btn btn-sm btn-secondary" onclick="window.openReassignModalForTask(${u.id})">Review &amp; Assign</button></td>
          </tr>
        `;
      });
      alertsTbody.innerHTML = alertHtml;
    }
  }

  // Preemptive Scheduling & Priority Monitor
  renderPreemptiveDashboard();
}

function renderTaskStatusOverview(total, pending, inProgress, completed, overdue) {
  const container = $("task-status-overview-box");
  if (!container) return;

  if (total === 0) {
    container.innerHTML = `<div class="empty-data-state">No tasks created yet in workspace.</div>`;
    return;
  }

  const paused = state.tasks.filter(t => t.status === "Paused").length;

  const pPct = Math.round((pending / total) * 100) || 0;
  const ipPct = Math.round((inProgress / total) * 100) || 0;
  const cPct = Math.round((completed / total) * 100) || 0;
  const oPct = Math.round((overdue / total) * 100) || 0;
  const pauPct = Math.round((paused / total) * 100) || 0;

  container.innerHTML = `
    <div class="status-bar-container">
      <div class="status-bar-row">
        <span class="status-bar-label"><span style="color:#f59e0b;">⏳</span> Pending</span>
        <div class="status-bar-track">
          <div class="progress-bar-fill" style="width: ${pPct}%; background: #f59e0b;"></div>
        </div>
        <span class="status-bar-val">${pending} (${pPct}%)</span>
      </div>
      <div class="status-bar-row">
        <span class="status-bar-label"><span style="color:#6366f1;">⚡</span> In Progress</span>
        <div class="status-bar-track">
          <div class="progress-bar-fill" style="width: ${ipPct}%; background: #6366f1;"></div>
        </div>
        <span class="status-bar-val">${inProgress} (${ipPct}%)</span>
      </div>
      <div class="status-bar-row">
        <span class="status-bar-label"><span style="color:#10b981;">✅</span> Completed</span>
        <div class="status-bar-track">
          <div class="progress-bar-fill" style="width: ${cPct}%; background: #10b981;"></div>
        </div>
        <span class="status-bar-val">${completed} (${cPct}%)</span>
      </div>
      <div class="status-bar-row">
        <span class="status-bar-label"><span style="color:#ef4444;">🚨</span> Overdue</span>
        <div class="status-bar-track">
          <div class="progress-bar-fill" style="width: ${oPct}%; background: #ef4444;"></div>
        </div>
        <span class="status-bar-val" style="color: ${overdue > 0 ? '#b91c1c' : 'inherit'};">${overdue} (${oPct}%)</span>
      </div>
      ${paused > 0 ? `
      <div class="status-bar-row">
        <span class="status-bar-label"><span style="color:#ea580c;">⏸️</span> Paused</span>
        <div class="status-bar-track">
          <div class="progress-bar-fill" style="width: ${pauPct}%; background: #ea580c;"></div>
        </div>
        <span class="status-bar-val">${paused} (${pauPct}%)</span>
      </div>` : ""}
    </div>
  `;
}

function renderPriorityDistribution() {
  const container = $("priority-distribution-box");
  if (!container) return;

  const total = state.tasks.length;
  if (total === 0) {
    container.innerHTML = `<div class="empty-data-state">No tasks created yet in workspace.</div>`;
    return;
  }

  const critical = state.tasks.filter(t => (t.priority || "").equalsIgnoreCase("Critical")).length;
  const high = state.tasks.filter(t => (t.priority || "").equalsIgnoreCase("High")).length;
  const medium = state.tasks.filter(t => (t.priority || "").equalsIgnoreCase("Medium") || !t.priority).length;
  const low = state.tasks.filter(t => (t.priority || "").equalsIgnoreCase("Low")).length;

  const critPct = Math.round((critical / total) * 100) || 0;
  const highPct = Math.round((high / total) * 100) || 0;
  const medPct = Math.round((medium / total) * 100) || 0;
  const lowPct = Math.round((low / total) * 100) || 0;

  container.innerHTML = `
    <div class="status-bar-container">
      <div class="status-bar-row">
        <span class="status-bar-label"><span class="priority-tag priority-critical" style="padding:1px 6px; font-size:10px;">Critical</span></span>
        <div class="status-bar-track">
          <div class="progress-bar-fill" style="width: ${critPct}%; background: #ef4444;"></div>
        </div>
        <span class="status-bar-val">${critical} (${critPct}%)</span>
      </div>
      <div class="status-bar-row">
        <span class="status-bar-label"><span class="priority-tag priority-high" style="padding:1px 6px; font-size:10px;">High</span></span>
        <div class="status-bar-track">
          <div class="progress-bar-fill" style="width: ${highPct}%; background: #f97316;"></div>
        </div>
        <span class="status-bar-val">${high} (${highPct}%)</span>
      </div>
      <div class="status-bar-row">
        <span class="status-bar-label"><span class="priority-tag priority-medium" style="padding:1px 6px; font-size:10px;">Medium</span></span>
        <div class="status-bar-track">
          <div class="progress-bar-fill" style="width: ${medPct}%; background: #3b82f6;"></div>
        </div>
        <span class="status-bar-val">${medium} (${medPct}%)</span>
      </div>
      <div class="status-bar-row">
        <span class="status-bar-label"><span class="priority-tag priority-low" style="padding:1px 6px; font-size:10px;">Low</span></span>
        <div class="status-bar-track">
          <div class="progress-bar-fill" style="width: ${lowPct}%; background: #64748b;"></div>
        </div>
        <span class="status-bar-val">${low} (${lowPct}%)</span>
      </div>
    </div>
  `;
}

function renderDashboardEmployeeWorkload() {
  const tbody = $("dashboard-employee-workload-tbody");
  if (!tbody) return;

  if (state.employees.length === 0) {
    tbody.innerHTML = `<tr><td colspan="6" class="empty-data-state">No registered employees in system.</td></tr>`;
    return;
  }

  const displayEmps = state.employees.slice(0, 8);
  tbody.innerHTML = displayEmps.map(emp => {
    const load = emp.workloadHours || 0;
    const max = emp.maxWorkload || 40;
    const pct = Math.min(100, Math.round((load / max) * 100));
    const activeTasks = emp.activeTaskCount !== undefined ? emp.activeTaskCount : state.tasks.filter(t => t.assignedEmployeeId === emp.id && t.status !== "Completed").length;

    const skillsHtml = (emp.skills || []).map(s => {
      const prof = (emp.skillProficiencies && emp.skillProficiencies[s]) || "Intermediate";
      return `<span class="skill-prof-group"><span class="skill-name">${escapeHTML(s)}</span><span class="prof-tag ${prof.toLowerCase()}">${prof}</span></span>`;
    }).join("");

    return `
      <tr>
        <td>
          <div style="display:flex; align-items:center; gap:8px;">
            <div class="user-avatar" style="width:26px; height:26px; font-size:11px;">${escapeHTML(emp.name.charAt(0))}</div>
            <div>
              <strong>${escapeHTML(emp.name)}</strong>
              <span style="font-size:11px; color:var(--text-muted);"> (#${emp.id})</span>
            </div>
          </div>
        </td>
        <td><span style="font-size:12px; color:#475569;">${escapeHTML(emp.role || "Developer")}</span></td>
        <td><div style="display:flex; flex-wrap:wrap; gap:4px;">${skillsHtml || '<span style="color:var(--text-muted); font-size:11px;">None</span>'}</div></td>
        <td>
          <span class="badge ${emp.available ? "available" : "unavailable"}" style="font-size:11px;">
            <span class="badge-dot"></span>${emp.available ? "Available" : "Unavailable"}
          </span>
        </td>
        <td><strong>${activeTasks}</strong></td>
        <td>
          <div style="display:flex; align-items:center; gap:8px;">
            <div class="chart-bar-track" style="flex:1; height:6px; width:100px;">
              <div class="chart-bar-fill ${pct > 80 ? "danger" : (pct > 50 ? "warning" : "primary")}" style="width: ${pct}%;"></div>
            </div>
            <span style="font-size:11px; font-weight:600; white-space:nowrap;">${load}h / ${max}h (${pct}%)</span>
          </div>
        </td>
      </tr>
    `;
  }).join("");
}

function renderPreemptiveDashboard() {
  const executingTasks = state.tasks.filter(t => t.status === "In Progress");
  const pausedTasks = state.tasks.filter(t => t.status === "Paused");

  // Preemption Alert Banner
  const banner = $("preemption-alert-banner");
  if (banner) {
    if (pausedTasks.length > 0) {
      const topPaused = pausedTasks[0];
      const msg = topPaused.preemptionMessage || "High-priority task assigned. Medium-priority task paused and will resume after completion.";
      const msgEl = $("preemption-banner-message");
      if (msgEl) msgEl.textContent = msg;
      banner.style.display = "flex";
    } else {
      banner.style.display = "none";
    }

    const closeBtn = $("preemption-banner-close-btn");
    if (closeBtn) {
      closeBtn.onclick = () => { banner.style.display = "none"; };
    }
  }

  // Count Badges
  const execCountBadge = $("count-executing-tasks");
  if (execCountBadge) execCountBadge.textContent = executingTasks.length;

  const pauseCountBadge = $("count-paused-tasks");
  if (pauseCountBadge) pauseCountBadge.textContent = pausedTasks.length;

  // Executing Tasks List
  const execList = $("dashboard-executing-tasks-list");
  if (execList) {
    if (executingTasks.length === 0) {
      execList.innerHTML = `<div class="empty-data-state" style="padding:16px;">No tasks currently executing. Newly assigned tasks will appear here.</div>`;
    } else {
      execList.innerHTML = executingTasks.map(t => {
        const prio = (t.priority || "Medium").toLowerCase();
        const progress = t.progressPercentage || 0;
        const remaining = (t.remainingDurationHours !== null && t.remainingDurationHours !== undefined) ? t.remainingDurationHours : t.durationHours;
        return `
          <div class="executing-task-card">
            <div class="card-preempt-header">
              <span class="card-preempt-title">${escapeHTML(t.title)}</span>
              <span class="priority-tag priority-${prio}">${escapeHTML(t.priority || "Medium")}</span>
            </div>
            <div class="card-preempt-meta">
              <span>👤 <strong>${escapeHTML(t.assignedEmployeeName || "Assigned")}</strong></span>
              <span>·</span>
              <span>⏱️ ${remaining}h remaining (${t.durationHours}h total)</span>
              ${t.deadline ? `<span>·</span><span>📅 Deadline: ${escapeHTML(t.deadline)}</span>` : ""}
            </div>
            <div style="display:flex; justify-content:space-between; font-size:11px; color:var(--text-muted); margin-bottom:4px;">
              <span>Execution Progress</span>
              <span><strong>${progress}%</strong></span>
            </div>
            <div class="progress-container">
              <div class="progress-bar-fill executing" style="width: ${progress}%;"></div>
            </div>
          </div>
        `;
      }).join("");
    }
  }

  // Paused Tasks List
  const pausedList = $("dashboard-paused-tasks-list");
  if (pausedList) {
    if (pausedTasks.length === 0) {
      pausedList.innerHTML = `<div class="empty-data-state" style="padding:16px;">✅ No paused tasks. All assigned workloads are running without interruption.</div>`;
    } else {
      pausedList.innerHTML = pausedTasks.map(t => {
        const prio = (t.priority || "Medium").toLowerCase();
        const progress = t.progressPercentage || 0;
        const remaining = (t.remainingDurationHours !== null && t.remainingDurationHours !== undefined) ? t.remainingDurationHours : t.durationHours;
        return `
          <div class="paused-task-card">
            <div class="card-preempt-header">
              <span class="card-preempt-title">${escapeHTML(t.title)}</span>
              <span class="priority-tag priority-${prio}">${escapeHTML(t.priority || "Medium")}</span>
            </div>
            <div class="card-preempt-meta">
              <span>👤 <strong>${escapeHTML(t.assignedEmployeeName || "Assigned")}</strong></span>
              <span>·</span>
              <span>⏱️ ${remaining}h remaining</span>
              ${t.deadline ? `<span>·</span><span>📅 Deadline: ${escapeHTML(t.deadline)}</span>` : ""}
            </div>
            <div style="display:flex; justify-content:space-between; font-size:11px; color:var(--text-muted); margin-bottom:4px;">
              <span>Preserved Progress</span>
              <span><strong>${progress}% saved</strong></span>
            </div>
            <div class="progress-container">
              <div class="progress-bar-fill" style="width: ${progress}%;"></div>
            </div>
            <div class="preemption-reason-box">
              ⚡ <strong>Preempted by:</strong> ${escapeHTML(t.preemptedByTaskTitle || "Higher-Priority Task")}
              <div style="margin-top:2px;">${escapeHTML(t.preemptionMessage || "Will automatically resume once higher-priority task completes.")}</div>
            </div>
          </div>
        `;
      }).join("");
    }
  }

  // Preemption History Log Table
  const historyTbody = $("dashboard-preemption-history-tbody");
  if (historyTbody) {
    const records = state.preemptions || [];
    if (records.length === 0) {
      historyTbody.innerHTML = `<tr><td colspan="7" class="empty-data-state" style="padding:20px;">No preemption events recorded yet. Adding a higher-priority task while an employee is busy will trigger preemption.</td></tr>`;
    } else {
      historyTbody.innerHTML = records.map(p => {
        const prioHigher = (p.preemptingTaskPriority || "High").toLowerCase();
        const prioLower = (p.preemptedTaskPriority || "Medium").toLowerCase();
        const statusClass = (p.status || "PAUSED").toLowerCase();
        return `
          <tr>
            <td>${formatDate(p.preemptedAt)}</td>
            <td>
              <strong>${escapeHTML(p.preemptingTaskTitle)}</strong>
              <div><span class="priority-tag priority-${prioHigher}">${escapeHTML(p.preemptingTaskPriority)}</span></div>
            </td>
            <td>
              <strong>${escapeHTML(p.preemptedTaskTitle)}</strong>
              <div><span class="priority-tag priority-${prioLower}">${escapeHTML(p.preemptedTaskPriority)}</span></div>
            </td>
            <td><strong>${escapeHTML(p.employeeName)}</strong></td>
            <td><span class="preemption-status-tag ${statusClass}">${escapeHTML(p.status)}</span></td>
            <td><strong>${p.savedProgressPercentage}%</strong> (${p.savedRemainingHours}h remaining)</td>
            <td style="max-width:280px; font-size:12px;">${escapeHTML(p.message)}</td>
          </tr>
        `;
      }).join("");
    }
  }
}

function renderWorkloadBarChart() {
  const container = $("workload-chart-box");
  if (!container) return;

  if (state.employees.length === 0) {
    container.innerHTML = `<div class="empty-data-state">No registered employees to display.</div>`;
    return;
  }

  const items = state.employees.slice(0, 7).map(emp => {
    const load = emp.workloadHours || 0;
    const max = emp.maxWorkload || 40;
    const pct = Math.min(100, Math.round((load / max) * 100));

    let fillClass = "primary";
    if (pct > 80) fillClass = "danger";
    else if (pct > 50) fillClass = "warning";
    else if (pct > 0) fillClass = "success";

    return `
      <div class="chart-bar-item">
        <div class="chart-bar-label-row">
          <span>${escapeHTML(emp.name)} <small style="color:var(--text-muted);">(${emp.role})</small></span>
          <span><strong>${load}h</strong> / ${max}h (${pct}%)</span>
        </div>
        <div class="chart-bar-track">
          <div class="chart-bar-fill ${fillClass}" style="width: ${pct}%;"></div>
        </div>
      </div>
    `;
  }).join("");

  container.innerHTML = `<div class="custom-chart-bar-list">${items}</div>`;
}

function renderTaskStatusChart(pending, assigned, completed) {
  const container = $("task-status-chart-box");
  if (!container) return;

  const total = pending + assigned + completed;
  if (total === 0) {
    container.innerHTML = `<div class="empty-data-state">No tasks created yet.</div>`;
    return;
  }

  const pPct = Math.round((pending / total) * 100) || 0;
  const aPct = Math.round((assigned / total) * 100) || 0;
  const cPct = Math.round((completed / total) * 100) || 0;

  // SVG Donut Chart
  const radius = 50;
  const circ = 2 * Math.PI * radius;
  const strokePending = (pPct / 100) * circ;
  const strokeAssigned = (aPct / 100) * circ;
  const strokeCompleted = (cPct / 100) * circ;

  container.innerHTML = `
    <div class="donut-chart-wrap">
      <svg width="140" height="140" viewBox="0 0 140 140" style="transform: rotate(-90deg);">
        <circle cx="70" cy="70" r="${radius}" fill="none" stroke="#e2e8f0" stroke-width="16" />
        <circle cx="70" cy="70" r="${radius}" fill="none" stroke="#10b981" stroke-width="16"
          stroke-dasharray="${strokeCompleted} ${circ}" stroke-dashoffset="0" />
        <circle cx="70" cy="70" r="${radius}" fill="none" stroke="#3b82f6" stroke-width="16"
          stroke-dasharray="${strokeAssigned} ${circ}" stroke-dashoffset="-${strokeCompleted}" />
        <circle cx="70" cy="70" r="${radius}" fill="none" stroke="#f59e0b" stroke-width="16"
          stroke-dasharray="${strokePending} ${circ}" stroke-dashoffset="-${strokeCompleted + strokeAssigned}" />
      </svg>
      <div class="donut-legend">
        <div class="legend-item"><span class="legend-color" style="background:#10b981;"></span><span>Completed (${completed} · ${cPct}%)</span></div>
        <div class="legend-item"><span class="legend-color" style="background:#3b82f6;"></span><span>Assigned (${assigned} · ${aPct}%)</span></div>
        <div class="legend-item"><span class="legend-color" style="background:#f59e0b;"></span><span>Pending (${pending} · ${pPct}%)</span></div>
      </div>
    </div>
  `;
}

function renderEmployeesPage() {
  const grid = $("employees-grid-container");
  const search = $("employee-search-input").value.toLowerCase().trim();
  const skillFilter = $("employee-skill-filter").value;
  const availFilter = $("employee-avail-filter").value;

  // Populate skill filter dropdown
  const allSkills = new Set();
  state.employees.forEach(e => (e.skills || []).forEach(s => allSkills.add(s)));
  const skillSelect = $("employee-skill-filter");
  const curSkill = skillSelect.value;
  skillSelect.innerHTML = `<option value="all">All Skills</option>` +
    Array.from(allSkills).sort().map(s => `<option value="${escapeHTML(s)}">${escapeHTML(s)}</option>`).join("");
  skillSelect.value = curSkill;

  let filtered = state.employees.filter(emp => {
    if (availFilter !== "all" && String(emp.available) !== availFilter) return false;
    if (skillFilter !== "all" && !(emp.skills || []).some(s => s.equalsIgnoreCase(skillFilter))) return false;
    if (search) {
      const match = String(emp.id).includes(search) ||
                    (emp.name && emp.name.toLowerCase().includes(search)) ||
                    (emp.role && emp.role.toLowerCase().includes(search)) ||
                    (emp.email && emp.email.toLowerCase().includes(search)) ||
                    (emp.username && emp.username.toLowerCase().includes(search)) ||
                    (emp.skills || []).some(s => s.toLowerCase().includes(search));
      if (!match) return false;
    }
    return true;
  });

  if (filtered.length === 0) {
    grid.innerHTML = `<div class="empty-data-state" style="grid-column: 1/-1;">No employees matching criteria found. Click "+ Add Employee" or "Bulk Import" to register personnel.</div>`;
    return;
  }

  grid.innerHTML = filtered.map(emp => {
    const load = emp.workloadHours || 0;
    const max = emp.maxWorkload || 40;
    const pct = Math.min(100, Math.round((load / max) * 100));
    const loginUser = emp.username || emp.email || "Not assigned";
    const activeTasks = emp.activeTaskCount !== undefined ? emp.activeTaskCount : state.tasks.filter(t => t.assignedEmployeeId === emp.id && t.status !== "Completed").length;

    const skillsHtml = (emp.skills || []).map(s => {
      const prof = (emp.skillProficiencies && emp.skillProficiencies[s]) || "Intermediate";
      return `<span class="skill-prof-group"><span class="skill-name">${escapeHTML(s)}</span><span class="prof-tag ${prof.toLowerCase()}">${prof}</span></span>`;
    }).join("");

    return `
      <div class="employee-card">
        <div class="emp-card-top">
          <div class="emp-profile-left">
            <div class="emp-avatar-lg">${escapeHTML(emp.name.charAt(0).toUpperCase())}</div>
            <div class="emp-card-details">
              <h4>${escapeHTML(emp.name)} <span style="font-size:11px; color:var(--text-muted); font-weight:500;">#${emp.id}</span></h4>
              <span>${escapeHTML(emp.role)}</span>
            </div>
          </div>
          <span class="badge ${emp.available ? "available" : "unavailable"}">
            <span class="badge-dot"></span>${emp.available ? "Available" : "Unavailable"}
          </span>
        </div>

        <div style="font-size: 11px; color: var(--text-muted); margin-bottom: 8px; line-height: 1.5;">
          <div>📧 Email: ${escapeHTML(emp.email || "No email")}</div>
          <div>🔑 Username: <strong>${escapeHTML(loginUser)}</strong></div>
        </div>

        <div class="skill-chips" style="margin-bottom: 12px; display:flex; flex-wrap:wrap; gap:4px;">
          ${skillsHtml || '<span style="color:var(--text-muted); font-size:11px;">No skills registered</span>'}
        </div>

        <div class="emp-workload-box">
          <div class="emp-workload-header">
            <span>Workload Capacity</span>
            <strong>${activeTasks} active tasks · ${load}h / ${max}h (${pct}%)</strong>
          </div>
          <div class="chart-bar-track">
            <div class="chart-bar-fill ${pct > 80 ? "danger" : (pct > 50 ? "warning" : "primary")}" style="width: ${pct}%;"></div>
          </div>
        </div>

        <div class="emp-card-footer">
          <button class="btn btn-sm btn-secondary" onclick="window.toggleEmployeeAvailability(${emp.id}, ${!emp.available})">
            ${emp.available ? "Mark Unavailable" : "Mark Available"}
          </button>
          <div style="display: flex; gap: 6px;">
            <button class="btn-icon" title="Edit Employee" onclick="window.openEditEmployeeModal(${emp.id})">✏️</button>
            <button class="btn-icon" title="Delete Employee" onclick="window.confirmDeleteEmployee(${emp.id}, '${escapeHTML(emp.name)}')">🗑️</button>
          </div>
        </div>
      </div>
    `;
  }).join("");
}

function renderTasksPage() {
  const tbody = $("tasks-table-body");
  const search = $("task-search-input").value.toLowerCase().trim();
  const statusFilter = $("task-status-filter").value;
  const priorityFilter = $("task-priority-filter").value;
  const skillFilter = $("task-skill-filter").value;

  // Populate skills filter
  const allSkills = new Set();
  state.tasks.forEach(t => t.requiredSkill && allSkills.add(t.requiredSkill));
  const skillSelect = $("task-skill-filter");
  const curSkill = skillSelect.value;
  skillSelect.innerHTML = `<option value="all">All Skills</option>` +
    Array.from(allSkills).sort().map(s => `<option value="${escapeHTML(s)}">${escapeHTML(s)}</option>`).join("");
  skillSelect.value = curSkill;

  let filtered = state.tasks.filter(task => {
    if (statusFilter !== "all") {
      if (statusFilter.equalsIgnoreCase("Overdue")) {
        if (task.status === "Completed" || calculateTaskDeadlineRisk(task) !== "Overdue") return false;
      } else if (!task.status.equalsIgnoreCase(statusFilter)) {
        return false;
      }
    }
    if (priorityFilter !== "all" && !task.priority.equalsIgnoreCase(priorityFilter)) return false;
    if (skillFilter !== "all" && !task.requiredSkill.equalsIgnoreCase(skillFilter)) return false;
    if (search) {
      const match = (task.title && task.title.toLowerCase().includes(search)) ||
                    (task.description && task.description.toLowerCase().includes(search)) ||
                    (task.requiredSkill && task.requiredSkill.toLowerCase().includes(search)) ||
                    (task.assignedEmployeeName && task.assignedEmployeeName.toLowerCase().includes(search));
      if (!match) return false;
    }
    return true;
  });

  if (filtered.length === 0) {
    tbody.innerHTML = `<tr><td colspan="8" class="empty-data-state">No tasks matching search filters. Create a task or click "Run Automatic Assignment".</td></tr>`;
    return;
  }

  tbody.innerHTML = filtered.map(t => {
    let statusClass = "pending";
    if (t.status === "Assigned") statusClass = "assigned";
    if (t.status === "In Progress") statusClass = "inprogress";
    if (t.status === "Paused") statusClass = "paused";
    if (t.status === "Completed") statusClass = "completed";
    if (t.status === "Unassigned") statusClass = "unavailable";

    let prioClass = `priority-${(t.priority || "medium").toLowerCase()}`;

    return `
      <tr>
        <td><strong>#${t.id}</strong></td>
        <td>
          <strong>${escapeHTML(t.title)}</strong>
          ${t.description ? `<div style="font-size:11px; color:var(--text-muted);">${escapeHTML(t.description)}</div>` : ""}
          <div style="margin-top:3px;"><span class="skill-chip">${escapeHTML(t.requiredSkill)}</span></div>
        </td>
        <td>
          ${t.assignedEmployeeName ? `<strong>${escapeHTML(t.assignedEmployeeName)}</strong>` : `<span style="color:var(--danger); font-weight:600;">Unassigned</span>${getUnassignedReasonSnippet(t.id)}`}
        </td>
        <td><span class="priority-tag ${prioClass}">${escapeHTML(t.priority)}</span></td>
        <td>
          <div>${escapeHTML(t.deadline || "Not set")}</div>
          <div style="margin-top:4px;">${renderDeadlineRiskBadge(t)}</div>
        </td>
        <td><strong>${t.durationHours} hrs</strong></td>
        <td>
          <span class="badge ${statusClass}"><span class="badge-dot"></span>${escapeHTML(t.status)}</span>
          ${t.status === "Paused" ? `<div style="font-size:10px; color:#c2410c; margin-top:2px;">${t.progressPercentage || 0}% saved (${t.remainingDurationHours !== null ? t.remainingDurationHours : t.durationHours}h left)</div>` : ""}
        </td>
        <td>
          <div style="display:flex; gap:6px;">
            <button class="btn btn-sm btn-secondary" title="Reassign Task" onclick="window.openReassignModalForTask(${t.id})">⇄</button>
            <button class="btn-icon" title="Edit Task" onclick="window.openEditTaskModal(${t.id})">✏️</button>
            <button class="btn-icon" title="Delete Task" onclick="window.confirmDeleteTask(${t.id}, '${escapeHTML(t.title)}')">🗑️</button>
          </div>
        </td>
      </tr>
    `;
  }).join("");
}

function renderWorkforceMonitoring() {
  const tbody = $("workforce-roster-body");
  const available = state.employees.filter(e => e.available).length;
  const busy = state.employees.filter(e => e.available && e.workloadHours > 0).length;
  const atCapacity = state.employees.filter(e => e.available && e.workloadHours >= e.maxWorkload).length;
  const unavailable = state.employees.filter(e => !e.available).length;

  $("wf-stat-available").textContent = available;
  $("wf-stat-busy").textContent = busy;
  $("wf-stat-at-capacity").textContent = atCapacity;
  $("wf-stat-unavailable").textContent = unavailable;

  if (state.employees.length === 0) {
    tbody.innerHTML = `<tr><td colspan="8" class="empty-data-state">No workforce personnel registered in system.</td></tr>`;
    return;
  }

  tbody.innerHTML = state.employees.map(emp => {
    const load = emp.workloadHours || 0;
    const max = emp.maxWorkload || 40;
    const pct = Math.min(100, Math.round((load / max) * 100));
    const remaining = Math.max(0, max - load);

    return `
      <tr>
        <td>
          <div style="display:flex; align-items:center; gap:10px;">
            <div class="user-avatar" style="width:28px; height:28px; font-size:11px;">${escapeHTML(emp.name.charAt(0))}</div>
            <div>
              <strong>${escapeHTML(emp.name)}</strong>
              <div style="font-size:11px; color:var(--text-muted);">${escapeHTML(emp.email || "")}</div>
            </div>
          </div>
        </td>
        <td>${escapeHTML(emp.role)}</td>
        <td><div class="skill-chips">${(emp.skills || []).map(s => `<span class="skill-chip">${escapeHTML(s)}</span>`).join("")}</div></td>
        <td>
          <span class="badge ${emp.available ? "available" : "unavailable"}">
            <span class="badge-dot"></span>${emp.available ? "Available" : "Unavailable"}
          </span>
        </td>
        <td>
          <div style="font-weight:600;">${load}h / ${max}h</div>
          <div class="chart-bar-track" style="width:120px; height:6px; margin-top:4px;">
            <div class="chart-bar-fill ${pct > 80 ? "danger" : (pct > 50 ? "warning" : "success")}" style="width: ${pct}%;"></div>
          </div>
        </td>
        <td><strong>${pct}%</strong></td>
        <td><span style="color:${remaining > 0 ? "var(--success)" : "var(--danger)"}; font-weight:700;">${remaining} hrs</span></td>
        <td>
          <button class="btn btn-sm btn-secondary" onclick="window.toggleEmployeeAvailability(${emp.id}, ${!emp.available})">
            ${emp.available ? "Set Offline" : "Set Active"}
          </button>
        </td>
      </tr>
    `;
  }).join("");
}

function renderManagerEmergencies() {
  const tbody = $("emergencies-table-body");
  if (state.emergencies.length === 0) {
    tbody.innerHTML = `<tr><td colspan="9" class="empty-data-state">No emergency reports submitted. Workforce is operating normally.</td></tr>`;
    return;
  }

  tbody.innerHTML = state.emergencies.map(em => {
    let statusBadge = `<span class="badge pending">OPEN</span>`;
    if (em.status === "ACKNOWLEDGED") statusBadge = `<span class="badge inprogress">ACKNOWLEDGED</span>`;
    if (em.status === "RESOLVED") statusBadge = `<span class="badge completed">RESOLVED</span>`;

    return `
      <tr>
        <td><strong>#${em.id}</strong></td>
        <td><strong>${escapeHTML(em.employeeName || "Employee #" + em.employeeId)}</strong></td>
        <td><span class="badge unavailable">${escapeHTML(em.type)}</span></td>
        <td>${em.taskTitle ? `<strong>${escapeHTML(em.taskTitle)}</strong>` : "General Issue"}</td>
        <td style="max-width:280px; white-space:normal; font-size:12px;">${escapeHTML(em.description)}</td>
        <td>${escapeHTML(em.expectedUnavailability || "Indefinite")}</td>
        <td>${formatDate(em.createdAt)}</td>
        <td>${statusBadge}</td>
        <td>
          <div style="display:flex; gap:6px;">
            ${em.status === "OPEN" ? `<button class="btn btn-sm btn-secondary" onclick="window.acknowledgeEmergency(${em.id})">Acknowledge</button>` : ""}
            ${em.status !== "RESOLVED" ? `<button class="btn btn-sm btn-primary" onclick="window.openResolveEmergencyModal(${em.id}, '${escapeHTML(em.employeeName)}')">Resolve</button>` : ""}
            ${em.taskId ? `<button class="btn btn-sm btn-secondary" title="Reassign Affected Task" onclick="window.openReassignModalForTask(${em.taskId})">⇄ Reassign</button>` : ""}
          </div>
        </td>
      </tr>
    `;
  }).join("");
}

function renderReportsAndHistory() {
  const total = state.assignmentsHistory.length;
  const success = state.assignmentsHistory.filter(a => a.outcome === "SUCCESS").length;
  const rate = total > 0 ? Math.round((success / total) * 100) : 0;
  const reassignments = state.assignmentsHistory.filter(a => a.reassigned).length;

  const totalLoad = state.employees.reduce((acc, e) => acc + (e.workloadHours || 0), 0);
  const totalCap = state.employees.reduce((acc, e) => acc + (e.maxWorkload || 40), 0);
  const avgUtil = totalCap > 0 ? Math.round((totalLoad / totalCap) * 100) : 0;

  $("report-total-assignments").textContent = total;
  $("report-success-rate").textContent = `${rate}%`;
  $("report-reassignment-count").textContent = reassignments;
  $("report-avg-utilization").textContent = `${avgUtil}%`;

  const tbody = $("history-table-body");
  if (total === 0) {
    tbody.innerHTML = `<tr><td colspan="7" class="empty-data-state">No assignment history records found.</td></tr>`;
    return;
  }

  tbody.innerHTML = state.assignmentsHistory.map(a => `
    <tr>
      <td>${formatDate(a.assignmentTime)}</td>
      <td><strong>${escapeHTML(a.taskTitle || "Task #" + a.taskId)}</strong></td>
      <td>${a.employeeName ? escapeHTML(a.employeeName) : `<span style="color:var(--text-muted);">None</span>`}</td>
      <td>${a.workloadBefore !== null ? `<strong>${a.workloadBefore}h</strong> &rarr; <strong>${a.workloadAfter}h</strong>` : "-"}</td>
      <td><span class="skill-chip">${escapeHTML(a.algorithm || "Greedy Minimum Workload")}</span></td>
      <td>
        <span class="badge ${a.outcome === "SUCCESS" ? "completed" : "pending"}">
          <span class="badge-dot"></span>${escapeHTML(a.outcome)}
        </span>
      </td>
      <td style="max-width:320px; white-space:normal; font-size:12px;">${escapeHTML(a.reason || "")}</td>
    </tr>
  `).join("");
}

// =========================================================================
// EMPLOYEE PORTAL RENDERERS
// =========================================================================

function renderEmployeeDashboard() {
  if (!state.currentUser || !state.currentUser.employeeId) return;

  const myId = state.currentUser.employeeId;
  const me = state.employees.find(e => e.id === myId) || { workloadHours: 0, maxWorkload: 40, available: true, role: "Software Developer" };

  const myTasks = state.tasks.filter(t => t.assignedEmployeeId === myId);
  const activeTasks = myTasks.filter(t => t.status !== "Completed");
  const pendingTasks = myTasks.filter(t => t.status === "Assigned" || t.status === "Pending");
  const inProgressTasks = myTasks.filter(t => t.status === "In Progress");
  const completedTasks = myTasks.filter(t => t.status === "Completed");

  const load = me.workloadHours || 0;
  const max = me.maxWorkload || 40;
  const pct = Math.min(100, Math.round((load / max) * 100));

  if ($("emp-stat-total-tasks")) $("emp-stat-total-tasks").textContent = myTasks.length;
  if ($("emp-stat-pending-tasks")) $("emp-stat-pending-tasks").textContent = pendingTasks.length;
  if ($("emp-stat-inprogress-tasks")) $("emp-stat-inprogress-tasks").textContent = inProgressTasks.length;
  if ($("emp-stat-active-tasks")) $("emp-stat-active-tasks").textContent = activeTasks.length;
  if ($("emp-stat-completed-tasks")) $("emp-stat-completed-tasks").textContent = completedTasks.length;
  if ($("emp-stat-workload")) $("emp-stat-workload").textContent = `${load}h`;
  if ($("emp-stat-capacity-subtext")) $("emp-stat-capacity-subtext").textContent = `Cap: ${max}h (${pct}% used)`;
  if ($("emp-stat-avail-text")) {
    $("emp-stat-avail-text").textContent = me.available ? "Available" : "Unavailable";
    $("emp-stat-avail-text").style.color = me.available ? "var(--success)" : "var(--danger)";
  }
  if ($("emp-stat-role-text")) $("emp-stat-role-text").textContent = me.role || "Software Developer";

  // Greeting
  const greetingEl = $("emp-dash-greeting-name");
  if (greetingEl) greetingEl.textContent = me.name || "Team Member";

  // Quick Action Required tasks
  const quickTbody = $("emp-quick-tasks-body");
  if (activeTasks.length === 0) {
    quickTbody.innerHTML = `<tr><td colspan="5" class="empty-data-state">🎉 You have no pending tasks! Great job.</td></tr>`;
  } else {
    quickTbody.innerHTML = activeTasks.slice(0, 4).map(t => `
      <tr>
        <td><strong>${escapeHTML(t.title)}</strong></td>
        <td><span class="priority-tag priority-${(t.priority || "medium").toLowerCase()}">${escapeHTML(t.priority)}</span></td>
        <td>${t.durationHours} hrs</td>
        <td>
          <div>${escapeHTML(t.deadline || "None")}</div>
          <div style="margin-top:2px;">${renderDeadlineRiskBadge(t)}</div>
        </td>
        <td>
          <div style="display:flex; gap:6px;">
            ${t.status === "Assigned" ? `<button class="btn btn-sm btn-primary" onclick="window.startTask(${t.id})">Start</button>` : ""}
            <button class="btn btn-sm btn-success" onclick="window.openCompleteTaskModal(${t.id}, '${escapeHTML(t.title)}')">Complete</button>
          </div>
        </td>
      </tr>
    `).join("");
  }

  // Quick Emergencies
  const myEmergencies = state.emergencies.filter(e => e.employeeId === myId);
  const quickEmergTbody = $("emp-quick-emergencies-body");
  if (myEmergencies.length === 0) {
    quickEmergTbody.innerHTML = `<tr><td colspan="4" class="empty-data-state">No emergency incidents reported.</td></tr>`;
  } else {
    quickEmergTbody.innerHTML = myEmergencies.slice(0, 4).map(e => `
      <tr>
        <td><strong>${escapeHTML(e.type)}</strong></td>
        <td><span class="badge ${e.status === "RESOLVED" ? "completed" : "pending"}">${escapeHTML(e.status)}</span></td>
        <td style="max-width:200px; font-size:12px;">${escapeHTML(e.managerResponse || "Pending manager review")}</td>
        <td>${formatDate(e.createdAt)}</td>
      </tr>
    `).join("");
  }

  // Render Paused Tasks
  renderEmployeePausedTasks();
}

function renderEmployeePausedTasks() {
  if (!state.currentUser || !state.currentUser.employeeId) return;
  const myId = state.currentUser.employeeId;
  const myPausedTasks = state.tasks.filter(t => t.assignedEmployeeId === myId && t.status === "Paused");

  const panel = $("emp-paused-tasks-panel");
  const tbody = $("emp-paused-tasks-tbody");
  if (!panel || !tbody) return;

  if (myPausedTasks.length === 0) {
    panel.style.display = "none";
    tbody.innerHTML = "";
    return;
  }

  panel.style.display = "block";
  tbody.innerHTML = myPausedTasks.map(t => {
    const prio = (t.priority || "Medium").toLowerCase();
    const progress = t.progressPercentage || 0;
    const remaining = (t.remainingDurationHours !== null && t.remainingDurationHours !== undefined) ? t.remainingDurationHours : t.durationHours;
    return `
      <tr>
        <td>
          <strong>${escapeHTML(t.title)}</strong>
          ${t.description ? `<div style="font-size:11px; color:var(--text-muted);">${escapeHTML(t.description)}</div>` : ""}
        </td>
        <td><span class="priority-tag priority-${prio}">${escapeHTML(t.priority || "Medium")}</span></td>
        <td>
          <div style="display:flex; align-items:center; gap:8px;">
            <div class="progress-container" style="width:80px; margin:0;">
              <div class="progress-bar-fill" style="width:${progress}%;"></div>
            </div>
            <strong>${progress}%</strong>
          </div>
        </td>
        <td><strong>${remaining} hrs</strong></td>
        <td><span style="color:#9a3412; font-weight:600;">⚡ ${escapeHTML(t.preemptedByTaskTitle || "Higher-Priority Task")}</span></td>
        <td>
          <span class="badge inprogress">
            <span class="badge-dot"></span>Auto-resumes on completion
          </span>
        </td>
      </tr>
    `;
  }).join("");
}

function renderEmployeeTasks() {
  if (!state.currentUser || !state.currentUser.employeeId) return;
  const myId = state.currentUser.employeeId;
  const myTasks = state.tasks.filter(t => t.assignedEmployeeId === myId && t.status !== "Completed");

  const tbody = $("my-assigned-tasks-body");
  if (myTasks.length === 0) {
    tbody.innerHTML = `<tr><td colspan="8" class="empty-data-state">No active assigned tasks right now. You're completely caught up!</td></tr>`;
    return;
  }

  tbody.innerHTML = myTasks.map(t => {
    let prioClass = `priority-${(t.priority || "medium").toLowerCase()}`;
    let statusClass = "assigned";
    if (t.status === "In Progress") statusClass = "inprogress";
    if (t.status === "Paused") statusClass = "paused";

    return `
      <tr>
        <td><strong>#${t.id}</strong></td>
        <td>
          <strong>${escapeHTML(t.title)}</strong>
          ${t.description ? `<div style="font-size:11px; color:var(--text-muted);">${escapeHTML(t.description)}</div>` : ""}
        </td>
        <td><span class="skill-chip">${escapeHTML(t.requiredSkill)}</span></td>
        <td><span class="priority-tag ${prioClass}">${escapeHTML(t.priority)}</span></td>
        <td><strong>${t.durationHours} hrs</strong></td>
        <td>${escapeHTML(t.deadline || "Not set")}</td>
        <td>
          <span class="badge ${statusClass}"><span class="badge-dot"></span>${escapeHTML(t.status)}</span>
          ${t.status === "Paused" ? `<div style="font-size:10px; color:#c2410c; margin-top:2px;">${t.progressPercentage || 0}% saved (${t.remainingDurationHours !== null ? t.remainingDurationHours : t.durationHours}h left)</div>` : ""}
        </td>
        <td>
          <div style="display:flex; gap:6px; align-items:center;">
            ${t.status === "Assigned" ? `<button class="btn btn-sm btn-primary" onclick="window.startTask(${t.id})">Start Task</button>` : ""}
            ${t.status === "Paused" ? `<span style="font-size:11px; color:#c2410c; font-weight:600;">⏸️ Paused</span>` : ""}
            <button class="btn btn-sm btn-success" onclick="window.openCompleteTaskModal(${t.id}, '${escapeHTML(t.title)}')">Mark Completed</button>
            <button class="btn btn-sm btn-danger" onclick="window.openReportEmergencyForTask(${t.id})">Report Roadblock</button>
          </div>
        </td>
      </tr>
    `;
  }).join("");
}

function renderEmployeeHistory() {
  if (!state.currentUser || !state.currentUser.employeeId) return;
  const myId = state.currentUser.employeeId;
  const completed = state.tasks.filter(t => t.assignedEmployeeId === myId && t.status === "Completed");

  const tbody = $("my-task-history-body");
  if (completed.length === 0) {
    tbody.innerHTML = `<tr><td colspan="5" class="empty-data-state">No completed tasks yet. Tasks you complete will show up here.</td></tr>`;
    return;
  }

  tbody.innerHTML = completed.map(t => `
    <tr>
      <td><strong>${escapeHTML(t.title)}</strong></td>
      <td><span class="skill-chip">${escapeHTML(t.requiredSkill)}</span></td>
      <td><strong>${t.durationHours} hrs</strong></td>
      <td>${formatDate(t.completedAt)}</td>
      <td style="max-width:320px; font-size:12px;">${escapeHTML(t.completionNotes || "No completion notes submitted.")}</td>
    </tr>
  `).join("");
}

function renderEmployeeEmergencies() {
  if (!state.currentUser || !state.currentUser.employeeId) return;
  const myId = state.currentUser.employeeId;

  // Populate related tasks select
  const taskSelect = $("emp-emergency-task");
  const myActiveTasks = state.tasks.filter(t => t.assignedEmployeeId === myId && t.status !== "Completed");
  taskSelect.innerHTML = `<option value="">-- No specific task --</option>` +
    myActiveTasks.map(t => `<option value="${t.id}">${escapeHTML(t.title)} (${t.durationHours}h)</option>`).join("");

  // History table
  const myEmergencies = state.emergencies.filter(e => e.employeeId === myId);
  const tbody = $("emp-emergencies-history-table");

  if (myEmergencies.length === 0) {
    tbody.innerHTML = `<tr><td colspan="4" class="empty-data-state">No emergency reports submitted.</td></tr>`;
    return;
  }

  tbody.innerHTML = myEmergencies.map(e => `
    <tr>
      <td><strong>${escapeHTML(e.type)}</strong></td>
      <td>${e.taskTitle ? escapeHTML(e.taskTitle) : "General"}</td>
      <td><span class="badge ${e.status === "RESOLVED" ? "completed" : "pending"}">${escapeHTML(e.status)}</span></td>
      <td style="max-width:240px; font-size:12px;">${escapeHTML(e.managerResponse || "Pending manager action")}</td>
    </tr>
  `).join("");
}

// =========================================================================
// ACTION HANDLERS & MODAL CONTROLLERS
// =========================================================================

// Helper: Calculate Deadline Feasibility Status Badge
function getDeadlineFeasibilityBadge(task) {
  if (!task.deadline) {
    return `<div class="feasibility-tag nodeadline" style="margin-top:4px;">No Deadline</div>`;
  }
  const todayStr = new Date().toISOString().slice(0, 10);
  if (task.deadline < todayStr) {
    return `<div class="feasibility-tag expired" style="margin-top:4px;" title="Deadline expired on ${task.deadline}">✕ Expired</div>`;
  }
  const days = Math.max(1, Math.ceil((new Date(task.deadline) - new Date(todayStr)) / (1000 * 60 * 60 * 24)));
  const availHours = days * 8;
  if (task.durationHours > availHours) {
    return `<div class="feasibility-tag infeasible" style="margin-top:4px;" title="Requires ${task.durationHours}h, but only ${availHours}h work time remains before ${task.deadline}">✕ Infeasible</div>`;
  }
  return `<div class="feasibility-tag feasible" style="margin-top:4px;" title="${availHours}h available before ${task.deadline}">✓ Feasible</div>`;
}

// Helper: Get Unassigned Reason Snippet
function getUnassignedReasonSnippet(taskId) {
  let reason = "";
  if (state.lastAssignmentResults && state.lastAssignmentResults.length > 0) {
    const found = state.lastAssignmentResults.find(r => r.taskId === taskId && r.status === "Unassigned");
    if (found && found.reason) reason = found.reason;
  }
  if (!reason && state.assignmentsHistory && state.assignmentsHistory.length > 0) {
    const found = state.assignmentsHistory.find(a => a.taskId === taskId && a.outcome === "FAILED");
    if (found && found.reason) reason = found.reason;
  }
  if (reason) {
    const shortReason = reason.length > 38 ? reason.slice(0, 35) + "..." : reason;
    return `<div style="font-size:10px; color:#b91c1c; margin-top:3px;" title="${escapeHTML(reason)}">⚠️ ${escapeHTML(shortReason)}</div>`;
  }
  return "";
}

// Helper: Short human-friendly review reason for unassigned tasks
function getShortReviewReason(result) {
  if (!result) return "Requires manager review.";
  if (result.deadlineStatus === "EXPIRED") return "Deadline expired.";
  if (result.deadlineStatus === "INFEASIBLE") return "Deadline cannot be met.";
  const r = (result.reason || "").toLowerCase();
  if (r.includes("missing required skills") || r.includes("no employee with the required skill")) {
    return "Required skill unavailable.";
  }
  if (r.includes("unavailable") || r.includes("unavailability")) {
    return "No employee available.";
  }
  if (r.includes("capacity") || r.includes("workload")) {
    return "Workload capacity exceeded.";
  }
  return "No eligible candidate found.";
}

// Render Assignment Results Modal (Clean Professional White UI)
function renderAssignmentResultsModal(results) {
  const safeResults = results || [];
  const total = safeResults.length;
  const assignedList = safeResults.filter(r => r.status === "Assigned");
  const reviewList = safeResults.filter(r => r.status !== "Assigned");

  const statTotal = $("res-stat-total");
  const statAssigned = $("res-stat-assigned");
  const statUnassigned = $("res-stat-unassigned");
  if (statTotal) statTotal.textContent = total;
  if (statAssigned) statAssigned.textContent = assignedList.length;
  if (statUnassigned) statUnassigned.textContent = reviewList.length;

  const listContainer = $("assignment-results-list");
  if (!listContainer) return;

  if (total === 0) {
    listContainer.innerHTML = `
      <div class="clean-task-card" style="text-align:center; padding:32px 20px;">
        <div style="font-size:28px; margin-bottom:8px;">📋</div>
        <h4 style="margin:0 0 6px 0; color:#1e293b; font-size:15px;">No Pending Tasks Processed</h4>
        <p style="margin:0; color:#64748b; font-size:13px;">All current tasks are already Assigned, In Progress, Paused, or Completed.</p>
      </div>
    `;
    const modal = $("assignment-results-modal");
    if (modal) modal.classList.add("active");
    return;
  }

  let html = "";

  // 1. Successfully Assigned Tasks Section (Clean Green with Suitability Explanation)
  if (assignedList.length > 0) {
    html += `
      <div class="clean-section-header success">
        <div style="display:flex; align-items:center; gap:8px;">
          <span style="background:#16a34a; color:#ffffff; font-size:12px; font-weight:700; padding:2px 8px; border-radius:999px;">${assignedList.length}</span>
          <h4 style="margin:0; font-size:14px; font-weight:700; color:#166534;">Successfully Assigned Tasks</h4>
        </div>
        <span style="font-size:12px; color:#15803d; font-weight:600;">Weighted Suitability Heuristic (Skill 40% · Workload 30% · Avail 20% · Reliab 10%)</span>
      </div>
    `;

    html += assignedList.map(r => {
      const prio = (r.priority || "Medium").toLowerCase();
      const prioLabel = escapeHTML(r.priority || "Medium");
      const eligibleCount = (r.eligibleCandidates || []).length;
      const winner = (r.eligibleCandidates || []).find(c => c.selected || c.employeeId === r.employeeId) || {
        employeeId: r.employeeId,
        name: r.employeeName,
        suitabilityScore: 85.0,
        skillScore: 100.0,
        workloadScore: 80.0,
        availabilityScore: 75.0,
        reliabilityScore: 75.0,
        skillProficiency: "Advanced",
        maxWorkload: 40
      };

      const winnerScore = winner.suitabilityScore !== undefined && winner.suitabilityScore !== null ? Number(winner.suitabilityScore).toFixed(1) : "N/A";
      const winnerProf = winner.skillProficiency || "Proficient";

      let eligibleTableHtml = "";
      if (eligibleCount > 0) {
        eligibleTableHtml = `
          <table class="candidate-table" style="margin-top:8px;">
            <thead>
              <tr>
                <th style="width:22%;">Candidate</th>
                <th style="width:14%;">Proficiency</th>
                <th style="width:12%;">Skill (40%)</th>
                <th style="width:12%;">Workload (30%)</th>
                <th style="width:12%;">Avail (20%)</th>
                <th style="width:12%;">Reliab (10%)</th>
                <th style="width:16%;">Suitability Score</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              ${r.eligibleCandidates.map(c => {
                const isWinner = c.selected === true || c.employeeId === r.employeeId;
                const rowStyle = isWinner ? `style="background:#f0fdf4;"` : "";
                const statusBadge = isWinner
                  ? `<span class="badge" style="background:#dcfce7; color:#15803d; border:1px solid #86efac; font-weight:700;">✓ SELECTED</span>`
                  : `<span class="badge" style="background:#f1f5f9; color:#64748b; border:1px solid #cbd5e1; font-weight:600;">ELIGIBLE</span>`;
                const prof = c.skillProficiency || "Intermediate";

                return `
                  <tr ${rowStyle}>
                    <td><strong>${escapeHTML(c.name)}</strong> <span style="font-size:11px; color:#64748b;">(#${c.employeeId})</span></td>
                    <td><span class="prof-tag ${prof.toLowerCase()}">${prof}</span></td>
                    <td>${Number(c.skillScore || 0).toFixed(1)}</td>
                    <td>${Number(c.workloadScore || 0).toFixed(1)} <small>(${c.workloadHours}h)</small></td>
                    <td>${Number(c.availabilityScore || 0).toFixed(1)}</td>
                    <td>${Number(c.reliabilityScore || 75.0).toFixed(1)}</td>
                    <td><strong style="color:${isWinner ? '#15803d' : '#0f172a'}; font-size:13px;">${Number(c.suitabilityScore || 0).toFixed(1)}</strong></td>
                    <td>${statusBadge}</td>
                  </tr>
                `;
              }).join("")}
            </tbody>
          </table>
        `;
      }

      return `
        <div class="clean-task-card assigned">
          <div style="display:flex; justify-content:space-between; align-items:flex-start; flex-wrap:wrap; gap:10px; margin-bottom:12px;">
            <div>
              <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap; margin-bottom:4px;">
                <span style="font-size:12px; font-weight:700; color:#64748b;">#${r.taskId}</span>
                <strong style="font-size:15px; color:#0f172a;">${escapeHTML(r.taskTitle)}</strong>
                <span class="priority-tag priority-${prio}">${prioLabel}</span>
                <span class="badge assigned" style="font-size:11px;">Assigned</span>
              </div>
              <div style="font-size:12px; color:#64748b; display:flex; gap:14px; flex-wrap:wrap;">
                <span><strong>Required Skill:</strong> ${escapeHTML(r.requiredSkill || "None")}</span>
                <span><strong>Duration:</strong> ${r.durationHours}h</span>
                <span><strong>Deadline:</strong> ${escapeHTML(r.deadline || "No deadline")}</span>
              </div>
            </div>

            <!-- Winner & Suitability Score Pill -->
            <div style="display:flex; flex-direction:column; align-items:flex-end; gap:6px;">
              <div class="clean-assigned-pill">
                <span>👤 <strong>${escapeHTML(r.employeeName)}</strong></span>
                <span style="font-size:11px; font-weight:500; color:#15803d;">(${r.workloadBefore}h &rarr; ${r.workloadAfter}h)</span>
              </div>
              <div class="suitability-badge">
                <span>Suitability Score:</span>
                <span class="score-val">${winnerScore}</span>
                <span style="font-size:10px; color:#93c5fd;">/ 100</span>
              </div>
            </div>
          </div>

          <!-- Assignment Decision Explanation Rationale -->
          <div class="suitability-breakdown-card">
            <div style="font-weight:700; font-size:12px; color:#0f172a; margin-bottom:4px;">
              Assignment Decision Reasons for ${escapeHTML(r.employeeName)}:
            </div>
            <ul class="decision-checklist">
              <li>Required <strong>${escapeHTML(r.requiredSkill)}</strong> skill matched (${winnerProf} proficiency level).</li>
              <li>Employee is available and ready for assignments.</li>
              <li>Workload is within maximum capacity limit (${r.workloadBefore}h + ${r.durationHours}h = ${r.workloadAfter}h &le; ${winner.maxWorkload || 40}h).</li>
              <li>Deadline is feasible based on standard working day capacity (8h/day).</li>
              <li>Highest overall suitability score (${winnerScore} / 100) among all eligible candidates (tie-breaker: minimum current workload).</li>
            </ul>

            <!-- 4 Weighted Score Sub-components -->
            <div style="display:grid; grid-template-columns:repeat(4, 1fr); gap:8px; margin-top:10px; padding-top:10px; border-top:1px solid #e2e8f0;">
              <div style="background:#f8fafc; padding:6px 8px; border-radius:6px; text-align:center;">
                <div style="font-size:10px; color:#64748b; font-weight:600;">Skill Score (40%)</div>
                <div style="font-size:13px; font-weight:700; color:#1d4ed8;">${Number(winner.skillScore || 0).toFixed(1)}</div>
                <div style="font-size:10px; color:#64748b;">${winnerProf} level</div>
              </div>
              <div style="background:#f8fafc; padding:6px 8px; border-radius:6px; text-align:center;">
                <div style="font-size:10px; color:#64748b; font-weight:600;">Workload Score (30%)</div>
                <div style="font-size:13px; font-weight:700; color:#0f172a;">${Number(winner.workloadScore || 0).toFixed(1)}</div>
                <div style="font-size:10px; color:#64748b;">${r.workloadBefore}h active load</div>
              </div>
              <div style="background:#f8fafc; padding:6px 8px; border-radius:6px; text-align:center;">
                <div style="font-size:10px; color:#64748b; font-weight:600;">Availability Score (20%)</div>
                <div style="font-size:13px; font-weight:700; color:#15803d;">${Number(winner.availabilityScore || 0).toFixed(1)}</div>
                <div style="font-size:10px; color:#64748b;">Capacity margin</div>
              </div>
              <div style="background:#f8fafc; padding:6px 8px; border-radius:6px; text-align:center;">
                <div style="font-size:10px; color:#64748b; font-weight:600;">Reliability Score (10%)</div>
                <div style="font-size:13px; font-weight:700; color:#7c3aed;">${Number(winner.reliabilityScore || 75.0).toFixed(1)}</div>
                <div style="font-size:10px; color:#64748b;">Historical / baseline</div>
              </div>
            </div>
          </div>

          ${eligibleCount > 0 ? `
            <details class="clean-details-toggle" open style="margin-top:10px;">
              <summary>View eligible candidate suitability ranking (${eligibleCount} evaluated)</summary>
              ${eligibleTableHtml}
            </details>
          ` : ""}
        </div>
      `;
    }).join("");
  }

  // 2. Tasks Requiring Manager Review Section (Clean Orange with Rejection Categories)
  if (reviewList.length > 0) {
    html += `
      <div class="clean-section-header warning" style="margin-top:${assignedList.length > 0 ? '20px' : '0'};">
        <div style="display:flex; align-items:center; gap:8px;">
          <span style="background:#ea580c; color:#ffffff; font-size:12px; font-weight:700; padding:2px 8px; border-radius:999px;">${reviewList.length}</span>
          <h4 style="margin:0; font-size:14px; font-weight:700; color:#9a3412;">Tasks Requiring Manager Review</h4>
        </div>
        <span style="font-size:12px; color:#c2410c;">Hard constraints failed · Status remains Pending</span>
      </div>
    `;

    html += reviewList.map(r => {
      const prio = (r.priority || "Medium").toLowerCase();
      const prioLabel = escapeHTML(r.priority || "Medium");
      const shortReason = getShortReviewReason(r);
      const rejectedCount = (r.rejectedCandidates || []).length;

      let rejectedHtml = "";
      if (rejectedCount > 0) {
        rejectedHtml = `
          <table class="candidate-table" style="margin-top:8px;">
            <thead>
              <tr><th>Candidate</th><th>Constraint Category</th><th>Detailed Rejection Reason</th></tr>
            </thead>
            <tbody>
              ${r.rejectedCandidates.map(c => {
                let catClass = "skill";
                let catLabel = "Missing Required Skill";
                if (c.rejectionCategory === "UNAVAILABLE") { catClass = "avail"; catLabel = "Employee Unavailable"; }
                else if (c.rejectionCategory === "CAPACITY_EXCEEDED") { catClass = "cap"; catLabel = "Maximum Capacity Exceeded"; }
                else if (c.rejectionCategory === "DEADLINE_INFEASIBLE") { catClass = "deadline"; catLabel = "Deadline Infeasible"; }
                return `
                  <tr>
                    <td><strong>${escapeHTML(c.name)}</strong> <span style="font-size:11px; color:#64748b;">(#${c.employeeId})</span></td>
                    <td><span class="badge-rejection-cat ${catClass}">${catLabel}</span></td>
                    <td style="color:#475569;">${escapeHTML(c.rejectionReason)}</td>
                  </tr>
                `;
              }).join("")}
            </tbody>
          </table>
        `;
      }

      return `
        <div class="clean-task-card review">
          <div style="display:flex; justify-content:space-between; align-items:flex-start; flex-wrap:wrap; gap:10px; margin-bottom:8px;">
            <div>
              <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap; margin-bottom:4px;">
                <span style="font-size:12px; font-weight:700; color:#64748b;">#${r.taskId}</span>
                <strong style="font-size:14px; color:#0f172a;">${escapeHTML(r.taskTitle)}</strong>
                <span class="priority-tag priority-${prio}">${prioLabel}</span>
              </div>
              <div style="font-size:12px; color:#64748b; display:flex; gap:14px; flex-wrap:wrap;">
                <span><strong>Required Skill:</strong> ${escapeHTML(r.requiredSkill || "None")}</span>
                <span><strong>Duration:</strong> ${r.durationHours}h</span>
                <span><strong>Deadline:</strong> ${escapeHTML(r.deadline || "No deadline")}</span>
              </div>
            </div>
            <div class="clean-review-pill">
              <span>⚠️ ${escapeHTML(shortReason)}</span>
            </div>
          </div>

          <details class="clean-details-toggle" open>
            <summary>View constraint evaluation &amp; candidate rejection diagnostics</summary>
            <div style="margin-top:8px; padding:10px 12px; background:#f8fafc; border-radius:6px; border:1px solid #e2e8f0; font-size:12px; color:#475569;">
              <div style="margin-bottom:6px;"><strong>Failure Diagnostic:</strong> ${escapeHTML(r.reason)}</div>
              ${rejectedHtml}
            </div>
          </details>
        </div>
      `;
    }).join("");
  }

  listContainer.innerHTML = html;

  const modal = $("assignment-results-modal");
  if (modal) modal.classList.add("active");
  const openBtn = $("open-assignment-results-btn");
  if (openBtn) openBtn.style.display = "inline-flex";
}

// Automatic Heuristic Assignment Run
async function runAutomaticAssignment() {
  if (state.isAssigning) return;
  state.isAssigning = true;
  state.suppressAssignmentSseToast = true;

  const heroBtn = $("hero-run-assignment-btn");
  const tableBtn = $("tasks-run-assignment-btn");
  const origHeroText = heroBtn ? heroBtn.innerHTML : "";
  const origTableText = tableBtn ? tableBtn.innerHTML : "";

  if (heroBtn) { heroBtn.disabled = true; heroBtn.textContent = "Processing..."; }
  if (tableBtn) { tableBtn.disabled = true; tableBtn.textContent = "Processing..."; }

  try {
    const results = await apiRequest("/assignments/run", { method: "POST" });
    state.lastAssignmentResults = results || [];

    const total = state.lastAssignmentResults.length;
    const assignedTasks = state.lastAssignmentResults.filter(r => r.status === "Assigned");
    const assigned = assignedTasks.length;
    const unassigned = total - assigned;

    // Display ONLY ONE consolidated summary notification
    if (total === 0) {
      showToast("Automatic assignment: No pending tasks found to process.", "info");
    } else if (assigned > 0 && unassigned === 0) {
      const whoText = assigned === 1 ? ` (Assigned to ${assignedTasks[0].employeeName})` : "";
      showToast(`Automatic assignment completed successfully.${whoText}\nTotal pending processed: ${total} | Assigned: ${assigned} | Unassigned: 0`, "success");
    } else {
      showToast(`Automatic assignment completed.\nTotal pending processed: ${total} | Assigned: ${assigned} | Remaining unassigned: ${unassigned}`, "warning");
    }

    renderAssignmentResultsModal(state.lastAssignmentResults);
    await loadAllData(false);
  } catch (err) {
    showToast("Assignment failed: " + err.message, "error");
  } finally {
    state.isAssigning = false;
    setTimeout(() => { state.suppressAssignmentSseToast = false; }, 1500);
    if (heroBtn) { heroBtn.disabled = false; heroBtn.innerHTML = origHeroText; }
    if (tableBtn) { tableBtn.disabled = false; tableBtn.innerHTML = origTableText; }
  }
}

// Start Task
async function startTask(taskId) {
  try {
    await apiRequest(`/tasks/${taskId}/start`, { method: "PATCH" });
    showToast("Task marked as In Progress!", "info");
    await loadAllData(false);
  } catch (err) {
    showToast("Failed to start task: " + err.message, "error");
  }
}

// Complete Task
function openCompleteTaskModal(taskId, taskTitle) {
  $("complete-task-id").value = taskId;
  $("complete-task-title-display").innerHTML = `Completing: <strong>${escapeHTML(taskTitle)}</strong>`;
  $("complete-task-notes").value = "";
  $("complete-task-modal").classList.add("active");
}

async function submitCompleteTask(e) {
  e.preventDefault();
  const taskId = $("complete-task-id").value;
  const notes = $("complete-task-notes").value.trim();

  try {
    await apiRequest(`/tasks/${taskId}/complete`, {
      method: "PATCH",
      body: JSON.stringify({ notes })
    });
    $("complete-task-modal").classList.remove("active");
    showToast("Task completed successfully! Workload recalculated.", "success");
    await loadAllData(false);
  } catch (err) {
    showToast("Failed to complete task: " + err.message, "error");
  }
}

// Emergency Submissions
function openReportEmergencyForTask(taskId) {
  navigateToPage("employee-emergencies");
  $("emp-emergency-task").value = String(taskId);
}

async function submitEmployeeEmergency(e) {
  e.preventDefault();
  if (!state.currentUser || !state.currentUser.employeeId) return;

  const type = $("emp-emergency-type").value;
  const taskIdVal = $("emp-emergency-task").value;
  const desc = $("emp-emergency-desc").value.trim();
  const duration = $("emp-emergency-duration").value.trim();

  const payload = {
    employeeId: state.currentUser.employeeId,
    type,
    description: desc,
    taskId: taskIdVal ? parseInt(taskIdVal) : null,
    expectedUnavailability: duration || null
  };

  try {
    await apiRequest("/emergencies", {
      method: "POST",
      body: JSON.stringify(payload)
    });

    $("emp-emergency-desc").value = "";
    $("emp-emergency-duration").value = "";
    showToast("Emergency report transmitted to manager immediately.", "success");
    await loadAllData(false);
  } catch (err) {
    showToast("Failed to submit emergency: " + err.message, "error");
  }
}

async function acknowledgeEmergency(id) {
  try {
    await apiRequest(`/emergencies/${id}/acknowledge`, { method: "PATCH" });
    showToast("Emergency report acknowledged.", "info");
    await loadAllData(false);
  } catch (err) {
    showToast("Failed to acknowledge: " + err.message, "error");
  }
}

function openResolveEmergencyModal(id, employeeName) {
  $("resolve-emergency-id").value = id;
  $("resolve-emergency-info").innerHTML = `Resolving emergency incident for <strong>${escapeHTML(employeeName)}</strong>`;
  $("resolve-emergency-response").value = "";
  $("resolve-emergency-modal").classList.add("active");
}

async function submitResolveEmergency(e) {
  e.preventDefault();
  const id = $("resolve-emergency-id").value;
  const responseText = $("resolve-emergency-response").value.trim();

  try {
    await apiRequest(`/emergencies/${id}/resolve`, {
      method: "PATCH",
      body: JSON.stringify({ response: responseText })
    });
    $("resolve-emergency-modal").classList.remove("active");
    showToast("Emergency incident resolved successfully.", "success");
    await loadAllData(false);
  } catch (err) {
    showToast("Failed to resolve emergency: " + err.message, "error");
  }
}

// Task Reassignment Modal
async function openReassignModalForTask(taskId) {
  state.reassignSelectedTaskId = taskId;
  state.reassignSelectedEmpId = null;

  try {
    const data = await apiRequest(`/assignments/reassign-candidates/${taskId}`);
    const task = data.task;
    const candidates = data.candidates || [];
    const recommended = data.recommended;

    $("reassign-task-summary").innerHTML = `
      <div style="font-size: 13px; font-weight:700; margin-bottom:4px;">${escapeHTML(task.title)}</div>
      <div style="font-size: 12px; color:var(--text-muted);">
        Required Skill: <span class="skill-chip">${escapeHTML(task.requiredSkill)}</span> ·
        Duration: <strong>${task.durationHours}h</strong> ·
        Currently: <strong>${escapeHTML(task.assignedEmployeeName || "Unassigned")}</strong>
      </div>
    `;

    const tbody = $("reassign-candidates-tbody");
    if (candidates.length === 0) {
      const reasonMsg = data.failureReason ? `Reason: <strong>${escapeHTML(data.failureReason)}</strong>` : "No eligible personnel with required skill and available capacity.";
      tbody.innerHTML = `<tr><td colspan="5" class="empty-data-state" style="padding:24px; color:#b91c1c;">⚠️ No eligible personnel found.<br><span style="font-size:12px; color:var(--text-muted);">${reasonMsg}</span></td></tr>`;
      $("confirm-reassign-btn").disabled = true;
    } else {
      tbody.innerHTML = candidates.map((c, idx) => {
        const isRec = recommended && recommended.id === c.id;
        return `
          <tr style="${isRec ? "background:#f0fdf4;" : ""}">
            <td>
              <strong>${escapeHTML(c.name)}</strong>
              ${isRec ? `<span class="badge completed" style="margin-left:6px;">BEST MATCH</span>` : ""}
            </td>
            <td>${(c.skills || []).map(s => `<span class="skill-chip">${escapeHTML(s)}</span>`).join("")}</td>
            <td><strong>${c.workloadHours} hrs</strong></td>
            <td>${c.maxWorkload} hrs</td>
            <td>
              <input type="radio" name="reassign-candidate-radio" value="${c.id}" ${isRec ? "checked" : ""} onchange="window.selectReassignCandidate(${c.id})">
            </td>
          </tr>
        `;
      }).join("");

      state.reassignSelectedEmpId = recommended ? recommended.id : candidates[0].id;
      $("confirm-reassign-btn").disabled = false;
    }

    $("reassign-modal").classList.add("active");
  } catch (err) {
    showToast("Error retrieving replacement candidates: " + err.message, "error");
  }
}

function selectReassignCandidate(empId) {
  state.reassignSelectedEmpId = empId;
  $("confirm-reassign-btn").disabled = false;
}

async function confirmReassignTask() {
  if (!state.reassignSelectedTaskId || !state.reassignSelectedEmpId) return;

  const reason = $("reassign-reason-input").value.trim() || "Manual reassignment via Greedy Heuristic";

  try {
    await apiRequest("/assignments/reassign", {
      method: "POST",
      body: JSON.stringify({
        taskId: state.reassignSelectedTaskId,
        newEmployeeId: state.reassignSelectedEmpId,
        reason
      })
    });

    $("reassign-modal").classList.remove("active");
    showToast("Task successfully reassigned and workloads updated!", "success");
    await loadAllData(false);
  } catch (err) {
    showToast("Failed to reassign task: " + err.message, "error");
  }
}

let currentEmployeeModalSkills = [];

function renderEmployeeModalSkillChips() {
  const container = $("modal-skill-chips-container");
  if (!container) return;
  if (!currentEmployeeModalSkills || currentEmployeeModalSkills.length === 0) {
    container.innerHTML = `<span style="font-size:12px; color:var(--text-muted); padding:4px;">No skills added yet. Add a skill and proficiency level above.</span>`;
    return;
  }
  container.innerHTML = currentEmployeeModalSkills.map((item, idx) => `
    <span class="skill-chip-editable">
      <strong>${escapeHTML(item.name)}</strong>
      <span class="prof-tag ${item.proficiency.toLowerCase()}">${escapeHTML(item.proficiency)}</span>
      <button type="button" class="btn-remove-skill" onclick="window.removeSkillFromModal(${idx})">&times;</button>
    </span>
  `).join("");
}

function addSkillToModal() {
  const input = $("modal-skill-name-input");
  const profSelect = $("modal-skill-prof-select");
  if (!input || !profSelect) return;
  const name = input.value.trim();
  const proficiency = profSelect.value || "Intermediate";
  if (!name) {
    input.focus();
    return;
  }
  const existingIdx = currentEmployeeModalSkills.findIndex(s => s.name.equalsIgnoreCase(name));
  if (existingIdx >= 0) {
    currentEmployeeModalSkills[existingIdx].proficiency = proficiency;
  } else {
    currentEmployeeModalSkills.push({ name, proficiency });
  }
  input.value = "";
  input.focus();
  renderEmployeeModalSkillChips();
}

function removeSkillFromModal(index) {
  if (index >= 0 && index < currentEmployeeModalSkills.length) {
    currentEmployeeModalSkills.splice(index, 1);
    renderEmployeeModalSkillChips();
  }
}

window.addSkillToModal = addSkillToModal;
window.removeSkillFromModal = removeSkillFromModal;

// Employee Add / Edit / Delete
function openAddEmployeeModal() {
  $("employee-modal-title").textContent = "Add Employee";
  $("modal-employee-id").value = "";
  $("modal-employee-name").value = "";
  $("modal-employee-email").value = "";
  $("modal-employee-role").value = "";
  $("modal-employee-max-workload").value = "40";
  $("modal-employee-avail").value = "true";
  currentEmployeeModalSkills = [];
  renderEmployeeModalSkillChips();
  $("employee-modal").classList.add("active");
}

function openEditEmployeeModal(id) {
  const emp = state.employees.find(e => e.id === id);
  if (!emp) return;

  $("employee-modal-title").textContent = "Edit Employee";
  $("modal-employee-id").value = emp.id;
  $("modal-employee-name").value = emp.name || "";
  $("modal-employee-email").value = emp.email || "";
  $("modal-employee-role").value = emp.role || "";
  $("modal-employee-max-workload").value = emp.maxWorkload || 40;
  $("modal-employee-avail").value = emp.available ? "true" : "false";

  currentEmployeeModalSkills = (emp.skills || []).map(s => ({
    name: s,
    proficiency: (emp.skillProficiencies && emp.skillProficiencies[s]) || "Intermediate"
  }));
  renderEmployeeModalSkillChips();
  $("employee-modal").classList.add("active");
}

async function saveEmployee(e) {
  e.preventDefault();
  const id = $("modal-employee-id").value;
  const name = $("modal-employee-name").value.trim();
  const email = $("modal-employee-email").value.trim();
  const role = $("modal-employee-role").value.trim();
  const maxWorkload = parseInt($("modal-employee-max-workload").value) || 40;
  const available = $("modal-employee-avail").value === "true";

  // Check if user entered something in the skill input but forgot to click Add
  const pendInput = $("modal-skill-name-input");
  if (pendInput && pendInput.value.trim()) {
    addSkillToModal();
  }

  if (currentEmployeeModalSkills.length === 0) {
    showToast("Please add at least one skill with a proficiency level for this employee.", "warning");
    return;
  }

  const skills = currentEmployeeModalSkills.map(s => s.name);
  const skillProficiencies = {};
  currentEmployeeModalSkills.forEach(s => {
    skillProficiencies[s.name] = s.proficiency;
  });

  const payload = { name, email, role, skills, skillProficiencies, maxWorkload, available };

  try {
    if (id) {
      await apiRequest(`/employees/${id}`, { method: "PUT", body: JSON.stringify(payload) });
      showToast("Employee details and skill proficiencies updated.", "success");
    } else {
      await apiRequest("/employees", { method: "POST", body: JSON.stringify(payload) });
      showToast("New employee added to workforce.", "success");
    }
    $("employee-modal").classList.remove("active");
    await loadAllData(false);
  } catch (err) {
    showToast("Error saving employee: " + err.message, "error");
  }
}

async function toggleEmployeeAvailability(id, newStatus) {
  try {
    await apiRequest(`/employees/${id}/availability`, {
      method: "PATCH",
      body: JSON.stringify({ available: newStatus })
    });
    showToast("Employee availability updated.", "info");
    await loadAllData(false);
  } catch (err) {
    showToast("Failed to update availability: " + err.message, "error");
  }
}

async function confirmDeleteEmployee(id, name) {
  if (!confirm(`Are you sure you want to remove employee "${name}"? This action cannot be undone.`)) return;

  try {
    await apiRequest(`/employees/${id}`, { method: "DELETE" });
    showToast(`Employee "${name}" removed.`, "info");
    await loadAllData(false);
  } catch (err) {
    showToast("Cannot delete employee: " + err.message, "error");
  }
}

// Task Add / Edit / Delete
function openAddTaskModal() {
  $("task-modal-title").textContent = "Create New Task";
  $("modal-task-id").value = "";
  $("modal-task-title").value = "";
  $("modal-task-desc").value = "";
  $("modal-task-skill").value = "";
  $("modal-task-duration").value = "3";
  $("modal-task-priority").value = "Medium";

  // Default deadline: 7 days from now
  const d = new Date();
  d.setDate(d.getDate() + 7);
  $("modal-task-deadline").value = d.toISOString().split("T")[0];

  $("task-modal").classList.add("active");
}

function openEditTaskModal(id) {
  const task = state.tasks.find(t => t.id === id);
  if (!task) return;

  $("task-modal-title").textContent = "Edit Task";
  $("modal-task-id").value = task.id;
  $("modal-task-title").value = task.title || "";
  $("modal-task-desc").value = task.description || "";
  $("modal-task-skill").value = task.requiredSkill || "";
  $("modal-task-duration").value = task.durationHours || 2;
  $("modal-task-priority").value = task.priority || "Medium";
  $("modal-task-deadline").value = task.deadline || "";
  $("task-modal").classList.add("active");
}

async function saveTask(e) {
  e.preventDefault();
  const id = $("modal-task-id").value;
  const title = $("modal-task-title").value.trim();
  const desc = $("modal-task-desc").value.trim();
  const requiredSkill = $("modal-task-skill").value.trim();
  const durationHours = parseInt($("modal-task-duration").value) || 2;
  const priority = $("modal-task-priority").value;
  const deadline = $("modal-task-deadline").value;

  const payload = { title, description: desc, requiredSkill, durationHours, priority, deadline };

  try {
    if (id) {
      await apiRequest(`/tasks/${id}`, { method: "PUT", body: JSON.stringify(payload) });
      showToast("Task updated.", "success");
    } else {
      await apiRequest("/tasks", { method: "POST", body: JSON.stringify(payload) });
      showToast("New task created.", "success");
    }
    $("task-modal").classList.remove("active");
    await loadAllData(false);
  } catch (err) {
    showToast("Error saving task: " + err.message, "error");
  }
}

async function confirmDeleteTask(id, title) {
  if (!confirm(`Are you sure you want to delete task "${title}"?`)) return;

  try {
    await apiRequest(`/tasks/${id}`, { method: "DELETE" });
    showToast(`Task "${title}" deleted.`, "info");
    await loadAllData(false);
  } catch (err) {
    showToast("Failed to delete task: " + err.message, "error");
  }
}

// Bulk Import Modal Controller
function openImportModal(type = "employees") {
  state.importType = type;
  state.selectedImportFile = null;

  $("import-modal-title").textContent = type === "employees" ? "Bulk Import Employees" : "Bulk Import Tasks";
  $("import-modal-desc").textContent = type === "employees"
    ? "Upload CSV or Excel (.xlsx) with Name, Email, Role, Skills, Availability, MaxWorkload columns."
    : "Upload CSV or Excel (.xlsx) with Title, Description, RequiredSkill, DurationHours, Priority, Deadline.";

  $("import-download-template-btn").href = `${API_BASE}/import/template/${type}`;
  $("import-selected-file-name").style.display = "none";
  $("import-results-box").style.display = "none";
  $("import-error-list").style.display = "none";
  $("submit-import-btn").disabled = true;

  $("import-modal").classList.add("active");
}

async function submitBulkImport() {
  if (!state.selectedImportFile) return;

  const btn = $("submit-import-btn");
  btn.disabled = true;
  btn.textContent = "Processing...";

  const formData = new FormData();
  formData.append("file", state.selectedImportFile);

  try {
    const res = await apiRequest(`/import/${state.importType}`, {
      method: "POST",
      body: formData
    });

    $("import-results-box").style.display = "block";
    $("imp-total").textContent = res.totalRecords || 0;
    $("imp-success").textContent = res.successfulRecords || 0;
    $("imp-duplicates").textContent = res.duplicateRecords || 0;
    $("imp-invalid").textContent = (res.invalidRecords || 0) + (res.failedRecords || 0);

    const errBox = $("import-error-list");
    if (res.errorMessages && res.errorMessages.length > 0) {
      errBox.innerHTML = res.errorMessages.map(m => `<div>• ${escapeHTML(m)}</div>`).join("");
      errBox.style.display = "block";
    } else {
      errBox.style.display = "none";
    }

    showToast(`Import processed: ${res.successfulRecords} records saved.`, "success");
    await loadAllData(false);
  } catch (err) {
    showToast("Import failed: " + err.message, "error");
  } finally {
    btn.disabled = false;
    btn.textContent = "Upload & Process";
  }
}

// Export History to CSV
function exportHistoryToCSV() {
  if (state.assignmentsHistory.length === 0) {
    showToast("No assignment history to export.", "warning");
    return;
  }

  const headers = ["Assignment ID", "Timestamp", "Task ID", "Task Title", "Employee ID", "Employee Name", "Workload Before", "Workload After", "Algorithm", "Outcome", "Reason", "Reassigned"];
  const rows = state.assignmentsHistory.map(a => [
    a.id,
    `"${a.assignmentTime || ""}"`,
    a.taskId || "",
    `"${(a.taskTitle || "").replace(/"/g, '""')}"`,
    a.employeeId || "",
    `"${(a.employeeName || "").replace(/"/g, '""')}"`,
    a.workloadBefore !== null ? a.workloadBefore : "",
    a.workloadAfter !== null ? a.workloadAfter : "",
    `"${a.algorithm || "Greedy Minimum Workload"}"`,
    a.outcome || "",
    `"${(a.reason || "").replace(/"/g, '""')}"`,
    a.reassigned ? "YES" : "NO"
  ]);

  const csvContent = [headers.join(","), ...rows.map(r => r.join(","))].join("\n");
  const blob = new Blob([csvContent], { type: "text/csv;charset=utf-8;" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.setAttribute("href", url);
  link.setAttribute("download", `assignment_history_${new Date().toISOString().slice(0, 10)}.csv`);
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  showToast("Assignment history exported to CSV.", "success");
}

// Notification Drawer Toggle
function toggleNotificationDropdown() {
  const dropdown = $("notification-dropdown");
  dropdown.classList.toggle("active");
  if (dropdown.classList.contains("active")) {
    renderNotificationList();
  }
}

function renderNotificationList() {
  const container = $("notif-list-container");
  if (state.notifications.length === 0) {
    container.innerHTML = `<div class="empty-data-state" style="padding:20px;">No notifications yet.</div>`;
    return;
  }

  container.innerHTML = state.notifications.map(n => {
    let circleClass = "info";
    let icon = "ℹ️";
    if (n.type === "EMERGENCY") { circleClass = "emergency"; icon = "🚨"; }
    else if (n.type === "ALERT") { circleClass = "alert"; icon = "⚠️"; }
    else if (n.type === "TASK_ASSIGNED" || n.type === "TASK_COMPLETED") { circleClass = "task"; icon = "📋"; }

    return `
      <div class="notif-item ${n.read ? "" : "unread"}" onclick="window.markNotificationRead(${n.id})">
        <div class="notif-icon-circle ${circleClass}">${icon}</div>
        <div class="notif-content">
          <p>${escapeHTML(n.message)}</p>
          <time>${formatDate(n.createdAt)}</time>
        </div>
      </div>
    `;
  }).join("");
}

async function markNotificationRead(id) {
  try {
    await apiRequest(`/notifications/${id}/read`, { method: "PATCH" });
    const notif = state.notifications.find(n => n.id === id);
    if (notif) notif.read = true;
    updateNotificationBadge();
    renderNotificationList();
  } catch (err) {
    console.error("Failed to mark notification read", err);
  }
}

async function markAllNotificationsRead() {
  try {
    const empId = (state.currentUser && state.currentUser.role === "EMPLOYEE") ? state.currentUser.employeeId : null;
    const url = empId ? `/notifications/read-all?employeeId=${empId}` : "/notifications/read-all";
    await apiRequest(url, { method: "POST" });
    state.notifications.forEach(n => n.read = true);
    updateNotificationBadge();
    renderNotificationList();
    showToast("All notifications marked as read.", "info");
  } catch (err) {
    console.error("Failed to mark all read", err);
  }
}

// Window attachments for inline event handlers & global access
window.toggleEmployeeAvailability = toggleEmployeeAvailability;
window.openEditEmployeeModal = openEditEmployeeModal;
window.confirmDeleteEmployee = confirmDeleteEmployee;
window.openEditTaskModal = openEditTaskModal;
window.confirmDeleteTask = confirmDeleteTask;
window.openReassignModalForTask = openReassignModalForTask;
window.selectReassignCandidate = selectReassignCandidate;
window.openCompleteTaskModal = openCompleteTaskModal;
window.openReportEmergencyForTask = openReportEmergencyForTask;
window.startTask = startTask;
window.acknowledgeEmergency = acknowledgeEmergency;
window.openResolveEmergencyModal = openResolveEmergencyModal;
window.markNotificationRead = markNotificationRead;
window.runAutomaticAssignment = runAutomaticAssignment;
window.openAddEmployeeModal = openAddEmployeeModal;
window.openAddTaskModal = openAddTaskModal;
window.openImportModal = openImportModal;
window.exportHistoryToCSV = exportHistoryToCSV;
window.loginUser = loginUser;
window.logoutUser = logoutUser;
window.loadAllData = loadAllData;
window.navigateToPage = navigateToPage;

// =========================================================================
// EVENT LISTENERS & INITIALIZATION
// =========================================================================
document.addEventListener("DOMContentLoaded", async () => {
  // 1. Navigation Click Handlers
  $$(".nav-btn").forEach(btn => {
    btn.addEventListener("click", () => navigateToPage(btn.dataset.page));
  });

  // 2. Role Switcher in Topbar
  $("header-role-switcher").addEventListener("change", (e) => {
    const selected = e.target.value;
    if (selected === "MANAGER") {
      loginUser("manager", "manager123");
    } else {
      // Switch to first employee or Alsaba
      const emp = state.employees.find(e => e.email) || { email: "emp1@company.com" };
      loginUser(emp.email, "employee123");
    }
  });

  // 3. Login Form Submit
  $("login-form").addEventListener("submit", (e) => {
    e.preventDefault();
    const u = $("login-username").value.trim();
    const p = $("login-password").value.trim();
    loginUser(u, p);
  });

  // 4. Quick Demo Switcher Buttons
  $("quick-login-manager").addEventListener("click", () => loginUser("manager", "manager123"));
  $("quick-login-emp1").addEventListener("click", () => loginUser("alsaba@company.com", "employee123"));
  $("quick-login-emp2").addEventListener("click", () => loginUser("emp2@company.com", "employee123"));
  $("logout-button").addEventListener("click", logoutUser);

  // 5. Automatic Assignment Buttons
  $("hero-run-assignment-btn").addEventListener("click", runAutomaticAssignment);
  $("tasks-run-assignment-btn").addEventListener("click", runAutomaticAssignment);
  const openResBtn = $("open-assignment-results-btn");
  if (openResBtn) {
    openResBtn.addEventListener("click", () => {
      if (state.lastAssignmentResults && state.lastAssignmentResults.length > 0) {
        renderAssignmentResultsModal(state.lastAssignmentResults);
      } else {
        showToast("No assignment results to display yet. Run Automatic Assignment first.", "info");
      }
    });
  }

  // 6. Modal Openers
  $("open-add-employee-modal").addEventListener("click", openAddEmployeeModal);
  $("open-add-task-modal").addEventListener("click", openAddTaskModal);
  $("bulk-import-emp-btn").addEventListener("click", () => openImportModal("employees"));
  $("bulk-import-tasks-btn").addEventListener("click", () => openImportModal("tasks"));
  $("export-history-csv-btn").addEventListener("click", exportHistoryToCSV);

  // 7. Modal Closers
  $$("[data-close]").forEach(btn => {
    btn.addEventListener("click", () => {
      const modal = $(btn.dataset.close);
      if (modal) modal.classList.remove("active");
    });
  });

  // 8. Form Submissions & Interactive Builders
  const addSkillBtn = $("btn-add-skill-prof");
  if (addSkillBtn) {
    addSkillBtn.addEventListener("click", addSkillToModal);
  }
  const skillNameInput = $("modal-skill-name-input");
  if (skillNameInput) {
    skillNameInput.addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        e.preventDefault();
        addSkillToModal();
      }
    });
  }

  $("employee-form").addEventListener("submit", saveEmployee);
  $("task-form").addEventListener("submit", saveTask);
  $("complete-task-form").addEventListener("submit", submitCompleteTask);
  $("employee-report-emergency-form").addEventListener("submit", submitEmployeeEmergency);
  $("resolve-emergency-form").addEventListener("submit", submitResolveEmergency);
  $("confirm-reassign-btn").addEventListener("click", confirmReassignTask);

  // 9. File Dropzone & Import
  const dropzone = $("file-dropzone");
  const fileInput = $("import-file-input");
  dropzone.addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", (e) => {
    if (e.target.files.length > 0) {
      state.selectedImportFile = e.target.files[0];
      $("import-selected-file-name").textContent = `Selected: ${state.selectedImportFile.name} (${(state.selectedImportFile.size / 1024).toFixed(1)} KB)`;
      $("import-selected-file-name").style.display = "block";
      $("submit-import-btn").disabled = false;
    }
  });

  dropzone.addEventListener("dragover", (e) => { e.preventDefault(); dropzone.classList.add("dragover"); });
  dropzone.addEventListener("dragleave", () => dropzone.classList.remove("dragover"));
  dropzone.addEventListener("drop", (e) => {
    e.preventDefault();
    dropzone.classList.remove("dragover");
    if (e.dataTransfer.files.length > 0) {
      state.selectedImportFile = e.dataTransfer.files[0];
      $("import-selected-file-name").textContent = `Selected: ${state.selectedImportFile.name}`;
      $("import-selected-file-name").style.display = "block";
      $("submit-import-btn").disabled = false;
    }
  });

  $("submit-import-btn").addEventListener("click", submitBulkImport);

  // 10. Filters & Search Inputs
  $("employee-search-input").addEventListener("input", renderEmployeesPage);
  $("employee-skill-filter").addEventListener("change", renderEmployeesPage);
  $("employee-avail-filter").addEventListener("change", renderEmployeesPage);

  $("task-search-input").addEventListener("input", renderTasksPage);
  $("task-status-filter").addEventListener("change", renderTasksPage);
  $("task-priority-filter").addEventListener("change", renderTasksPage);
  $("task-skill-filter").addEventListener("change", renderTasksPage);

  // 11. Notification Bell & Dropdown
  $("notif-bell-btn").addEventListener("click", toggleNotificationDropdown);
  $("mark-all-read-btn").addEventListener("click", markAllNotificationsRead);

  // Close notification dropdown when clicking outside
  document.addEventListener("click", (e) => {
    const dropdown = $("notification-dropdown");
    const bell = $("notif-bell-btn");
    if (dropdown && !dropdown.contains(e.target) && !bell.contains(e.target)) {
      dropdown.classList.remove("active");
    }
  });

  // 12. Check Stored Auth Session
  const savedAuth = localStorage.getItem("taskflow_auth");
  if (savedAuth) {
    try {
      state.currentUser = JSON.parse(savedAuth);
      if (!state.currentUser.authHeader) {
        state.currentUser.authHeader = "Basic " + btoa(
          (state.currentUser.username || "manager") + ":" +
          (state.currentUser.role === "EMPLOYEE" ? "employee123" : "manager123")
        );
        localStorage.setItem("taskflow_auth", JSON.stringify(state.currentUser));
      }
      applyUserSession();
    } catch (e) {
      await loginUser("manager", "manager123");
    }
  } else {
    // Default to manager for easy immediate review
    await loginUser("manager", "manager123");
  }

  // 13. Initialize Realtime Stream & Initial Data Fetch
  initRealtimeStream();
  await loadAllData();
});
