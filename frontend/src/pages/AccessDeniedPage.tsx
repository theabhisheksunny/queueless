import React from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ShieldAlert, ArrowLeft, Home, LogOut, Store } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { useLanguage } from '../context/LanguageContext';

interface AccessDeniedPageProps {
  reason?: 'owner_only' | 'super_admin_only' | 'branch_unauthorized' | 'no_branches' | 'general';
  customMessage?: string;
}

export const AccessDeniedPage: React.FC<AccessDeniedPageProps> = ({
  reason: propReason,
  customMessage,
}) => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { user, branches, switchShop, logout, isAuthenticated } = useAuth();
  const { language } = useLanguage();

  const isHindi = language === 'hi';
  const reason = propReason || (searchParams.get('reason') as any) || 'general';

  const isStaff = user?.role === 'ROLE_STAFF';

  const getTitle = () => {
    if (reason === 'super_admin_only') {
      return isHindi ? 'सुपर एडमिन पहुंच आवश्यक' : 'Super Administrator Access Required';
    }
    if (reason === 'owner_only') {
      return isHindi ? 'केवल दुकान मालिक के लिए' : 'Shop Owner Access Only';
    }
    if (reason === 'branch_unauthorized') {
      return isHindi ? 'शाखा पहुंच प्रतिबंधित है' : 'Branch Access Restricted';
    }
    if (reason === 'no_branches') {
      return isHindi ? 'कोई शाखा आवंटित नहीं है' : 'No Branches Assigned';
    }
    return isHindi ? 'पहुंच प्रतिबंधित है' : 'Access Restricted';
  };

  const getMessage = () => {
    if (customMessage) return customMessage;

    if (reason === 'super_admin_only') {
      return isHindi
        ? 'यह अनुभाग केवल प्लेटफ़ॉर्म सुपर एडमिनिस्ट्रेटर के लिए आरक्षित है। आपके खाते के पास इस संसाधन तक पहुँचने की अनुमति नहीं है।'
        : 'This section is reserved exclusively for Platform Super Administrators. Your account does not have permission to access Super Admin resources.';
    }
    if (reason === 'owner_only') {
      return isHindi
        ? 'यह अनुभाग (स्टाफ प्रबंधन और सदस्यता बिलिंग) विशेष रूप से दुकान मालिक के लिए आरक्षित है। आपका खाता स्टाफ के रूप में लॉग इन है।'
        : 'This section (Staff Management & Subscription Billing) is reserved exclusively for the shop owner account. Your account is logged in as Staff.';
    }
    if (reason === 'branch_unauthorized') {
      return isHindi
        ? 'आपका स्टाफ खाता इस शाखा के लिए अधिकृत नहीं है। कृपया अपनी अधिकृत शाखा चुनें या दुकान मालिक से संपर्क करें।'
        : 'Your staff account does not have permission for this branch. Please switch to an authorized branch or contact your shop owner.';
    }
    if (reason === 'no_branches') {
      return isHindi
        ? 'आपके स्टाफ खाते को वर्तमान में किसी शाखा में नहीं जोड़ा गया है। कृपया अपने दुकान मालिक से शाखा पहुंच प्रदान करने का अनुरोध करें।'
        : 'Your staff account is not currently assigned to any physical branch. Please ask your shop owner to grant you access to a branch.';
    }
    return isHindi
      ? 'आपके पास इस संसाधन या दुकान को देखने की अनुमति नहीं है। यदि यह कोई गलती है, तो कृपया अपने दुकान मालिक से संपर्क करें।'
      : 'You do not have permission to view or manage this resource. If you believe this is an error, please contact your shop owner.';
  };

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col items-center justify-center p-4 selection:bg-brand-500 selection:text-white">
      <div className="max-w-md w-full text-center bg-white rounded-3xl p-8 border border-slate-200 shadow-xl shadow-slate-200/50">
        {/* Visual Icon */}
        <div className="w-16 h-16 rounded-2xl bg-amber-50 border border-amber-100 flex items-center justify-center mx-auto mb-5 text-amber-600 shadow-md shadow-amber-500/10">
          <ShieldAlert className="w-8 h-8" />
        </div>

        {/* 403 Badge */}
        <div className="inline-flex items-center px-3 py-1 rounded-full text-xs font-bold uppercase tracking-wider bg-amber-100/80 text-amber-800 mb-4">
          403 — {isHindi ? 'अनुमति नहीं है' : 'Access Restricted'}
        </div>

        {/* Heading */}
        <h1 className="text-2xl font-extrabold text-slate-900 tracking-tight mb-2">
          {getTitle()}
        </h1>

        {/* Description */}
        <p className="text-slate-600 text-sm leading-relaxed mb-6">
          {getMessage()}
        </p>

        {/* If user is staff and has multiple authorized branches, allow switching directly */}
        {isStaff && branches && branches.length > 0 && reason === 'branch_unauthorized' && (
          <div className="mb-6 p-4 bg-slate-50 rounded-2xl border border-slate-200 text-left">
            <div className="text-xs font-bold uppercase tracking-wider text-slate-500 mb-2 flex items-center gap-1.5">
              <Store className="w-3.5 h-3.5" />
              <span>{isHindi ? 'आपकी अधिकृत शाखाएं:' : 'Your Authorized Branches:'}</span>
            </div>
            <div className="space-y-2">
              {branches.map((b) => (
                <button
                  key={b.id}
                  onClick={() => {
                    switchShop(b.id);
                    navigate('/');
                  }}
                  className="w-full text-left px-3 py-2 rounded-xl bg-white hover:bg-brand-50 border border-slate-200 hover:border-brand-300 text-xs font-semibold text-slate-800 flex items-center justify-between transition-colors"
                >
                  <span>{b.branchName || b.name}</span>
                  <span className="text-[10px] text-brand-600 font-bold uppercase">{isHindi ? 'चुनें' : 'Select'} &rarr;</span>
                </button>
              ))}
            </div>
          </div>
        )}

        {/* Action Buttons */}
        <div className="flex flex-col sm:flex-row items-center justify-center gap-3">
          <button
            onClick={() => navigate(-1)}
            className="w-full sm:w-auto px-5 py-3 rounded-2xl border border-slate-200 bg-white hover:bg-slate-50 text-slate-700 font-semibold text-sm transition-all flex items-center justify-center gap-2 shadow-sm"
          >
            <ArrowLeft className="w-4 h-4" />
            <span>{isHindi ? 'पीछे जाएं' : 'Go Back'}</span>
          </button>

          <button
            onClick={() => navigate(isAuthenticated ? '/' : '/login')}
            className="w-full sm:w-auto px-5 py-3 rounded-2xl bg-brand-600 hover:bg-brand-700 text-white font-semibold text-sm shadow-md shadow-brand-500/20 transition-all flex items-center justify-center gap-2"
          >
            <Home className="w-4 h-4" />
            <span>{isAuthenticated ? (isHindi ? 'डैशबोर्ड पर जाएं' : 'Go to Dashboard') : (isHindi ? 'लॉगिन करें' : 'Sign In')}</span>
          </button>
        </div>

        {/* Sign Out Option for switching accounts */}
        {isAuthenticated && (
          <div className="mt-6 pt-5 border-t border-slate-100">
            <button
              onClick={() => {
                logout();
                navigate('/login');
              }}
              className="text-xs text-slate-500 hover:text-slate-800 font-medium inline-flex items-center gap-1.5 transition-colors"
            >
              <LogOut className="w-3.5 h-3.5" />
              <span>{isHindi ? 'दूसरे खाते से लॉगिन करें' : 'Sign in with a different account'}</span>
            </button>
          </div>
        )}

        {/* Brand Footer */}
        <div className="mt-8 text-xs text-slate-400 font-medium">
          QueueLess • Digital Token & Queue Operating System
        </div>
      </div>
    </div>
  );
};
