import React from 'react';
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { AuthProvider, useAuth } from './context/AuthContext';
import { LanguageProvider } from './context/LanguageContext';
import { ErrorBoundary } from './components/ErrorBoundary';
import { LoginPage } from './pages/LoginPage';
import { DashboardPage } from './pages/DashboardPage';
import { CustomerJoinPage } from './pages/CustomerJoinPage';
import { CustomerTrackPage } from './pages/CustomerTrackPage';
import { SubscriptionPage } from './pages/SubscriptionPage';
import { SettingsPage } from './pages/SettingsPage';
import { StaffPage } from './pages/StaffPage';
import { AdminPage } from './pages/AdminPage';
import { SuperAdminPage } from './pages/SuperAdminPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { AccessDeniedPage } from './pages/AccessDeniedPage';
import { sanitizeReturnUrl } from './utils/urlSecurity';

// Protected Route for Authenticated Users (Owner and Staff) with Return URL Preservation
const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isAuthenticated } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    const returnUrl = sanitizeReturnUrl(location.pathname + location.search);
    return (
      <Navigate
        to={`/login?redirect=${encodeURIComponent(returnUrl)}`}
        state={{ from: location }}
        replace
      />
    );
  }
  return <>{children}</>;
};

// Owner-Only Protected Route: Staff accounts see friendly 403 Access Denied screen
const OwnerRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, isAuthenticated } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    const returnUrl = sanitizeReturnUrl(location.pathname + location.search);
    return (
      <Navigate
        to={`/login?redirect=${encodeURIComponent(returnUrl)}`}
        state={{ from: location }}
        replace
      />
    );
  }

  if (user?.role === 'ROLE_STAFF') {
    return <AccessDeniedPage reason="owner_only" />;
  }

  return <>{children}</>;
};

// Super Admin Route Guard: Prevents Shop Owners & Staff from entering the portal
const SuperAdminRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, isAuthenticated } = useAuth();

  if (isAuthenticated && (user?.role === 'ROLE_OWNER' || user?.role === 'ROLE_STAFF')) {
    return <AccessDeniedPage reason="super_admin_only" />;
  }

  return <>{children}</>;
};

export const App: React.FC = () => {
  return (
    <ErrorBoundary>
      <LanguageProvider>
        <AuthProvider>
          <BrowserRouter>
            <Routes>
              {/* Public Customer Routes */}
              <Route path="/join/:publicQueueId" element={<CustomerJoinPage />} />
              <Route path="/join/shop/:merchantCode" element={<CustomerJoinPage />} />
              <Route path="/track/:trackingToken" element={<CustomerTrackPage />} />

              {/* Owner & Staff Authentication */}
              <Route path="/login" element={<LoginPage />} />

              {/* Protected Routes (Owner and Staff) */}
              <Route
                path="/"
                element={
                  <ProtectedRoute>
                    <DashboardPage />
                  </ProtectedRoute>
                }
              />
              <Route
                path="/settings"
                element={
                  <ProtectedRoute>
                    <SettingsPage />
                  </ProtectedRoute>
                }
              />

              {/* Owner-Only Routes */}
              <Route
                path="/subscription"
                element={
                  <OwnerRoute>
                    <SubscriptionPage />
                  </OwnerRoute>
                }
              />
              <Route
                path="/staff"
                element={
                  <OwnerRoute>
                    <StaffPage />
                  </OwnerRoute>
                }
              />

              {/* Access Denied (403), Admin & Dedicated Super Admin Portal */}
              <Route path="/access-denied" element={<AccessDeniedPage />} />
              <Route path="/forbidden" element={<AccessDeniedPage />} />
              <Route path="/admin" element={<AdminPage />} />
              <Route
                path="/super-admin"
                element={
                  <SuperAdminRoute>
                    <SuperAdminPage />
                  </SuperAdminRoute>
                }
              />
              <Route
                path="/super-admin/*"
                element={
                  <SuperAdminRoute>
                    <SuperAdminPage />
                  </SuperAdminRoute>
                }
              />
              <Route path="/superadmin" element={<Navigate to="/super-admin" replace />} />

              {/* 404 Fallback with helpful navigation */}
              <Route path="*" element={<NotFoundPage />} />
            </Routes>
          </BrowserRouter>
        </AuthProvider>
      </LanguageProvider>
    </ErrorBoundary>
  );
};

export default App;
