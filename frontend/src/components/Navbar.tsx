import React, { useState, useRef, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useLanguage } from '../context/LanguageContext';
import { QrCode, Settings, CreditCard, LogOut, Globe, Users, ChevronDown, Plus, Check, AlertCircle } from 'lucide-react';
import { getTheme } from '../theme/theme';
import { CreateBranchModal } from './CreateBranchModal';

interface NavbarProps {
  onShowQr?: () => void;
  publicQueueId?: string;
  category?: string;
}

export const Navbar: React.FC<NavbarProps> = ({ onShowQr, publicQueueId, category: propCategory }) => {
  const { user, logout, branches, activeShopId, switchShop } = useAuth();
  const { language, toggleLanguage, t } = useLanguage();
  const navigate = useNavigate();

  const [isBranchDropdownOpen, setIsBranchDropdownOpen] = useState(false);
  const [isAddBranchModalOpen, setIsAddBranchModalOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const isOwner = user?.role === 'ROLE_OWNER';
  const isStaff = user?.role === 'ROLE_STAFF';

  const activeBranch = branches.find((b) => b.id === activeShopId) || null;
  const currentCategory = propCategory || activeBranch?.category || user?.category;
  const theme = getTheme(currentCategory);
  const ThemeIcon = theme.icon;

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setIsBranchDropdownOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <>
      <header className="sticky top-0 z-30 bg-white/95 backdrop-blur-md border-b border-slate-200/80 shadow-sm">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between">
          {/* Brand Logo & Branch Switcher */}
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
                  {isStaff ? 'Staff' : 'Digital Token'}
                </span>
              </div>
            </Link>

            {/* Branch Switcher Dropdown */}
            {user && (
              <div className="relative pl-3 border-l border-slate-200" ref={dropdownRef}>
                <button
                  type="button"
                  onClick={() => setIsBranchDropdownOpen(!isBranchDropdownOpen)}
                  className="flex items-center gap-2 px-2.5 py-1.5 rounded-xl hover:bg-slate-100 transition-colors text-left"
                >
                  <ThemeIcon className="w-4 h-4 text-slate-500 shrink-0" />
                  <div className="hidden sm:block">
                    <span className="font-semibold text-slate-800 text-sm max-w-[160px] truncate block">
                      {activeBranch ? (activeBranch.branchName || activeBranch.name) : user.shopName}
                    </span>
                    <span className="text-[10px] text-slate-400 font-medium block">
                      {branches.length > 1 ? `${branches.length} branches • Switch` : '1 branch'}
                    </span>
                  </div>
                  <ChevronDown className="w-4 h-4 text-slate-400 shrink-0 ml-0.5" />
                </button>

                {isBranchDropdownOpen && (
                  <div className="absolute left-0 mt-2 w-72 bg-white rounded-2xl shadow-xl border border-slate-100 py-2 z-50 animate-in fade-in zoom-in-95 duration-100">
                    <div className="px-3 py-1.5 text-[11px] font-bold text-slate-400 uppercase tracking-wider">
                      {isStaff ? 'Assigned Branches' : 'Your Businesses & Branches'}
                    </div>

                    <div className="max-h-60 overflow-y-auto divide-y divide-slate-50">
                      {branches.map((b) => {
                        const isSelected = b.id === (activeBranch ? activeBranch.id : user.shopId);
                        const isPending = b.registrationStatus === 'PAYMENT_PENDING';
                        return (
                          <button
                            key={b.id}
                            type="button"
                            onClick={() => {
                              switchShop(b.id);
                              setIsBranchDropdownOpen(false);
                            }}
                            className={`w-full px-3.5 py-2.5 flex items-center justify-between text-left hover:bg-slate-50 transition-colors ${
                              isSelected ? 'bg-brand-50/60' : ''
                            }`}
                          >
                            <div className="flex-1 min-w-0 pr-2">
                              <div className="flex items-center gap-1.5">
                                <span className={`text-sm font-semibold truncate ${isSelected ? 'text-brand-900' : 'text-slate-800'}`}>
                                  {b.branchName || b.name}
                                </span>
                                {b.isPrimary && (
                                  <span className="text-[10px] bg-slate-100 text-slate-600 font-bold px-1.5 py-0.2 rounded">
                                    Primary
                                  </span>
                                )}
                              </div>
                              <div className="flex items-center gap-1.5 mt-0.5">
                                <span className="text-xs text-slate-500 truncate">{b.category}</span>
                                {isPending && (
                                  <span className="text-[10px] text-amber-700 bg-amber-50 border border-amber-200/80 font-bold px-1.5 py-0.2 rounded flex items-center gap-0.5">
                                    <AlertCircle className="w-2.5 h-2.5" />
                                    ₹99 Due
                                  </span>
                                )}
                              </div>
                            </div>
                            {isSelected && <Check className="w-4 h-4 text-brand-600 shrink-0" />}
                          </button>
                        );
                      })}
                    </div>

                    {isOwner && (
                      <div className="pt-2 mt-1 border-t border-slate-100 px-2">
                        <button
                          type="button"
                          onClick={() => {
                            setIsBranchDropdownOpen(false);
                            setIsAddBranchModalOpen(true);
                          }}
                          className="w-full py-2 px-3 rounded-xl bg-brand-50 hover:bg-brand-100 text-brand-700 text-xs font-semibold flex items-center justify-center gap-1.5 transition-colors"
                        >
                          <Plus className="w-3.5 h-3.5" />
                          <span>Add New Branch (₹99)</span>
                        </button>
                      </div>
                    )}
                  </div>
                )}
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

            {/* Staff Management Link (Owner Only) */}
            {isOwner && (
              <Link
                to="/staff"
                className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-xl text-xs sm:text-sm font-medium text-slate-700 hover:text-brand-600 hover:bg-slate-100 transition-colors"
                title="Manage Staff & User Seats"
              >
                <Users className="w-4 h-4 text-slate-500" />
                <span className="hidden md:inline">Staff & Seats</span>
              </Link>
            )}

            {/* Subscription Status Link (Owner Only) */}
            {isOwner && user && (
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

            {/* Staff role badge for non-owners */}
            {isStaff && (
              <span className="text-xs px-2.5 py-1 rounded-xl bg-slate-100 text-slate-700 font-semibold border border-slate-200">
                Staff Operator
              </span>
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

            {/* Shop Settings (Owner Only) */}
            {isOwner && user && (
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

      {/* Modal for creating a new branch */}
      {isOwner && isAddBranchModalOpen && (
        <CreateBranchModal
          isOpen={isAddBranchModalOpen}
          onClose={() => setIsAddBranchModalOpen(false)}
          onCreated={(branchId) => {
            switchShop(branchId);
          }}
        />
      )}
    </>
  );
};
