import React from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useLanguage } from '../context/LanguageContext';
import { QrCode, Settings, CreditCard, LogOut, Globe } from 'lucide-react';

interface NavbarProps {
  onShowQr?: () => void;
  publicQueueId?: string;
}

export const Navbar: React.FC<NavbarProps> = ({ onShowQr, publicQueueId }) => {
  const { user, logout } = useAuth();
  const { language, toggleLanguage, t } = useLanguage();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <header className="sticky top-0 z-30 bg-white/95 backdrop-blur-md border-b border-slate-200/80 shadow-sm">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between">
        {/* Brand Logo & Shop Name */}
        <div className="flex items-center gap-3">
          <Link to="/" className="flex items-center gap-2 group">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-brand-700 to-brand-500 flex items-center justify-center text-white shadow-md shadow-brand-500/25 group-hover:scale-105 transition-transform">
              <QrCode className="w-5 h-5" />
            </div>
            <div>
              <span className="text-xl font-bold tracking-tight text-slate-900 group-hover:text-brand-600 transition-colors">
                QueueLess
              </span>
              <span className="hidden sm:inline-block ml-1.5 text-xs font-semibold px-2 py-0.5 rounded-full bg-brand-50 text-brand-700 border border-brand-200/60">
                Digital Token
              </span>
            </div>
          </Link>

          {user?.shopName && (
            <div className="hidden md:flex items-center gap-2 pl-4 border-l border-slate-200">
              <span className="font-semibold text-slate-800 text-sm max-w-[200px] truncate">
                {user.shopName}
              </span>
              <span className="text-xs px-2 py-0.5 rounded-md bg-slate-100 text-slate-600 font-medium">
                {user.category}
              </span>
            </div>
          )}
        </div>

        {/* Right Navigation & Tools */}
        <div className="flex items-center gap-2 sm:gap-3">
          {/* Quick QR button if on dashboard */}
          {onShowQr && publicQueueId && (
            <button
              onClick={onShowQr}
              className="hidden sm:inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-brand-50 text-brand-700 hover:bg-brand-100 font-medium text-sm transition-colors border border-brand-200/80"
              title={t('showQrCode')}
            >
              <QrCode className="w-4 h-4 text-brand-600" />
              <span>QR Code</span>
            </button>
          )}

          {/* Subscription Status Link */}
          {user && (
            <Link
              to="/subscription"
              className={`flex items-center gap-1.5 px-2.5 py-1.5 rounded-xl text-xs sm:text-sm font-medium border transition-colors ${
                user.subscriptionActive
                  ? 'bg-emerald-50 text-emerald-800 border-emerald-200/80 hover:bg-emerald-100'
                  : 'bg-rose-50 text-rose-800 border-rose-200/80 hover:bg-rose-100'
              }`}
              title={user.subscriptionActive ? `${user.subscriptionDaysRemaining} days remaining` : 'Subscription Expired'}
            >
              <CreditCard className="w-4 h-4" />
              <span className="hidden xs:inline">
                {user.subscriptionActive
                  ? `${user.subscriptionDaysRemaining}d ${t('days')}`
                  : t('subExpired')}
              </span>
            </Link>
          )}

          {/* Language Switch Toggle */}
          <button
            onClick={toggleLanguage}
            className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-xl bg-slate-100 hover:bg-slate-200/80 text-slate-700 text-xs sm:text-sm font-medium transition-colors"
            title="Switch Language / भाषा बदलें"
          >
            <Globe className="w-4 h-4 text-slate-500" />
            <span className="font-semibold">{language === 'en' ? 'हिंदी' : 'EN'}</span>
          </button>

          {/* Shop Settings */}
          {user && (
            <Link
              to="/settings"
              className="p-2 rounded-xl text-slate-600 hover:text-slate-900 hover:bg-slate-100 transition-colors"
              title={t('settings')}
            >
              <Settings className="w-5 h-5" />
            </Link>
          )}

          {/* Logout */}
          {user && (
            <button
              onClick={handleLogout}
              className="p-2 rounded-xl text-slate-500 hover:text-rose-600 hover:bg-rose-50 transition-colors"
              title={t('logout')}
            >
              <LogOut className="w-5 h-5" />
            </button>
          )}
        </div>
      </div>
    </header>
  );
};
