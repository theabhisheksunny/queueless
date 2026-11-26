/**
 * Resolves the backend API base URL:
 * - If VITE_API_URL is explicitly set at build time, uses that URL.
 * - In development mode (import.meta.env.DEV), falls back to 'http://localhost:8080'.
 * - In production mode (import.meta.env.PROD), falls back to empty string '' (same-origin relative URL).
 *   This ensures no 'localhost' references leak into production bundles and allows
 *   Cloudflare Workers reverse-proxying or same-origin API hosting without Mixed Content or CORS errors.
 */
function getApiBaseUrl(): string {
  const configured = import.meta.env.VITE_API_URL;
  if (configured && configured.trim()) {
    return configured.trim().replace(/\/+$/, '');
  }
  if (import.meta.env.DEV) {
    return 'http://localhost:8080';
  }
  return '';
}

const BASE_URL = getApiBaseUrl();

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
  businessType?: string;
  avgServiceDurationMinutes: number;
  sessionId?: number;
  publicQueueId?: string;
  publicJoinUrl?: string;
  canonicalJoinUrl?: string;
  publicMerchantCode?: string;
  queueOpen: boolean;
  totalCustomers: number;
  waitingCustomers: number;
  currentlyServingToken?: string;
  currentlyServingName?: string;
  currentlyServingCustomers?: CustomerSummary[];
  customers: CustomerSummary[];
  subscriptionStatus: 'ACTIVE' | 'EXPIRED' | 'PENDING_APPROVAL';
  subscriptionDaysRemaining: number;
  subscriptionEndDate?: string;
}

export interface CustomerPositionData {
  shopName: string;
  category?: string;
  tokenNumber: string;
  currentlyServingToken: string;
  peopleAhead: number;
  estimatedWaitMinutes: number;
  status: 'WAITING' | 'CALLED' | 'SERVING' | 'COMPLETED' | 'SKIPPED' | 'NO_SHOW' | 'LEFT' | 'CANCELLED';
  queueOpen: boolean;
  trackingToken: string;
  joinedAt: string;
  completedAt?: string;
  expiresAt?: string;
}

