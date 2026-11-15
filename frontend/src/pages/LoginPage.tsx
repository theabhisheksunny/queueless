import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useLanguage } from '../context/LanguageContext';
import { api } from '../api/client';
import { 
  QrCode, 
  Lock, 
  User, 
  Store, 
  Clock, 
  Phone, 
  ArrowRight, 
  Sparkles, 
  Globe, 
  Eye, 
  EyeOff, 
  HelpCircle,
  AlertCircle,
  CheckCircle2
} from 'lucide-react';

const CATEGORIES = [
  'Barber Shop',
  'Salon & Spa',
  'Clinic & Doctor',
  'Small Hospital',
  'Restaurant / Cafe',
  'Repair Shop',
  'Automobile Service',
  'Other Local Business',
];

export const LoginPage: React.FC = () => {
  const { login, register, isLoading } = useAuth();
  const { language, toggleLanguage, t } = useLanguage();
  const navigate = useNavigate();

  const [mode, setMode] = useState<'login' | 'register' | 'recover'>('login');
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  // Form states
  const [phone, setPhone] = useState('');
  const [pin, setPin] = useState('');
  const [confirmPin, setConfirmPin] = useState('');
  const [confirmPinTouched, setConfirmPinTouched] = useState(false);
  const [showPin, setShowPin] = useState(false);
  const [fullName, setFullName] = useState('');
  const [shopName, setShopName] = useState('');
  const [category, setCategory] = useState(CATEGORIES[0]);
  const [avgServiceDurationMinutes, setAvgServiceDurationMinutes] = useState(15);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSuccessMsg(null);

    try {
      if (mode === 'login') {
        const effectivePin = pin.trim() || password.trim();
        const effectivePhone = phone.trim();

        if (effectivePhone && effectivePin) {
          await login({
            phone: effectivePhone,
            pin: effectivePin,
            deviceFingerprint: navigator.userAgent,
          });
          navigate('/');
        } else if (email.trim() && password.trim()) {
          await login({
            email: email.trim(),
            password: password.trim(),
          });
          navigate('/');
        } else {
          setError('Please enter your mobile number and 6-digit PIN.');
        }
      } else if (mode === 'register') {
        const effectivePin = pin.trim() || password.trim();
        const effectiveConfirmPin = confirmPin.trim() || effectivePin;

        if (!phone.trim()) {
          setError('Mobile number is required.');
          return;
        }

        if (effectivePin.length !== 6 || !/^\d{6}$/.test(effectivePin)) {
          setError('PIN must be exactly 6 numeric digits (0-9).');
          return;
        }

        if (effectivePin !== effectiveConfirmPin) {
          setError('PIN and Confirm PIN do not match.');
          return;
        }

        await register({
          fullName: fullName.trim(),
          email: email.trim() || undefined,
          phone: phone.trim(),
          pin: effectivePin,
          confirmPin: effectiveConfirmPin,
          password: effectivePin,
          shopName: shopName.trim(),
          category,
          avgServiceDurationMinutes,
          deviceFingerprint: navigator.userAgent,
        });
        navigate('/');
      } else if (mode === 'recover') {
        if (!phone.trim() || !pin.trim()) {
          setError('Mobile number and new 6-digit PIN are required.');
          return;
        }

        if (pin.trim().length !== 6 || !/^\d{6}$/.test(pin.trim())) {
          setError('New PIN must be exactly 6 numeric digits (0-9).');
          return;
        }

        if (confirmPin.trim() && pin.trim() !== confirmPin.trim()) {
          setError('New PIN and Confirm PIN do not match.');
          return;
        }

        await api.resetPin({
          phone: phone.trim(),
          newPin: pin.trim(),
          confirmPin: confirmPin.trim() || pin.trim(),
          deviceFingerprint: navigator.userAgent,
        });

        setSuccessMsg(t('pinResetSuccess'));
        setPin('');
        setConfirmPin('');
        setMode('login');
      }
    } catch (err: any) {
      setError(err.message || 'Authentication failed');
    }
  };

  const handleDemoLogin = async () => {
    setError(null);
    try {
      await login({
        phone: '9876543210',
        pin: '123456',
        deviceFingerprint: navigator.userAgent,
      });
      navigate('/');
    } catch (err: any) {
      try {
        await login({ email: 'owner@queueless.com', password: 'password123' });
        navigate('/');
      } catch (e: any) {
        setError(err.message || 'Demo login failed');
      }
    }
  };

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col justify-center py-10 px-4 sm:px-6 lg:px-8 relative selection:bg-brand-500 selection:text-white">
      {/* Top right language switch */}
      <div className="absolute top-6 right-6">
        <button
          onClick={toggleLanguage}
          className="flex items-center gap-2 px-3 py-1.5 rounded-2xl bg-white border border-slate-200/80 text-slate-700 shadow-sm text-sm font-semibold hover:bg-slate-50 transition-colors"
        >
          <Globe className="w-4 h-4 text-brand-600" />
          <span>{language === 'en' ? 'हिंदी' : 'English'}</span>
        </button>
      </div>

      <div className="sm:mx-auto sm:w-full sm:max-w-md text-center">
        {/* Brand Icon */}
        <div className="w-16 h-16 rounded-3xl bg-gradient-to-tr from-brand-700 to-brand-500 mx-auto flex items-center justify-center text-white shadow-xl shadow-brand-500/25 mb-3 animate-bounce-short">
          <QrCode className="w-8 h-8" />
        </div>

        <h1 className="text-3xl font-black tracking-tight text-slate-900 mb-1">
          QueueLess
        </h1>
        <p className="text-sm font-semibold text-brand-600 mb-1">
          {t('tagline')}
        </p>
        <p className="text-xs text-slate-500 max-w-xs mx-auto">
          {mode === 'login'
            ? t('pinLoginSubtitle')
            : mode === 'register'
            ? t('pinRegisterSubtitle')
            : t('lostSimRecoveryMsg')}
        </p>
      </div>

      <div className="mt-6 sm:mx-auto sm:w-full sm:max-w-md">
        <div className="bg-white py-8 px-6 shadow-xl shadow-slate-200/50 rounded-3xl sm:px-10 border border-slate-200/80 backdrop-blur-xl">
          
          {/* Tabs: Sign In / Create Account / Reset PIN */}
          <div className="flex p-1 bg-slate-100 rounded-2xl mb-6">
            <button
              type="button"
              onClick={() => {
                setMode('login');
                setError(null);
                setSuccessMsg(null);
              }}
              className={`flex-1 py-2.5 text-xs font-bold rounded-xl transition-all ${
                mode === 'login'
                  ? 'bg-white text-slate-900 shadow-sm'
                  : 'text-slate-500 hover:text-slate-900'
              }`}
            >
              {t('signIn')}
            </button>
            <button
              type="button"
              onClick={() => {
                setMode('register');
                setError(null);
                setSuccessMsg(null);
              }}
              className={`flex-1 py-2.5 text-xs font-bold rounded-xl transition-all ${
                mode === 'register'
                  ? 'bg-white text-slate-900 shadow-sm'
                  : 'text-slate-500 hover:text-slate-900'
              }`}
            >
              {t('signUp')}
            </button>
            {mode === 'recover' && (
              <button
                type="button"
                className="flex-1 py-2.5 text-xs font-bold rounded-xl bg-white text-brand-700 shadow-sm transition-all"
              >
                {t('resetPin')}
              </button>
            )}
          </div>

          {/* Error Alert */}
          {error && (
            <div className="mb-5 p-3.5 rounded-2xl bg-rose-50 border border-rose-200/80 text-rose-700 text-xs font-semibold flex items-center gap-2 animate-shake">
              <AlertCircle className="w-4 h-4 shrink-0 text-rose-500" />
              <span>{error}</span>
            </div>
          )}

          {/* Success Alert */}
          {successMsg && (
            <div className="mb-5 p-3.5 rounded-2xl bg-emerald-50 border border-emerald-200/80 text-emerald-700 text-xs font-semibold flex items-center gap-2">
              <CheckCircle2 className="w-4 h-4 shrink-0 text-emerald-500" />
              <span>{successMsg}</span>
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            
            {/* Registration specific fields */}
            {mode === 'register' && (
              <>
                {/* Shop Name */}
                <div>
                  <label className="block text-xs font-bold uppercase tracking-wider text-slate-600 mb-1">
                    {t('shopName')} <span className="text-rose-500">*</span>
                  </label>
                  <div className="relative">
                    <Store className="w-5 h-5 text-slate-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
                    <input
                      type="text"
                      required
                      value={shopName}
                      onChange={(e) => setShopName(e.target.value)}
                      placeholder="e.g. Royal Hair Salon & Spa"
                      className="w-full pl-11 pr-4 py-3 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/10 outline-none text-slate-900 font-medium transition-all"
                    />
                  </div>
                </div>

                {/* Business Category */}
                <div>
                  <label className="block text-xs font-bold uppercase tracking-wider text-slate-600 mb-1">
                    {t('category')} <span className="text-rose-500">*</span>
                  </label>
                  <select
                    value={category}
                    onChange={(e) => setCategory(e.target.value)}
                    className="w-full px-4 py-3 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/10 outline-none text-slate-900 font-medium bg-white transition-all"
                  >
                    {CATEGORIES.map((cat) => (
                      <option key={cat} value={cat}>
                        {cat}
                      </option>
                    ))}
                  </select>
                </div>

                {/* Full Name */}
                <div>
                  <label className="block text-xs font-bold uppercase tracking-wider text-slate-600 mb-1">
                    {t('fullName')} <span className="text-rose-500">*</span>
                  </label>
                  <div className="relative">
                    <User className="w-5 h-5 text-slate-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
                    <input
                      type="text"
                      required
                      value={fullName}
                      onChange={(e) => setFullName(e.target.value)}
                      placeholder="e.g. Ramesh Sharma"
                      className="w-full pl-11 pr-4 py-3 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/10 outline-none text-slate-900 font-medium transition-all"
                    />
                  </div>
                </div>
              </>
            )}

            {/* Mobile Phone Number (Directly followed by PIN) */}
            <div>
              <label className="block text-xs font-bold uppercase tracking-wider text-slate-600 mb-1">
                {t('phone')} <span className="text-rose-500">*</span>
              </label>
              <div className="relative">
                <div className="absolute left-3.5 top-1/2 -translate-y-1/2 flex items-center gap-1 text-slate-500 font-bold text-xs">
                  <Phone className="w-4 h-4 text-slate-400" />
                  <span>+91</span>
                </div>
                <input
                  type="tel"
                  required
                  maxLength={10}
                  value={phone}
                  onChange={(e) => setPhone(e.target.value.replace(/\D/g, ''))}
                  placeholder="e.g. 9876543210"
                  className="w-full pl-16 pr-4 py-3 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/10 outline-none text-slate-900 font-semibold transition-all"
                />
              </div>
            </div>

            {/* 6-Digit Secret PIN */}
            <div>
              <div className="flex items-center justify-between mb-1">
                <label className="block text-xs font-bold uppercase tracking-wider text-slate-600">
                  {mode === 'recover' ? t('newPin') : t('pin')} <span className="text-rose-500">*</span>
                </label>
                {mode === 'login' && (
                  <button
                    type="button"
                    onClick={() => {
                      setMode('recover');
                      setError(null);
                      setSuccessMsg(null);
                    }}
                    className="text-xs text-brand-600 font-bold hover:underline"
                  >
                    {t('forgotPin')}
                  </button>
                )}
              </div>
              <div className="relative">
                <Lock className="w-5 h-5 text-slate-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
                <input
                  name="pin"
                  data-testid="pin-input"
                  type={showPin ? 'text' : 'password'}
                  inputMode="numeric"
                  pattern="[0-9]*"
                  maxLength={6}
                  required
                  value={pin}
                  onChange={(e) => {
                    const val = e.target.value.replace(/\D/g, '');
                    setPin(val);
                    setPassword(val);
                    if (!confirmPinTouched && mode === 'register') {
                      setConfirmPin(val);
                    }
                  }}
                  placeholder="••••••"
                  className="w-full pl-11 pr-11 py-3 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/10 outline-none text-slate-900 font-mono font-bold tracking-widest transition-all text-center"
                />
                <button
                  type="button"
                  onClick={() => setShowPin(!showPin)}
                  className="absolute right-3.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
                >
                  {showPin ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                </button>
              </div>
            </div>

            {/* Confirm PIN (Register and Recover only) */}
            {mode !== 'login' && (
              <div>
                <label className="block text-xs font-bold uppercase tracking-wider text-slate-600 mb-1">
                  {t('confirmPin')} <span className="text-rose-500">*</span>
                </label>
                <div className="relative">
                  <Lock className="w-5 h-5 text-slate-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
                  <input
                    name="confirmPin"
                    data-testid="confirm-pin-input"
                    type={showPin ? 'text' : 'password'}
                    inputMode="numeric"
                    pattern="[0-9]*"
                    maxLength={6}
                    required
                    value={confirmPin}
                    onChange={(e) => {
                      setConfirmPinTouched(true);
                      setConfirmPin(e.target.value.replace(/\D/g, ''));
                    }}
                    placeholder="••••••"
                    className="w-full pl-11 pr-4 py-3 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/10 outline-none text-slate-900 font-mono font-bold tracking-widest transition-all text-center"
                  />
                </div>
              </div>
            )}

            {/* Avg Service Time (Register only) */}
            {mode === 'register' && (
              <div>
                <label className="block text-xs font-bold uppercase tracking-wider text-slate-600 mb-1">
                  {t('avgServiceTime')} ({t('minutes')})
                </label>
                <div className="relative">
                  <Clock className="w-5 h-5 text-slate-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
                  <input
                    type="number"
                    min="1"
                    max="180"
                    value={avgServiceDurationMinutes}
                    onChange={(e) => setAvgServiceDurationMinutes(Number(e.target.value))}
                    className="w-full pl-11 pr-4 py-3 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/10 outline-none text-slate-900 font-medium transition-all"
                  />
                </div>
              </div>
            )}

            {/* Fallback hidden email input to support existing browser autocomplete */}
            {mode === 'register' && (
              <div className="hidden">
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="owner@example.com"
                />
              </div>
            )}

            {/* Submit Button */}
            <button
              type="submit"
              disabled={isLoading}
              className="w-full mt-2 py-3.5 px-4 rounded-2xl bg-brand-600 hover:bg-brand-700 text-white font-bold text-base shadow-lg shadow-brand-500/25 flex items-center justify-center gap-2 transition-all disabled:opacity-50"
            >
              <span>
                {isLoading
                  ? 'Processing...'
                  : mode === 'login'
                  ? t('loginBtn')
                  : mode === 'recover'
                  ? t('resetPin')
                  : t('registerBtn')}
              </span>
              <ArrowRight className="w-5 h-5" />
            </button>
          </form>

          {/* Account Recovery Guidance (in Recover Mode) */}
          {mode === 'recover' && (
            <div className="mt-6 p-4 rounded-2xl bg-amber-50/80 border border-amber-200/80 text-amber-900 text-xs">
              <div className="flex items-start gap-2">
                <HelpCircle className="w-4 h-4 text-amber-600 shrink-0 mt-0.5" />
                <div>
                  <div className="font-bold text-amber-950 mb-0.5">{t('lostSimRecoveryTitle')}</div>
                  <div className="text-amber-800 leading-relaxed mb-3">{t('lostSimRecoveryMsg')}</div>
                  <button
                    type="button"
                    onClick={() => {
                      setMode('login');
                      setError(null);
                    }}
                    className="font-bold text-amber-900 hover:underline inline-flex items-center gap-1"
                  >
                    <span>← {t('signIn')}</span>
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* Quick Demo Login Button (isolated strictly to development builds) */}
          {import.meta.env.DEV && mode === 'login' && (
            <div className="mt-6 pt-6 border-t border-slate-100 text-center">
              <button
                type="button"
                onClick={handleDemoLogin}
                disabled={isLoading}
                className="w-full py-3 px-4 rounded-2xl bg-amber-50 hover:bg-amber-100/80 border border-amber-200/80 text-amber-900 font-bold text-sm flex items-center justify-center gap-2 transition-colors shadow-sm"
              >
                <Sparkles className="w-4 h-4 text-amber-600" />
                <span>{t('demoLoginBtn')}</span>
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
