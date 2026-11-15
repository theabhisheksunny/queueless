const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';

export interface CustomerSummary {
  id: number;
  tokenNumber: string;
  tokenSequence: number;
  customerName: string;
  customerPhone?: string;
  trackingToken: string;
  status: 'WAITING' | 'CALLED' | 'SERVING' | 'COMPLETED' | 'SKIPPED' | 'NO_SHOW' | 'LEFT' | 'CANCELLED';
  addedBy: 'QR_CODE' | 'MANUAL_OWNER';
  joinedAt: string;
  calledAt?: string;
  servedAt?: string;
  completedAt?: string;
}

export interface QueueDashboardData {
  shopId: number;
  shopName: string;
  category: string;
  avgServiceDurationMinutes: number;
  sessionId?: number;
  publicQueueId?: string;
  publicJoinUrl?: string;
  queueOpen: boolean;
  totalCustomers: number;
  waitingCustomers: number;
  currentlyServingToken?: string;
  currentlyServingName?: string;
  customers: CustomerSummary[];
  subscriptionStatus: 'ACTIVE' | 'EXPIRED' | 'PENDING_APPROVAL';
  subscriptionDaysRemaining: number;
  subscriptionEndDate?: string;
}

export interface CustomerPositionData {
  shopName: string;
  tokenNumber: string;
  currentlyServingToken: string;
  peopleAhead: number;
  estimatedWaitMinutes: number;
  status: 'WAITING' | 'CALLED' | 'SERVING' | 'COMPLETED' | 'SKIPPED' | 'NO_SHOW' | 'LEFT' | 'CANCELLED';
  queueOpen: boolean;
  trackingToken: string;
  joinedAt: string;
}

export interface SubscriptionStatusData {
  status: 'ACTIVE' | 'EXPIRED' | 'PENDING_APPROVAL';
  planType: 'MONTHLY' | 'YEARLY' | 'TRIAL';
  startDate?: string;
  endDate?: string;
  daysRemaining: number;
  active: boolean;
  configuredUpiId: string;
  configuredUpiName: string;
  monthlyPrice: number;
  yearlyPrice: number;
  pendingPaymentReference?: string;
}

export interface ShopSettingsData {
  id: number;
  name: string;
  category: string;
  avgServiceDurationMinutes: number;
}

export interface AdminShopItemData {
  shopId: number;
  shopName: string;
  category: string;
  ownerName: string;
  ownerEmail: string;
  ownerPhone: string;
  subscriptionStatus: 'ACTIVE' | 'EXPIRED' | 'PENDING_APPROVAL';
  planType?: string;
  subscriptionEndDate?: string;
  active: boolean;
  pendingPaymentReference?: string;
}

async function request<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
  const token = localStorage.getItem('queueless_token');
  const headers = new Headers(options.headers || {});

  if (!headers.has('Content-Type') && !(options.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }

  if (token && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  const response = await fetch(`${BASE_URL}${endpoint}`, {
    ...options,
    headers,
  });

  if (!response.ok) {
    let errorMsg = 'An error occurred';
    try {
      const errorJson = await response.json();
      errorMsg = errorJson.error || errorJson.message || JSON.stringify(errorJson);
    } catch {
      errorMsg = await response.text() || response.statusText;
    }
    throw new Error(errorMsg);
  }

  if (response.status === 204) {
    return {} as T;
  }

  return response.json();
}

export const api = {
  // Auth
  register: (data: any) => request<any>('/api/auth/register', { method: 'POST', body: JSON.stringify(data) }),
  login: (data: any) => request<any>('/api/auth/login', { method: 'POST', body: JSON.stringify(data) }),

  // Owner Queue Dashboard
  getDashboard: () => request<QueueDashboardData>('/api/queue/dashboard'),
  startQueue: () => request<QueueDashboardData>('/api/queue/start', { method: 'POST' }),
  closeQueue: () => request<QueueDashboardData>('/api/queue/close', { method: 'POST' }),
  manualAddCustomer: (data: { customerName: string; customerPhone?: string }) =>
    request<QueueDashboardData>('/api/queue/customers/manual', { method: 'POST', body: JSON.stringify(data) }),
  callNextCustomer: () => request<QueueDashboardData>('/api/queue/customers/next', { method: 'POST' }),
  updateCustomerStatus: (customerId: number, status: string) =>
    request<QueueDashboardData>(`/api/queue/customers/${customerId}/status?status=${status}`, { method: 'PATCH' }),
  editCustomer: (customerId: number, data: { customerName: string; customerPhone?: string }) =>
    request<QueueDashboardData>(`/api/queue/customers/${customerId}`, { method: 'PUT', body: JSON.stringify(data) }),
  removeCustomer: (customerId: number) =>
    request<QueueDashboardData>(`/api/queue/customers/${customerId}`, { method: 'DELETE' }),

  // Public Customer QR
  joinQueue: (publicQueueId: string, data: { customerName: string; customerPhone?: string }) =>
    request<any>(`/api/public/queue/${publicQueueId}/join`, { method: 'POST', body: JSON.stringify(data) }),
  trackPosition: (trackingToken: string) =>
    request<CustomerPositionData>(`/api/public/queue/track/${trackingToken}`),
  leaveQueue: (trackingToken: string) =>
    request<CustomerPositionData>(`/api/public/queue/track/${trackingToken}/leave`, { method: 'POST' }),
  rejoinQueue: (trackingToken: string) =>
    request<CustomerPositionData>(`/api/public/queue/track/${trackingToken}/rejoin`, { method: 'POST' }),

  // Subscription
  getSubscriptionStatus: () => request<SubscriptionStatusData>('/api/owner/subscription'),
  submitSubscriptionPayment: (data: { planType: string; paymentReference: string; paymentUpiId?: string }) =>
    request<SubscriptionStatusData>('/api/owner/subscription/payment', { method: 'POST', body: JSON.stringify(data) }),

  // Settings
  getShopSettings: () => request<ShopSettingsData>('/api/owner/settings'),
  updateShopSettings: (data: { name: string; category: string; avgServiceDurationMinutes: number }) =>
    request<ShopSettingsData>('/api/owner/settings', { method: 'PUT', body: JSON.stringify(data) }),

  // Minimal Admin
  getAdminConfig: (adminKey: string) =>
    request<any>('/api/admin/config', { headers: { 'X-Admin-Key': adminKey } }),
  updateAdminSettings: (adminKey: string, data: any) =>
    request<any>('/api/admin/settings', { method: 'POST', headers: { 'X-Admin-Key': adminKey }, body: JSON.stringify(data) }),
  getAdminShops: (adminKey: string) =>
    request<AdminShopItemData[]>('/api/admin/shops', { headers: { 'X-Admin-Key': adminKey } }),
  activateAdminShop: (adminKey: string, data: { shopId: number; planType: string; customDays?: number }) =>
    request<any>('/api/admin/activate', { method: 'POST', headers: { 'X-Admin-Key': adminKey }, body: JSON.stringify(data) }),

  // SSE Stream URLs
  getOwnerSseUrl: () => {
    const token = localStorage.getItem('queueless_token');
    return `${BASE_URL}/api/queue/stream/owner?token=${encodeURIComponent(token || '')}`;
  },
  getCustomerSseUrl: (trackingToken: string) => {
    return `${BASE_URL}/api/public/queue/stream/customer/${encodeURIComponent(trackingToken)}`;
  },
};