export interface PublicQueueInfoData {
  shopName: string;
  category: string;
  queueOpen: boolean;
  totalWaiting: number;
  currentlyServingToken?: string;
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
  trialStartedAt?: string;
  trialEndsAt?: string;
  trialConsumedAt?: string;
  trialExpired?: boolean;
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

export interface CategoryChangeStatusData {
  currentCategory: string;
  fee: number;
  upiId: string;
  upiName: string;
  qrPayload: string;
  hasPendingRequest: boolean;
  allowedCategories: string[];
  requestId?: number;
  oldCategory?: string;
  requestedCategory?: string;
  amount?: number;
  paymentReference?: string;
  status?: 'PAYMENT_PENDING' | 'PENDING_VERIFICATION' | 'APPROVED' | 'REJECTED';
  rejectionReason?: string;
  createdAt?: string;
  reviewedAt?: string;
}

export interface AdminCategoryChangeItemData {
  id: number;
  shopId: number;
  shopName: string;
  ownerName?: string;
  ownerEmail?: string;
  ownerPhone?: string;
  oldCategory: string;
  requestedCategory: string;
  amount: number;
  paymentReference?: string;
  status: 'PAYMENT_PENDING' | 'PENDING_VERIFICATION' | 'APPROVED' | 'REJECTED';
  rejectionReason?: string;
  createdAt: string;
  reviewedAt?: string;
  reviewedBy?: string;
}

export interface BranchData {
  id: number;
  name: string;
  branchName: string;
  category: string;
  businessType?: string;
  address?: string;
  phone?: string;
  avgServiceDurationMinutes?: number;
  isPrimary: boolean;
  registrationStatus: 'REGISTERED' | 'PAYMENT_PENDING';
  registrationFeePaid: number;
  pendingPaymentReference?: string;
  configuredUpiId?: string;
  configuredUpiName?: string;
  registrationFee?: number;
  businessId?: number;
  businessName?: string;
}

export interface BusinessData {
  id: number;
  name: string;
  createdAt: string;
  branches: BranchData[];
}

export interface StaffData {
  id: number;
  fullName: string;
  phone: string;
  role: string;
  status: 'ACTIVE' | 'REVOKED';
  activeSessionId?: string;
  lastLoginAt?: string;
  createdAt: string;
  assignedBranchIds: number[];
  assignedBranchNames: string[];
  registeredDeviceName?: string;
  devicePlatform?: string;
  deviceEnrollmentStatus?: 'ENROLLED' | 'AWAITING_ENROLLMENT' | 'NO_DEVICE';
  hasPendingReplacement?: boolean;
  pendingReplacementId?: number;
  temporaryPin?: string;
}

export interface DeviceReplacementData {
  id: number;
  userId: number;
  userFullName?: string;
  userPhone?: string;
  userRole?: string;
  ownerId?: number;
  oldDeviceId?: string;
  oldDeviceName?: string;
  newDeviceId?: string;
  newDeviceName?: string;
  newDevicePlatform?: string;
  reason?: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  reviewerRole?: string;
  createdAt: string;
  reviewedAt?: string;
}

export interface DeviceEnrollmentData {
  phone: string;
  temporaryPin: string;
  newPermanentPin: string;
  confirmPermanentPin: string;
  deviceRegistrationId: string;
  deviceName: string;
  platform: string;
  publicKey: string;
}

export interface DeviceProofLoginData {
  phone: string;
  pin: string;
  deviceRegistrationId: string;
  challenge?: string;
  clientTimestamp?: number;
  signature?: string;
  deviceFingerprint?: string;
}

export interface DeviceChallengeResponseData {
  challenge: string;
  expiresAt: string;
  validitySeconds: number;
}

export interface DeviceReplacementCompleteData {
  phone: string;
  pin: string;
  deviceRegistrationId: string;
  publicKey: string;
}

export interface DeviceReplacementRequestData {
  phone: string;
  pin: string;
  requestedDeviceRegistrationId?: string;
  requestedDeviceName: string;
  requestedDevicePlatform: string;
  requestedPublicKey: string;
}

export interface SubscriptionSeatItemData {
  id: number;
  planType: string;
  status: string;
  startDate: string;
  endDate: string;
  amount: number;
  paymentReference?: string;
  assignedUserId?: number;
  assignedUserName?: string;
  assignedUserPhone?: string;
  active: boolean;
}

export interface SeatSummaryData {
  includedSeats: number;
  additionalSeatsPurchased: number;
  totalActiveSeats: number;
  assignedSeats: number;
  availableSeats: number;
  monthlyPrice: number;
  yearlyPrice: number;
  configuredUpiId: string;
  configuredUpiName: string;
  seats: SubscriptionSeatItemData[];
}

export interface AdminPaymentItemData {
  id: number;
  paymentType: string;
  ownerId?: number;
  ownerName?: string;
  ownerPhone?: string;
  shopId?: number;
  shopName?: string;
  seatId?: number;
  batchCount?: number;
  batchShopIds?: number[];
  amount: number;
  paymentReference: string;
  paymentUpiId: string;
  status: string;
  planType?: string;
  notes?: string;
  rejectionReason?: string;
  createdAt: string;
  reviewedAt?: string;
  reviewedBy?: string;
}

export interface AdminDashboardSummaryData {
  totalShopOwners: number;
  totalRegisteredBranches: number;
  activeSubscriptions: number;
  expiredSubscriptions: number;
  pendingPayments: number;
  totalApprovedPayments: number;
  totalApprovedAmount: number;
  latestPendingPayments: AdminPaymentItemData[];
  recentlyApprovedPayments: AdminPaymentItemData[];
  recentlyRegisteredOwners: {
    ownerId: number;
    ownerName: string;
    phone: string;
    email?: string;
    registeredAt: string;
    branchCount: number;
    subscriptionStatus: string;
  }[];
}

export interface AdminSubscriptionData {
  id: number;
  ownerId?: number;
  ownerName?: string;
  ownerPhone?: string;
  ownerEmail?: string;
  shopId?: number;
  shopName?: string;
  plan: string;
  amount: number;
  startDate: string;
  endDate: string;
  status: string;
  daysRemaining: number;
  paymentReference?: string;
  paymentUpiId?: string;
  notes?: string;
  createdAt: string;
}

export interface AdminUserShopData {
  ownerId: number;
  ownerName: string;
  phone: string;
  email?: string;
  registeredAt: string;
  subscriptionPlan: string;
  subscriptionStatus: string;
  subscriptionEndDate?: string;
  businesses: { id: number; name: string }[];
  branches: {
    id: number;
    name: string;
    branchName?: string;
    businessName?: string;
    category: string;
    publicQueueId?: string;
    primary: boolean;
    registrationStatus: string;
    address?: string;
  }[];
}

export interface AdminActivityLogData {
  id: number;
  action: string;
  actor: string;
  targetType: string;
  targetId: string;
  details?: string;
  createdAt: string;
}

/**
 * Translates raw backend exceptions and HTTP status codes into clear,
 * empathetic, user-friendly language suitable for shop owners and customers.
 * Technical details (SQL, stack traces, class names) are never shown.
 */
function toUserFriendlyMessage(
  rawMsg: string,
  status: number,
  details?: Record<string, string>,
  correlationId?: string
): string {
  if (!rawMsg && (!details || Object.keys(details).length === 0)) {
    return "We're having trouble processing your request. Please try again.";
  }

  // If structured field-level validation errors exist, present the first clear field message
  if (details && Object.keys(details).length > 0) {
    const firstField = Object.keys(details)[0];
    const fieldMsg = details[firstField];
    if (fieldMsg) {
      return fieldMsg;
    }
  }

  // Preserve specific business validation messages already crafted for users
  const isAlreadyFriendly =
    rawMsg &&
    rawMsg.length > 20 &&
    !['Forbidden', 'Unauthorized', 'Bad credentials', 'Internal Server Error', 'Access Denied'].includes(rawMsg) &&
    !rawMsg.startsWith('org.springframework') &&
    !rawMsg.startsWith('java.') &&
    !rawMsg.startsWith('{');

  if (isAlreadyFriendly) {
    return rawMsg;
  }

  if (rawMsg && rawMsg.startsWith('{') && rawMsg.endsWith('}')) {
    try {
      const parsed = JSON.parse(rawMsg);
      if (parsed.message && !['Forbidden', 'Unauthorized', 'Bad credentials', 'Internal Server Error'].includes(parsed.message)) {
        return parsed.message;
      }
      if (parsed.error && !['Forbidden', 'Unauthorized', 'Bad credentials'].includes(parsed.error)) {
        return parsed.error;
      }
    } catch {}
  }

  switch (status) {
    case 400:
      return rawMsg && !rawMsg.includes('Exception')
        ? rawMsg
        : 'Please verify the submitted details and try again.';
    case 401:
      if (rawMsg && (rawMsg.includes('Bad credentials') || rawMsg.includes('Invalid credentials') || rawMsg.includes('Incorrect mobile'))) {
        return 'Incorrect mobile number or 6-digit PIN. Please verify and try again.';
      }
      if (rawMsg && rawMsg.includes('revoked')) {
        return 'Your account access has been revoked. Please contact your shop owner or support.';
      }
      return 'Your session has ended. Please sign in with your 6-digit PIN to continue.';
    case 402:
      return 'Subscription required: Your subscription or trial has expired. Please renew your subscription to continue managing your queue.';
    case 403:
      if (rawMsg && (rawMsg.includes('DEVICE_MISMATCH') || rawMsg.includes('enrolled on another device'))) {
        return rawMsg;
      }
      if (rawMsg && (rawMsg.includes('own staff account') || rawMsg.includes('Only shop owners') || rawMsg.includes('Owner privileges'))) {
        return 'This section is reserved for the shop owner account.';
      }
      return 'Access restricted: You do not have permission for this shop action. Please contact your shop owner.';
    case 404:
      return 'The requested queue, shop, or customer record was not found.';
    case 408:
      return 'Request timed out. Please check your network connection and try again.';
    case 409:
      if (rawMsg && (rawMsg.includes('conflict') || rawMsg.includes('already exists') || rawMsg.includes('Trial Already Consumed'))) {
        return rawMsg;
      }
      return 'A conflict occurred with an existing queue or business record. Please refresh and try again.';
    case 410:
      return 'This customer tracking link has expired (15 minutes after completion). Please scan the QR code at the shop to join again.';
    case 423:
      return 'Account temporarily locked due to 5 consecutive incorrect PIN attempts. Please try again in 15 minutes.';
    case 429:
      return 'Too many requests. Please slow down and wait a moment before trying again.';
    case 500:
    case 502:
    case 503:
    case 504: {
      const refSuffix = correlationId ? ` (Ref: ${correlationId.substring(0, 8)})` : '';
      return `We're having trouble processing your request. Please try again.${refSuffix}`;
    }
    default:
      return rawMsg || "We're having trouble processing your request. Please try again.";
  }
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

  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${endpoint}`, {
      ...options,
      headers,
    });
  } catch {
    // Network failures or offline errors
    const err: any = new Error("Unable to connect to QueueLess. Please check your internet connection and try again.");
    err.status = 0;
    err.isNetworkError = true;
    throw err;
  }

  if (!response.ok) {
    let errorMsg = 'An error occurred';
    let correlationId: string | undefined;
    let details: Record<string, string> | undefined;

    try {
      const errorJson = await response.json();
      errorMsg = errorJson.message || errorJson.error || JSON.stringify(errorJson);
      correlationId = errorJson.correlationId;
      details = errorJson.details;
    } catch {
      errorMsg = (await response.text()) || response.statusText;
    }

    // 401 Unauthorized handling:
    // If session has expired or is invalid, and user is not already on /login,
    // dispatch an unauthorized event so AuthContext / Router handles redirection safely
    if (response.status === 401) {
      if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/login')) {
        window.dispatchEvent(
          new CustomEvent('queueless:unauthorized', {
            detail: {
              targetPath: window.location.pathname + window.location.search,
            },
          })
        );
      }
    }

    const friendlyMsg = toUserFriendlyMessage(errorMsg, response.status, details, correlationId);
    const err: any = new Error(friendlyMsg);
    err.status = response.status;
    err.rawMessage = errorMsg;
    err.correlationId = correlationId;
    err.details = details;
    throw err;
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
  pinLogin: (phone: string, pin: string, deviceFingerprint?: string) =>
    request<any>('/api/auth/login', { method: 'POST', body: JSON.stringify({ phone, pin, deviceFingerprint }) }),
  resetPin: (data: { phone: string; currentPin?: string; newPin: string; confirmPin: string; deviceFingerprint?: string }) =>
    request<any>('/api/auth/reset-pin', { method: 'POST', body: JSON.stringify(data) }),
  deleteAccount: () => request<any>('/api/owner/account', { method: 'DELETE' }),

  // Owner Queue Dashboard
  getDashboard: (shopId?: number) =>
    request<QueueDashboardData>(shopId ? `/api/queue/dashboard?shopId=${shopId}` : '/api/queue/dashboard'),
  startQueue: (shopId?: number) =>
    request<QueueDashboardData>(shopId ? `/api/queue/start?shopId=${shopId}` : '/api/queue/start', { method: 'POST' }),
  closeQueue: (shopId?: number) =>
    request<QueueDashboardData>(shopId ? `/api/queue/close?shopId=${shopId}` : '/api/queue/close', { method: 'POST' }),
  manualAddCustomer: (data: { customerName: string; customerPhone?: string }, shopId?: number) =>
    request<QueueDashboardData>(shopId ? `/api/queue/customers/manual?shopId=${shopId}` : '/api/queue/customers/manual', { method: 'POST', body: JSON.stringify(data) }),
  callNextCustomer: (shopId?: number) =>
    request<QueueDashboardData>(shopId ? `/api/queue/customers/next?shopId=${shopId}` : '/api/queue/customers/next', { method: 'POST' }),
  updateCustomerStatus: (customerId: number, status: string, shopId?: number) =>
    request<QueueDashboardData>(shopId ? `/api/queue/customers/${customerId}/status?status=${status}&shopId=${shopId}` : `/api/queue/customers/${customerId}/status?status=${status}`, { method: 'PATCH' }),
  editCustomer: (customerId: number, data: { customerName: string; customerPhone?: string }, shopId?: number) =>
    request<QueueDashboardData>(shopId ? `/api/queue/customers/${customerId}?shopId=${shopId}` : `/api/queue/customers/${customerId}`, { method: 'PUT', body: JSON.stringify(data) }),
  removeCustomer: (customerId: number, shopId?: number) =>
    request<QueueDashboardData>(shopId ? `/api/queue/customers/${customerId}?shopId=${shopId}` : `/api/queue/customers/${customerId}`, { method: 'DELETE' }),

  // Multi-Business & Multi-Branch
  getBusinesses: () => request<BusinessData[]>('/api/owner/businesses'),
  createBusiness: (data: { name: string }) =>
    request<BusinessData>('/api/owner/businesses', { method: 'POST', body: JSON.stringify(data) }),
  getBranches: () => request<BranchData[]>('/api/owner/branches'),
  createBranch: (data: { businessId?: number; name: string; branchName?: string; category?: string; address?: string; phone?: string; avgServiceDurationMinutes?: number; idempotencyKey?: string }) =>
    request<BranchData>('/api/owner/branches', { method: 'POST', body: JSON.stringify(data) }),
  submitBranchPayment: (branchId: number, data: { paymentReference: string; paymentUpiId?: string }) =>
    request<{ paymentId: number; status: string; amount: number; message: string }>(`/api/owner/branches/${branchId}/payment`, { method: 'POST', body: JSON.stringify(data) }),
  submitBulkBranchPayment: (data: { branchIds: number[]; paymentReference: string; paymentUpiId?: string }) =>
    request<{ paymentId: number; status: string; amount: number; message: string }>('/api/owner/branches/bulk-payment', { method: 'POST', body: JSON.stringify(data) }),

  // Device-Bound Auth
  enrollFirstDevice: (data: DeviceEnrollmentData) =>
    request<any>('/api/auth/device-enroll', { method: 'POST', body: JSON.stringify(data) }),
  getDeviceChallenge: (phone: string, deviceRegistrationId: string) =>
    request<DeviceChallengeResponseData>('/api/auth/device-challenge', { method: 'POST', body: JSON.stringify({ phone, deviceRegistrationId }) }),
  deviceLogin: (data: DeviceProofLoginData) =>
    request<any>('/api/auth/device-login', { method: 'POST', body: JSON.stringify(data) }),
  requestDeviceReplacement: (data: DeviceReplacementRequestData) =>
    request<DeviceReplacementData>('/api/auth/device-replacement/request', { method: 'POST', body: JSON.stringify(data) }),
  completeDeviceReplacement: (data: DeviceReplacementCompleteData) =>
    request<any>('/api/auth/device-replacement/complete', { method: 'POST', body: JSON.stringify(data) }),
  logout: () => request<any>('/api/auth/logout', { method: 'POST' }),

  // Staff & User Seats
  getStaff: () => request<StaffData[]>('/api/owner/staff'),
  createStaff: (data: { fullName: string; phone: string; pin: string; branchIds?: number[] }) =>
    request<StaffData>('/api/owner/staff', { method: 'POST', body: JSON.stringify(data) }),
  updateStaffBranches: (staffId: number, branchIds: number[]) =>
    request<StaffData>(`/api/owner/staff/${staffId}/branches`, { method: 'PUT', body: JSON.stringify({ branchIds }) }),
  revokeStaff: (staffId: number) =>
    request<{ message: string }>(`/api/owner/staff/${staffId}/revoke`, { method: 'POST' }),
  resetStaffDevice: (staffId: number) =>
    request<{ message: string }>(`/api/owner/staff/${staffId}/reset-device`, { method: 'POST' }),
  getOwnerDeviceReplacements: () =>
    request<DeviceReplacementData[]>('/api/owner/staff/device-replacements'),
  approveOwnerDeviceReplacement: (id: number) =>
    request<DeviceReplacementData>(`/api/owner/staff/device-replacements/${id}/approve`, { method: 'POST' }),
  rejectOwnerDeviceReplacement: (id: number, reason?: string) =>
    request<DeviceReplacementData>(`/api/owner/staff/device-replacements/${id}/reject${reason ? `?reason=${encodeURIComponent(reason)}` : ''}`, { method: 'POST' }),
  getSeatSummary: () => request<SeatSummaryData>('/api/owner/seats'),
  purchaseSeat: (data: { planType: 'MONTHLY' | 'YEARLY'; paymentReference: string; paymentUpiId?: string }) =>
    request<{ paymentId: number; status: string; amount: number; message: string }>('/api/owner/seats/purchase', { method: 'POST', body: JSON.stringify(data) }),

  // Public Customer QR & Push Notifications
  getPublicQueueInfo: (publicQueueId: string) =>
    request<PublicQueueInfoData>(`/api/public/queue/${publicQueueId}/info`),
  getCanonicalShopQueueInfo: (merchantCode: string) =>
    request<PublicQueueInfoData>(`/api/public/queue/shop/${merchantCode}/info`),
  joinQueue: (publicQueueId: string, data: { customerName: string; customerPhone?: string }) =>
    request<any>(`/api/public/queue/${publicQueueId}/join`, { method: 'POST', body: JSON.stringify(data) }),
  canonicalShopJoinQueue: (merchantCode: string, data: { customerName: string; customerPhone?: string }) =>
    request<any>(`/api/public/queue/shop/${merchantCode}/join`, { method: 'POST', body: JSON.stringify(data) }),
  trackPosition: (trackingToken: string) =>
    request<CustomerPositionData>(`/api/public/queue/track/${trackingToken}`),
  leaveQueue: (trackingToken: string) =>
    request<CustomerPositionData>(`/api/public/queue/track/${trackingToken}/leave`, { method: 'POST' }),
  rejoinQueue: (trackingToken: string) =>
    request<CustomerPositionData>(`/api/public/queue/track/${trackingToken}/rejoin`, { method: 'POST' }),
  getVapidPublicKey: () =>
    request<{ publicKey: string }>('/api/public/queue/vapid-public-key'),
  registerPushSubscription: (trackingToken: string, subscription: { endpoint: string; p256dh: string; auth: string; userAgent?: string }) =>
    request<{ success: boolean; message: string }>(`/api/public/queue/track/${trackingToken}/push-subscription`, {
      method: 'POST',
      body: JSON.stringify(subscription),
    }),
  unregisterPushSubscription: (trackingToken: string) =>
    request<{ success: boolean; message: string }>(`/api/public/queue/track/${trackingToken}/push-subscription`, {
      method: 'DELETE',
    }),

  // Subscription
  getSubscriptionStatus: (shopId?: number) =>
    request<SubscriptionStatusData>(shopId ? `/api/owner/subscription?shopId=${shopId}` : '/api/owner/subscription'),
  submitSubscriptionPayment: (data: { planType: string; paymentReference: string; paymentUpiId?: string }, shopId?: number) =>
    request<SubscriptionStatusData>(shopId ? `/api/owner/subscription/payment?shopId=${shopId}` : '/api/owner/subscription/payment', { method: 'POST', body: JSON.stringify(data) }),

  // Settings
  getShopSettings: (shopId?: number) =>
    request<ShopSettingsData>(shopId ? `/api/owner/settings?shopId=${shopId}` : '/api/owner/settings'),
  updateShopSettings: (data: { name: string; category?: string; avgServiceDurationMinutes: number }, shopId?: number) =>
    request<ShopSettingsData>(shopId ? `/api/owner/settings?shopId=${shopId}` : '/api/owner/settings', { method: 'PUT', body: JSON.stringify(data) }),

  // Category Change Request (₹101)
  getCategoryChangeStatus: (shopId?: number) =>
    request<CategoryChangeStatusData>(shopId ? `/api/owner/category-change/status?shopId=${shopId}` : '/api/owner/category-change/status'),
  initiateCategoryChange: (requestedCategory: string, shopId?: number) =>
    request<CategoryChangeStatusData>(shopId ? `/api/owner/category-change/request?shopId=${shopId}` : '/api/owner/category-change/request', { method: 'POST', body: JSON.stringify({ requestedCategory }) }),
  submitCategoryChangePayment: (data: { requestId: number; paymentReference: string }) =>
    request<CategoryChangeStatusData>('/api/owner/category-change/payment', { method: 'POST', body: JSON.stringify(data) }),

  // Minimal Admin
  getAdminConfig: (adminKey: string) =>
    request<any>('/api/admin/config', { headers: { 'X-Admin-Key': adminKey } }),
  updateAdminSettings: (adminKey: string, data: any) =>
    request<any>('/api/admin/settings', { method: 'POST', headers: { 'X-Admin-Key': adminKey }, body: JSON.stringify(data) }),
  getAdminShops: (adminKey: string) =>
    request<AdminShopItemData[]>('/api/admin/shops', { headers: { 'X-Admin-Key': adminKey } }),
  activateAdminShop: (adminKey: string, data: { shopId: number; planType: string; customDays?: number }) =>
    request<any>('/api/admin/activate', { method: 'POST', headers: { 'X-Admin-Key': adminKey }, body: JSON.stringify(data) }),
  getAdminCategoryChangeRequests: (adminKey: string) =>
    request<AdminCategoryChangeItemData[]>('/api/admin/category-requests', { headers: { 'X-Admin-Key': adminKey } }),
  approveAdminCategoryChange: (adminKey: string, id: number) =>
    request<any>(`/api/admin/category-requests/${id}/approve`, { method: 'POST', headers: { 'X-Admin-Key': adminKey } }),
  rejectAdminCategoryChange: (adminKey: string, id: number, reason: string) =>
    request<any>(`/api/admin/category-requests/${id}/reject`, { method: 'POST', headers: { 'X-Admin-Key': adminKey }, body: JSON.stringify({ reason }) }),
  getAdminPayments: (first?: string | number, second?: string | number, third?: number, fourth?: string) => {
    let status: string | undefined;
    let page = 0;
    let size = 50;
    let adminKey: string | undefined;

    if (typeof first === 'string' && (first.startsWith('admin-') || first.length > 20)) {
      adminKey = first;
      if (typeof second === 'string') status = second;
      if (typeof third === 'number') page = third;
    } else {
      if (typeof first === 'string') status = first;
      if (typeof second === 'number') page = second;
      if (typeof third === 'number') size = third;
      if (typeof fourth === 'string') adminKey = fourth;
    }

    const headers: Record<string, string> = {};
    if (adminKey) headers['X-Admin-Key'] = adminKey;
    const params = new URLSearchParams();
    if (status && status !== 'ALL') params.append('status', status);
    params.append('page', String(page));
    params.append('size', String(size));
    return request<AdminPaymentItemData[]>(`/api/admin/payments?${params.toString()}`, { headers });
  },
  approveAdminPayment: (first: string | number, second?: number | string) => {
    const id = typeof first === 'number' ? first : (typeof second === 'number' ? second : 0);
    const adminKey = typeof first === 'string' ? first : (typeof second === 'string' ? second : undefined);
    const headers: Record<string, string> = {};
    if (adminKey) headers['X-Admin-Key'] = adminKey;
    return request<{ message: string }>(`/api/admin/payments/${id}/approve`, { method: 'POST', headers });
  },
  rejectAdminPayment: (first: string | number, second?: number | string, third?: string) => {
    let id: number;
    let reason: string | undefined;
    let adminKey: string | undefined;
    if (typeof first === 'string') {
      adminKey = first;
      id = typeof second === 'number' ? second : 0;
      reason = third;
    } else {
      id = first;
      reason = typeof second === 'string' ? second : undefined;
      adminKey = third;
    }
    const headers: Record<string, string> = {};
    if (adminKey) headers['X-Admin-Key'] = adminKey;
    return request<{ message: string }>(`/api/admin/payments/${id}/reject`, { method: 'POST', headers, body: JSON.stringify({ reason }) });
  },
  getAdminDeviceReplacements: (adminKey?: string) => {
    const headers: Record<string, string> = {};
    if (adminKey) headers['X-Admin-Key'] = adminKey;
    return request<DeviceReplacementData[]>('/api/admin/device-replacements', { headers });
  },
  approveAdminDeviceReplacement: (first: string | number, second?: number | string) => {
    const id = typeof first === 'number' ? first : (typeof second === 'number' ? second : 0);
    const adminKey = typeof first === 'string' ? first : (typeof second === 'string' ? second : undefined);
    const headers: Record<string, string> = {};
    if (adminKey) headers['X-Admin-Key'] = adminKey;
    return request<DeviceReplacementData>(`/api/admin/device-replacements/${id}/approve`, { method: 'POST', headers });
  },
  rejectAdminDeviceReplacement: (first: string | number, second?: number | string, third?: string) => {
    let id: number;
    let reason: string | undefined;
    let adminKey: string | undefined;
    if (typeof first === 'string') {
      adminKey = first;
      id = typeof second === 'number' ? second : 0;
      reason = third;
    } else {
      id = first;
      reason = typeof second === 'string' ? second : undefined;
      adminKey = third;
    }
    const headers: Record<string, string> = {};
    if (adminKey) headers['X-Admin-Key'] = adminKey;
    return request<DeviceReplacementData>(`/api/admin/device-replacements/${id}/reject`, { method: 'POST', headers, body: JSON.stringify({ reason }) });
  },
  adminResetPin: (adminKey: string, phone: string, newPin: string) =>
    request<any>('/api/admin/reset-owner-pin', { method: 'POST', headers: { 'X-Admin-Key': adminKey }, body: JSON.stringify({ phone, newPin }) }),

  // Super Admin Portal API (Strictly authenticated via Bearer JWT with ROLE_SUPER_ADMIN)
  getSuperAdminDashboardSummary: (adminKey?: string) => {
    const headers: Record<string, string> = {};
    if (adminKey) headers['X-Admin-Key'] = adminKey;
    return request<AdminDashboardSummaryData>('/api/admin/dashboard/summary', { headers });
  },
  getSuperAdminSubscriptions: (first?: string | number, second?: string | number, third?: number, fourth?: string) => {
    let status: string | undefined;
    let page = 0;
    let size = 50;
    let adminKey: string | undefined;

    if (typeof first === 'string' && (first.startsWith('admin-') || first.length > 20)) {
      adminKey = first;
      if (typeof second === 'string') status = second;
      if (typeof third === 'number') page = third;
    } else {
      if (typeof first === 'string') status = first;
      if (typeof second === 'number') page = second;
      if (typeof third === 'number') size = third;
      if (typeof fourth === 'string') adminKey = fourth;
    }

    const headers: Record<string, string> = {};
    if (adminKey) headers['X-Admin-Key'] = adminKey;
    const params = new URLSearchParams();
    if (status && status !== 'ALL') params.append('status', status);
    params.append('page', String(page));
    params.append('size', String(size));
    return request<AdminSubscriptionData[]>(`/api/admin/subscriptions?${params.toString()}`, { headers });
  },
  getSuperAdminOwnerHistory: (first: number | string, second?: number | string) => {
    const ownerId = typeof first === 'number' ? first : Number(second);
    const adminKey = typeof first === 'string' ? first : (typeof second === 'string' ? second : undefined);
    const headers: Record<string, string> = {};
    if (adminKey) headers['X-Admin-Key'] = adminKey;
    return request<{ ownerId: number; ownerName: string; phone: string; email: string; subscriptions: AdminSubscriptionData[]; payments: AdminPaymentItemData[] }>(`/api/admin/subscriptions/owner/${ownerId}/history`, { headers });
  },
  getSuperAdminUsersAndShops: (first?: string | number, second?: string | number, third?: number, fourth?: string) => {
    let query: string | undefined;
    let page = 0;
    let size = 50;
    let adminKey: string | undefined;

    if (typeof first === 'string' && (first.startsWith('admin-') || first.length > 20)) {
      adminKey = first;
      if (typeof second === 'string') query = second;
      if (typeof third === 'number') page = third;
    } else {
      if (typeof first === 'string') query = first;
      if (typeof second === 'number') page = second;
      if (typeof third === 'number') size = third;
      if (typeof fourth === 'string') adminKey = fourth;
    }

    const headers: Record<string, string> = {};
    if (adminKey) headers['X-Admin-Key'] = adminKey;
    const params = new URLSearchParams();
    if (query) params.append('query', query);
    params.append('page', String(page));
    params.append('size', String(size));
    return request<AdminUserShopData[]>(`/api/admin/users-and-shops?${params.toString()}`, { headers });
  },
  getSuperAdminActivityLogs: (first?: number | string, second?: number | string, third?: string) => {
    let page = 0;
    let size = 50;
    let adminKey: string | undefined;

    if (typeof first === 'string' && (first.startsWith('admin-') || first.length > 20)) {
      adminKey = first;
      if (typeof second === 'number') page = second;
    } else {
      if (typeof first === 'number') page = first;
      if (typeof second === 'number') size = second;
      if (typeof third === 'string') adminKey = third;
    }

    const headers: Record<string, string> = {};
    if (adminKey) headers['X-Admin-Key'] = adminKey;
    const params = new URLSearchParams();
    params.append('page', String(page));
    params.append('size', String(size));
    return request<AdminActivityLogData[]>(`/api/admin/activity-logs?${params.toString()}`, { headers });
  },

  // SSE Stream URLs & Authentication Tickets
  getOwnerSseTicket: async (shopId?: number): Promise<string> => {
    const param = shopId ? `?shopId=${shopId}` : '';
    const res = await request<{ ticket: string; expiresInSeconds: number }>(`/api/queue/sse-ticket${param}`, {
      method: 'POST',
    });
    return res.ticket;
  },
  getOwnerSseUrl: (ticket: string, shopId?: number) => {
    const param = shopId ? `&shopId=${shopId}` : '';
    return `${BASE_URL}/api/queue/stream/owner?ticket=${encodeURIComponent(ticket)}${param}`;
  },
  getCustomerSseUrl: (trackingToken: string) => {
    return `${BASE_URL}/api/public/queue/stream/customer/${encodeURIComponent(trackingToken)}`;
  },
  /**
   * Connects to real-time owner queue stream using header-authenticated fetch-based streaming.
   * Completely eliminates tickets and credentials from the URI query string.
   * Gracefully falls back to single-use ticket EventSource if fetch-streaming is unavailable.
   */
  connectOwnerStream: async (
    shopId: number | undefined,
    onMessage: (data: QueueDashboardData) => void,
    onConnected: () => void,
    onError: (err: any) => void,
    abortSignal?: AbortSignal
  ): Promise<() => void> => {
    const token = typeof localStorage !== 'undefined' ? localStorage.getItem('queueless_token') : null;
    const url = `${BASE_URL}/api/queue/stream/owner`;
    const controller = new AbortController();
    const signal = abortSignal || controller.signal;

    let isClosed = false;
    const cleanup = () => {
      isClosed = true;
      controller.abort();
    };

    if (token && typeof ReadableStream !== 'undefined') {
      try {
        const headers: Record<string, string> = {
          'Authorization': `Bearer ${token}`,
          'Accept': 'text/event-stream',
        };
        if (shopId) headers['X-Shop-Id'] = String(shopId);

        const response = await fetch(url, { headers, signal });
        if (!response.ok || !response.body) {
          throw new Error(`SSE stream failed with HTTP ${response.status}`);
        }

        onConnected();
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        (async () => {
          try {
            while (!isClosed) {
              const { value, done } = await reader.read();
              if (done) break;
              buffer += decoder.decode(value, { stream: true });
              const lines = buffer.split('\n\n');
              buffer = lines.pop() || '';
              for (const block of lines) {
                const dataLine = block.split('\n').find((l) => l.startsWith('data:'));
                if (dataLine) {
                  try {
                    const parsed = JSON.parse(dataLine.replace(/^data:\s*/, ''));
                    onMessage(parsed);
                  } catch (e) {
                    console.error('Failed to parse SSE JSON payload', e);
                  }
                }
              }
            }
          } catch (err: any) {
            if (!isClosed && err.name !== 'AbortError') {
              onError(err);
            }
          }
        })();

        return cleanup;
      } catch (err: any) {
        if (err.name === 'AbortError') return cleanup;
        console.warn('Fetch streaming failed; falling back to single-use ticket EventSource', err);
      }
    }

    try {
      const ticket = await api.getOwnerSseTicket(shopId);
      if (isClosed) return cleanup;
      const sseUrl = api.getOwnerSseUrl(ticket, shopId);
      const es = new EventSource(sseUrl);
      es.onopen = () => onConnected();
      es.addEventListener('QUEUE_UPDATED', (e: MessageEvent) => {
        try {
          onMessage(JSON.parse(e.data));
        } catch {}
      });
      es.onerror = (e) => onError(e);
      return () => {
        isClosed = true;
        es.close();
      };
    } catch (e) {
      onError(e);
      return cleanup;
    }
  },
};
